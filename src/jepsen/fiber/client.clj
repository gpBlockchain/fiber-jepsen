(ns jepsen.fiber.client
  "Sends keysend payments from the client's node to another node, and performs
  the final read of every node's channels and every payment's status."
  (:require [clojure.tools.logging :refer [warn]]
            [jepsen [client :as client]
                    [util :refer [await-fn]]]
            [jepsen.fiber [rpc :as rpc]
                          [topology :as topology]])
  (:import (java.net ConnectException)))

(def terminal-statuses #{"Success" "Failed"})

(def payment-timeout-ms
  "How long a client waits for a payment to finish before recording it as :info."
  15000)

(defn await-payment
  "Polls get_payment until the payment is Success or Failed, or the timeout
  passes. Returns the last result seen, which may be nil."
  [node hash]
  (let [deadline (+ (System/currentTimeMillis) payment-timeout-ms)]
    (loop [last-seen nil]
      (let [res (try (rpc/call node "get_payment" {:payment_hash hash})
                     (catch Exception _ last-seen))]
        (if (or (terminal-statuses (:status res))
                (< deadline (System/currentTimeMillis)))
          res
          (do (Thread/sleep 200)
              (recur res)))))))

(def invoice-currency
  "Currency of invoices on the devnet (Fibd). Override with --invoice-currency."
  "Fibd")

(defn create-invoice!
  "Asks the receiver for an invoice. Throws (as a rejected payment) if the
  receiver is down, so the op is recorded as :fail with nothing sent."
  [test to amount]
  (try
    (rpc/call to "new_invoice"
              {:amount      (rpc/int->hex amount)
               :currency    (:invoice-currency test invoice-currency)
               :description "jepsen"
               :expiry      (rpc/int->hex 3600)
               :hash_algorithm "sha256"})
    (catch ConnectException _
      (throw (ex-info "receiver down" {:type :rpc-error})))))

(defn pay!
  [test node op]
  (let [to     (let [t (get-in op [:value :to])]
                 (cond
                   (and t (not= t node)) t
                   ; Invoice payments go end to end where possible, so they are
                   ; forwarded by the middle node (n1 -> n3 and n3 -> n1).
                   (= :invoice (get-in op [:value :mode]))
                   (let [ns (vec (:nodes test))]
                     (cond (= node (first ns)) (peek ns)
                           (= node (peek ns))  (first ns)
                           :else (rand-nth (remove #{node} ns))))
                   :else (rand-nth (remove #{node} (:nodes test)))))
        amount (get-in op [:value :amount])
        value  (assoc (:value op) :from node :to to)
        mode   (get-in op [:value :mode] :keysend)
        res    (try
                 (if (= :invoice mode)
                   (let [inv (create-invoice! test to amount)]
                     (rpc/call node "send_payment" {:invoice (:invoice_address inv)}))
                   (rpc/call node "send_payment"
                             {:target_pubkey (get @(:pubkeys test) to)
                              :amount        (rpc/int->hex amount)
                              :keysend       true}))
                 ; Nothing reached the node, so the payment definitely wasn't created.
                 (catch ConnectException _ ::node-down)
                 (catch clojure.lang.ExceptionInfo e
                   (if (= :rpc-error (:type (ex-data e)))
                     {::rejected (ex-message e)}
                     (throw e))))]
    (cond
      (= ::node-down res)
      (assoc op :type :fail, :value value, :error :node-down)

      (::rejected res)
      (assoc op :type :fail, :value value, :error (::rejected res))

      :else
      (let [hash  (:payment_hash res)
            value (assoc value :hash hash)]
        (swap! (:payments test) conj {:node node, :hash hash, :mode mode, :to to})
        (let [final (await-payment node hash)]
          (case (:status final)
            "Success" (assoc op :type :ok, :value (assoc value :fee (rpc/hex->int (:fee final))))
            "Failed"  (assoc op :type :fail, :value value, :error (:failed_error final))
            (assoc op :type :info, :value value, :error [:not-finished (:status final)])))))))

(defn probe!
  "After the network has healed, sends one small keysend each way over every
  channel. A healthy network completes them; a wedged channel leaves them stuck."
  [test]
  (vec (for [[a b]     (topology/edges (:nodes test))
             [from to] [[a b] [b a]]
             :let      [amount 1000000]]
         (let [res (try (rpc/call from "send_payment"
                                  {:target_pubkey (get @(:pubkeys test) to)
                                   :amount        (rpc/int->hex amount)
                                   :keysend       true})
                        (catch Exception e {::rejected (ex-message e)}))]
           (if-let [err (::rejected res)]
             {:from from, :to to, :amount amount, :status "Rejected", :error err}
             (let [final (await-payment from (:payment_hash res))]
               {:from   from
                :to     to
                :amount amount
                :hash   (:payment_hash res)
                :status (:status final)
                :error  (:failed_error final)}))))))

(defn read-node
  "Every channel of one node. Retries while the node recovers from faults."
  [node]
  (await-fn #(:channels (rpc/call node "list_channels" {:include_closed true}))
            {:log-message (str "Waiting to read channels from " node)
             :timeout     120000}))

(defn read-all
  [test]
  {:channels (into {} (for [n (:nodes test)] [n (read-node n)]))
   :payments (vec (for [{:keys [node hash mode to]} @(:payments test)]
                    (let [res (try (rpc/call node "get_payment" {:payment_hash hash})
                                   (catch Exception e
                                     (warn e "Couldn't read payment" hash "from" node)
                                     nil))]
                      (cond-> {:node   node
                               :hash   hash
                               :status (:status res)
                               :error  (:failed_error res)}
                        (= :invoice mode)
                        (assoc :to to
                               :invoice-status
                               (try (:status (rpc/call to "get_invoice" {:payment_hash hash}))
                                    (catch Exception e
                                      (warn e "Couldn't read invoice" hash "from" to)
                                      nil)))))))})

(defrecord Client [node]
  client/Client
  (open! [this _test node]
    (assoc this :node node))

  (setup! [_ _test])

  (invoke! [_ test op]
    (case (:f op)
      :pay   (pay! test node op)
      :probe (assoc op :type :ok, :value (probe! test))
      :read  (assoc op :type :ok, :value (read-all test))))

  (teardown! [_ _test])

  (close! [_ _test]))

(defn client
  []
  (Client. nil))
