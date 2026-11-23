(ns jepsen.fiber.faults.partition
  "The partition fault: iptables drops all traffic between groups of nodes,
  through jepsen.nemesis.combined's partition package. A target spec
  (--partition-targets) is one of:
    :one   one node, isolated from the others (the default);
    :half  a random half of the nodes, isolated from the other half (for an odd
           count, the smaller half).
  Once the cut is in place, wait 1-5 s before healing. Once healing completes,
  wait 15-30 s before the next cut. --partition-duration and --partition-interval
  override these ranges; --network-interval also applies when no partition
  interval is specified. Both waits are drawn in whole seconds per cycle.

  TCP keeps the connection open through the drop and retransmits once it heals,
  so this rarely makes channels reestablish; the disconnect fault does. A node
  waiting more than 30 s for its peer's reply force-closes the channel (fnn's
  PEER_CHANNEL_RESPONSE_TIMEOUT), so a partition longer than that usually closes
  the channels across it (open question 1 on the chaos-test plan).

  Healing deletes the partitions' own rules only (`net`), so that it doesn't
  also end a chain partition that is in effect at the time."
  (:require [clojure.string :as str]
            [jepsen [control :as c]
                    [generator :as gen]
                    [nemesis :as n]
                    [net :as jnet]]
            [jepsen.generator.context :as ctx]
            [jepsen.net.proto :as p]
            [jepsen.nemesis.combined :as nc]
            [jepsen.fiber.schedule :as schedule])
  (:import (java.util ArrayList Collection Collections SplittableRandom)))

(def target-specs
  #{:one :half})

(def default-duration [1 5])
(def default-interval [15 30])

(defn shuffled
  "`coll` in an order drawn from `r` (Fisher-Yates)."
  [^SplittableRandom r coll]
  (let [a (ArrayList. ^Collection (vec coll))]
    (doseq [i (range (dec (count a)) 0 -1)]
      (Collections/swap a i (.nextInt r (int (inc i)))))
    (vec a)))

(defn grudge
  "The grudge of a partition of `nodes` by target spec `spec`, drawn from `r`."
  [r nodes spec]
  (case spec
    :one  (let [node (schedule/pick r nodes)]
            (n/complete-grudge [[node] (vec (remove #{node} nodes))]))
    :half (n/complete-grudge (map vec (n/bisect (shuffled r nodes))))))

(defn ops
  "Endless pairs of a partition and its healing. The grudge is computed here, not
  from a spec in the nemesis. Each partition's spec is drawn from `specs`
  (default [:one]); with only one spec nothing is drawn for it, so `(ops r nodes)`
  gives the ops it gave before target specs existed."
  ([r nodes]
   (ops r nodes [:one]))
  ([r nodes specs]
   (let [specs (vec specs)
         spec  (if (= 1 (count specs))
                 (constantly (first specs))
                 #(schedule/pick r specs))]
     (mapcat (fn [g]
               [{:type :info, :f :start-partition, :value g}
                {:type :info, :f :stop-partition, :value nil}])
             (repeatedly #(grudge r nodes (spec)))))))

(defn gaps
  "Alternate the active and healed waits (ns), drawn from independent streams."
  [streams opts]
  (interleave (schedule/ranged-gaps (schedule/stream streams :partition-duration)
                                    (or (:partition-duration opts) default-duration))
              (schedule/ranged-gaps (schedule/stream streams :partition-network-interval)
                                    (or (:partition-interval opts) (:network-interval opts)
                                        default-interval))))

(defrecord PartitionCycle [gaps ops due]
  gen/Generator
  (op [this _ ctx]
    (when-let [op (first ops)]
      (let [op (gen/fill-in-op op ctx)]
        [(if (identical? :pending op) op (update op :time max due)) this])))
  (update [this _ ctx event]
    ;; Both invocation and completion are :info. Only the completion frees the
    ;; nemesis; other faults sharing it must not advance this partition's clock.
    (if (and (= :nemesis (:process event))
             (= (:f event) (:f (first ops)))
             (ctx/thread-free? ctx :nemesis))
      (PartitionCycle. (rest gaps) (rest ops) (+ (:time event) (first gaps)))
      this)))

(def partition-rule
  "A rule of jepsen.net/iptables's partitions as `iptables -S INPUT` prints it:
  everything from one address dropped, with no match or comment that would
  mark another fault's rule (the chain partition's carry a comment)."
  #"-A INPUT -s (\S+) -j DROP")

(defn partition-sources
  "The source address of every partition rule in `s`, the output of
  `iptables -S INPUT`, once per rule."
  [s]
  (keep #(second (re-matches partition-rule (str/trim %))) (str/split-lines (or s ""))))

(defn heal-partitions!
  "Deletes the current node's partition rules, and no other rule."
  []
  (c/su (doseq [src (partition-sources (c/exec :iptables :-w :-S :INPUT))]
          (c/exec :iptables :-w :-D :INPUT :-s src :-j :DROP))))

(def net
  "The test's network (its :net): jepsen.net/iptables, except that healing
  deletes only the partitions' rules. iptables' own heal! flushes every rule on
  every node, so a partition's healing used to end any chain partition
  (jepsen.fiber.faults.chain-partition) in effect at the time, whenever both
  faults ran (C-16)."
  (let [base jnet/iptables]
    (reify
      p/Net
      (drop! [_ test src dest] (p/drop! base test src dest))
      (heal! [_ test] (c/on-nodes test (fn [_ _] (heal-partitions!))))
      (slow! [_ test] (p/slow! base test))
      (slow! [_ test opts] (p/slow! base test opts))
      (flaky! [_ test] (p/flaky! base test))
      (fast! [_ test] (p/fast! base test))
      (shape! [_ test nodes behavior] (p/shape! base test nodes behavior))

      p/PartitionAll
      (drop-all! [_ test grudge] (p/drop-all! base test grudge)))))

(defn parse-duration
  "\"120-300\" -> [120 300]; \"180\" -> [180 180]. Seconds."
  [s]
  (let [[lo hi] (str/split s #"-" 2)]
    [(parse-long lo) (parse-long (or hi lo))]))

(def fault
  {:faults  #{:partition}
   :stream  :partition
   :package nc/partition-package
   :ops     (fn [r opts] (ops r (vec (:nodes opts)) (:partition-targets opts [:one])))
   :gaps    gaps
   :pace    (fn [gaps ops] (->PartitionCycle gaps ops 0))
   :fired   {:partition #{:start-partition}}})
