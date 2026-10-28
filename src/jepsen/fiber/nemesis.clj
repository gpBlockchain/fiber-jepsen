(ns jepsen.fiber.nemesis
  "Faults beyond jepsen.nemesis.combined's kill/pause/partition:

  - :term          graceful SIGTERM, wait up to --term-timeout for the process to
                   exit (SIGKILL if it doesn't), then restart. Kept separate from
                   :kill (SIGKILL) so the two can be compared.
  - :ckb-partition iptables drops traffic between fnn node(s) and the CKB chain
                   container. CKB is treated as an external dependency.
  - :ckb-latency   tc netem delay + jitter on fnn -> chain traffic only.
  - :latency       tc netem delay, jitter and reorder on fnn <-> fnn traffic.
  - :loss          tc netem packet loss on fnn <-> fnn traffic.
  - :disconnect    calls fnn's disconnect_peer RPC on one node for one or all of
                   its connected peers; healing reconnects with connect_peer
                   (--disconnect-reconnect rpc, default) or leaves it to fnn (auto).
  - :clock         per-process clock skew with libfaketime (fnn reads its offset
                   from a file on every call, so the skew changes without a restart).

  Shaping uses one prio qdisc per node with two netem bands: band 4 for traffic to
  the chain, band 5 for traffic to the other fnn nodes. Faults change the netem
  parameters of a band and healing resets them, so CKB and peer shaping can be
  active at the same time."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [generator :as gen]
                    [nemesis :as n]]
            [jepsen.control.util :as cu]
            [jepsen.fiber [db :as db]
                          [rpc :as rpc]
                          [topology :as topology]]))

(def chain-ip "10.77.0.10")
(def iface "eth0")

(defn- pick-targets
  "One random node or all of them, per `targets` (a collection of :one / :all)."
  [test targets]
  (case (rand-nth (vec targets))
    :all (vec (:nodes test))
    [(rand-nth (vec (:nodes test)))]))

(defn- on! [test nodes f]
  (c/on-nodes test nodes (fn [_ node] (c/su (f node)))))

;; --------------------------------------------------------------------- term

(defn- alive? [pid]
  (try (c/exec :kill :-0 pid) true
       (catch Exception _ false)))

(defn term-node!
  "Sends SIGTERM and waits up to timeout-s for fnn to exit. Returns a map with
  :exited? and :ms; a process that doesn't exit in time is SIGKILLed."
  [node timeout-s]
  (let [pid (try (str/trim (c/exec :cat db/pidfile)) (catch Exception _ nil))]
    (if (or (str/blank? pid) (not (alive? pid)))
      {:node node, :exited? :not-running}
      (let [t0       (System/currentTimeMillis)
            deadline (+ t0 (* 1000 timeout-s))]
        (c/exec :kill :-TERM pid)
        (loop []
          (cond
            (not (alive? pid))
            (do (c/exec :rm :-f db/pidfile)
                {:node node, :exited? true, :ms (- (System/currentTimeMillis) t0)})

            (< deadline (System/currentTimeMillis))
            (do (warn node "fnn did not exit within" timeout-s "s of SIGTERM; SIGKILL")
                (cu/stop-daemon! db/binary db/pidfile)
                {:node node, :exited? false, :ms (- (System/currentTimeMillis) t0)})

            :else (do (Thread/sleep 200) (recur))))))))

(defrecord TermNemesis [timeout-s]
  n/Nemesis
  (setup! [this _test] this)
  (invoke! [_ test op]
    (case (:f op)
      :term       (let [nodes (pick-targets test (:term-targets test [:one]))
                        res   (on! test nodes #(term-node! % timeout-s))]
                    (assoc op :value res))
      :term-start (assoc op :value (on! test (:nodes test)
                                        #(db/start-fnn! test %)))))
  (teardown! [_ _test])
  n/Reflection
  (fs [_] #{:term :term-start}))

(defn term-package
  [opts]
  (when ((:faults opts) :term)
    {:nemesis         (TermNemesis. (:term-timeout opts 30))
     :generator       (->> (gen/flip-flop (gen/repeat {:type :info, :f :term})
                                          (gen/repeat {:type :info, :f :term-start}))
                           (gen/stagger (:interval opts)))
     :final-generator {:type :info, :f :term-start}
     :perf            #{{:name "term", :start #{:term}, :stop #{:term-start}
                         :color "#E9A0A0"}}}))

;; ------------------------------------------------------------ ckb-partition

(defn- iptables-ckb! [action]
  (doseq [rule [["OUTPUT" "-d" chain-ip] ["INPUT" "-s" chain-ip]]]
    (try (apply c/exec :iptables action (concat rule ["-j" "DROP"]))
         (catch Exception e
           (when (= "-A" action) (throw e))))))

(defn- ckb-heal! [_node]
  ; Delete repeatedly in case a rule was added twice.
  (dotimes [_ 3] (iptables-ckb! "-D"))
  :healed)

(defrecord CkbPartitionNemesis []
  n/Nemesis
  (setup! [this test] (on! test (:nodes test) ckb-heal!) this)
  (invoke! [_ test op]
    (case (:f op)
      :ckb-partition (let [nodes (pick-targets test (:ckb-targets test [:one :all]))]
                       (on! test nodes (fn [_] (iptables-ckb! "-A") :cut))
                       (assoc op :value nodes))
      :ckb-heal      (assoc op :value (on! test (:nodes test) ckb-heal!))))
  (teardown! [_ test] (on! test (:nodes test) ckb-heal!))
  n/Reflection
  (fs [_] #{:ckb-partition :ckb-heal}))

(defn ckb-partition-package
  [opts]
  (when ((:faults opts) :ckb-partition)
    {:nemesis         (CkbPartitionNemesis.)
     :generator       (->> (gen/flip-flop (gen/repeat {:type :info, :f :ckb-partition})
                                          (gen/repeat {:type :info, :f :ckb-heal}))
                           (gen/stagger (:interval opts)))
     :final-generator {:type :info, :f :ckb-heal}
     :perf            #{{:name "ckb-part", :start #{:ckb-partition}, :stop #{:ckb-heal}
                         :color "#A0C8E9"}}}))

;; ---------------------------------------------------------------- tc shaping

(def ckb-band "1:4")
(def peer-band "1:5")
(def handles {ckb-band "40:", peer-band "50:"})

(defn- tc-reset!
  "(Re)creates the prio qdisc with two pass-through netem bands and filters
  sending chain traffic to band 4 and other fnn nodes' traffic to band 5."
  [test node]
  (try (c/exec :tc :qdisc :del :dev iface :root) (catch Exception _))
  (c/exec :tc :qdisc :add :dev iface :root :handle "1:" :prio :bands 5
          :priomap 1 2 2 2 1 2 0 0 1 1 1 1 1 1 1 1)
  (doseq [band [ckb-band peer-band]]
    (c/exec :tc :qdisc :add :dev iface :parent band :handle (handles band)
            :netem :delay "0ms"))
  (c/exec :tc :filter :add :dev iface :parent "1:0" :protocol :ip :prio 1 :u32
          :match :ip :dst (str chain-ip "/32") :flowid ckb-band)
  (doseq [peer (remove #{node} (:nodes test))]
    (c/exec :tc :filter :add :dev iface :parent "1:0" :protocol :ip :prio 2 :u32
            :match :ip :dst (str (topology/node-ip peer) "/32") :flowid peer-band))
  :reset)

(defn- netem! [band args]
  (apply c/exec :tc :qdisc :change :dev iface :parent band :handle (handles band)
         :netem args))

(defn- latency-args [{:keys [delay jitter reorder loss]}]
  (cond-> []
    true    (into [:delay (str delay "ms") (str jitter "ms") "25%"])
    reorder (into [:reorder (str reorder "%") "50%"])
    loss    (into [:loss (str loss "%") "25%"])))

(defrecord ShapeNemesis [opts]
  n/Nemesis
  (setup! [this test] (on! test (:nodes test) #(tc-reset! test %)) this)
  (invoke! [_ test op]
    (let [{:keys [ckb-delay ckb-jitter net-delay net-jitter net-loss net-reorder]} opts]
      (case (:f op)
        :ckb-latency   (let [nodes (pick-targets test (:ckb-targets test [:one :all]))
                             p     {:delay ckb-delay, :jitter ckb-jitter}]
                         (on! test nodes (fn [_] (netem! ckb-band (latency-args p))))
                         (assoc op :value {:nodes nodes, :netem p}))
        :ckb-latency-heal (do (on! test (:nodes test)
                                   (fn [_] (netem! ckb-band [:delay "0ms"])))
                              (assoc op :value :healed))
        :latency       (let [nodes (pick-targets test [:one :all])
                             p     {:delay net-delay, :jitter net-jitter, :reorder net-reorder}]
                         (on! test nodes (fn [_] (netem! peer-band (latency-args p))))
                         (assoc op :value {:nodes nodes, :netem p}))
        :loss          (let [nodes (pick-targets test [:one :all])
                             p     {:delay 0, :jitter 0, :loss net-loss}]
                         (on! test nodes (fn [_] (netem! peer-band (latency-args p))))
                         (assoc op :value {:nodes nodes, :netem p}))
        :net-heal      (do (on! test (:nodes test)
                                (fn [_] (netem! peer-band [:delay "0ms"])))
                           (assoc op :value :healed)))))
  (teardown! [_ test]
    (on! test (:nodes test)
         (fn [_] (try (c/exec :tc :qdisc :del :dev iface :root) (catch Exception _)))))
  n/Reflection
  (fs [_] #{:ckb-latency :ckb-latency-heal :latency :loss :net-heal}))

(defn shape-package
  [opts]
  (let [faults (:faults opts)
        ckb?   (faults :ckb-latency)
        peer   (filterv faults [:latency :loss])]
    (when (or ckb? (seq peer))
      (let [nem   (ShapeNemesis. opts)
            gens  (cond-> []
                    ckb?       (conj (gen/flip-flop
                                       (gen/repeat {:type :info, :f :ckb-latency})
                                       (gen/repeat {:type :info, :f :ckb-latency-heal})))
                    (seq peer) (conj (gen/flip-flop
                                       (map (fn [_] {:type :info, :f (rand-nth peer)})
                                            (range))
                                       (gen/repeat {:type :info, :f :net-heal}))))]
        {:nemesis         nem
         :generator       (gen/stagger (:interval opts) (gen/mix gens))
         :final-generator (cond-> []
                            ckb?       (conj {:type :info, :f :ckb-latency-heal})
                            (seq peer) (conj {:type :info, :f :net-heal}))
         :perf            (cond-> #{}
                            ckb?       (conj {:name "ckb-lat", :start #{:ckb-latency}
                                              :stop #{:ckb-latency-heal} :color "#C8A0E9"})
                            (seq peer) (conj {:name "net-shape", :start #{:latency :loss}
                                              :stop #{:net-heal} :color "#E9D8A0"}))}))))

;; --------------------------------------------------------------- disconnect

(defn- pubkey->node [test pk]
  (some (fn [[n k]] (when (= k pk) n)) @(:pubkeys test)))

(defn- disconnect!
  "Picks a node and calls disconnect_peer for one or all of its peers."
  [test]
  (let [node  (rand-nth (vec (:nodes test)))
        peers (mapv :pubkey (:peers (rpc/call node "list_peers")))
        pks   (case (rand-nth (vec (:disconnect-targets test [:one :all])))
                :all peers
                (if (seq peers) [(rand-nth peers)] []))]
    {:node  node
     :peers (vec (for [pk pks]
                   {:peer   (pubkey->node test pk)
                    :result (try (rpc/call node "disconnect_peer" {:pubkey pk}) :ok
                                 (catch Exception e (ex-message e)))}))}))

(defn- reconnect!
  "Reconnects every channel edge whose ends aren't peers any more, using
  connect_peer from the opener with the acceptor's p2p address."
  [test]
  (vec (for [[a b] (topology/edges (:nodes test))
             :let  [pk-b      (get @(:pubkeys test) b)
                    connected (try (some #(= pk-b (:pubkey %))
                                         (:peers (rpc/call a "list_peers")))
                                   (catch Exception _ ::unknown))]
             :when (not connected)]
         {:from a, :to b
          :result (try (rpc/call a "connect_peer"
                                 {:address (topology/p2p-address b (rpc/call b "node_info"))})
                       :ok
                       (catch Exception e (ex-message e)))})))

(defrecord DisconnectNemesis [mode]
  n/Nemesis
  (setup! [this _test] this)
  (invoke! [_ test op]
    (case (:f op)
      :disconnect           (assoc op :value (try (disconnect! test)
                                                  (catch Exception e (ex-message e))))
      :disconnect-reconnect (assoc op :value (if (and (= :auto mode) (not (:force (:value op))))
                                               :auto
                                               (reconnect! test)))))
  (teardown! [_ _test])
  n/Reflection
  (fs [_] #{:disconnect :disconnect-reconnect}))

(defn disconnect-package
  [opts]
  (when ((:faults opts) :disconnect)
    (let [mode (:disconnect-reconnect opts :rpc)]
      {:nemesis         (DisconnectNemesis. mode)
       :generator       (->> (gen/flip-flop (gen/repeat {:type :info, :f :disconnect})
                                            (gen/repeat {:type :info, :f :disconnect-reconnect}))
                             (gen/stagger (:interval opts)))
       ; Always reconnect via RPC at the end so the probe isn't blocked on auto-reconnect.
       :final-generator {:type :info, :f :disconnect-reconnect, :value {:force true}}
       :perf            #{{:name "disconnect", :start #{:disconnect}
                           :stop #{:disconnect-reconnect} :color "#E9C0A0"}}})))

;; -------------------------------------------------------------------- clock

(defn- write-offset! [secs]
  (cu/write-file! (format "%+d" (long secs)) db/faketime-file)
  secs)

(defrecord ClockNemesis [max-skew]
  n/Nemesis
  (setup! [this test] (on! test (:nodes test) (fn [_] (write-offset! 0))) this)
  (invoke! [_ test op]
    (case (:f op)
      :clock-skew  (let [nodes (pick-targets test [:one :all])
                         res   (on! test nodes
                                    (fn [_] (write-offset!
                                              (- (rand-int (inc (* 2 max-skew))) max-skew))))]
                     (assoc op :value res))
      :clock-reset (assoc op :value (on! test (:nodes test) (fn [_] (write-offset! 0))))))
  (teardown! [_ test] (on! test (:nodes test) (fn [_] (write-offset! 0))))
  n/Reflection
  (fs [_] #{:clock-skew :clock-reset}))

(defn clock-package
  [opts]
  (when ((:faults opts) :clock)
    {:nemesis         (ClockNemesis. (:clock-skew-max opts 60))
     :generator       (->> (gen/flip-flop (gen/repeat {:type :info, :f :clock-skew})
                                          (gen/repeat {:type :info, :f :clock-reset}))
                           (gen/stagger (:interval opts)))
     :final-generator {:type :info, :f :clock-reset}
     :perf            #{{:name "clock", :start #{:clock-skew}, :stop #{:clock-reset}
                         :color "#A0E9B8"}}}))

(defn packages
  "All extra packages enabled by opts (nil ones dropped)."
  [opts]
  (remove nil? [(term-package opts)
                (ckb-partition-package opts)
                (shape-package opts)
                (disconnect-package opts)
                (clock-package opts)]))
