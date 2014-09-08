(ns probe.core-test
  (:use [midje.sweet]
        [probe.core])
  (:require [clojure.core.async :refer [chan] :as async]
            [probe.sink :as sink]))

(def history1 (atom nil))

(defn history-sink1 [state]
  (clojure.tools.logging/trace "  History writer received: " state)
  (swap! history1 conj state))

(def history2 (atom nil))

(defn history-sink2 [state]
  (clojure.tools.logging/trace "  History writer received: " state)
  (reset! history2 state))

(facts "sinks"
  (rem-sink :history1)
  (fact "can be created"
    (:name (add-sink :history1 history-sink1))
    => :history1)
  (fact "can be retrieved"
    (:name (get-sink :history1))
    => :history1)
  (rem-sink :history1)
  (fact "can be removed"
    (:name (get-sink :history1))
    => nil?))


(facts "subscriptions"
  (unsubscribe-all)
  (rem-sink :history1)
  (fact "start with the empty state" (subscriptions) => #(= (count %) 0))
  (add-sink :history1 history-sink1)
  (subscribe #{:test} :history1)
  (fact "can be created" (get-subscription #{:test} :history1) => #(= (:sink %) :history1))
  (fact "can be retrieved" (:selector (get-subscription #{:test} :history1)) => #{:test})
  (fact "can be retrived by sink" (sink-subscriptions :history1) => #(= (count %) 1))
  (fact "ok to retrieve the empty set" (sink-subscriptions :fubar) => empty?)
  (unsubscribe #{:test} :history1)
  (fact "memoized lookup reset on unsubscribe"
        (get-subscription #{:test} :history1) => nil?))

(defn incrementer-fn [key]
  (fn [state]
    (clojure.tools.logging/trace "  Increment received state: " state)
    (if (and (map? state) (state key))
      (update-in state [key] inc)
      state)))

(facts "routing"
  (unsubscribe-all)
  (add-sink :history2 history-sink2 :force? true)
  (subscribe #{:test} :history2 :transform (incrementer-fn :count))
;  (add-sink :printer println)
;  (subscribe #{:test} :printer (incrementing-channel :count))
  (write-state {:tags #{:test} :foo :bar})
  (Thread/sleep 100)
  (facts "sends state to sink" @history2 => {:tags #{:test} :foo :bar})
  (write-state {:tags #{:test} :count 1})
  (facts "transforms are applied" @history2 => {:tags #{:test} :count 2})
  (write-state {:tags #{:test :foo :bar} :count 1})
  (facts "extra tags are ignored by selector"
         @history2 => {:tags #{:test :foo :bar} :count 2})
  (write-state {:tags #{:test} :count 1 :foo :bar})
  (facts "extra state is ok" @history2 => {:tags #{:test} :count 2 :foo :bar})
  (write-state {:tags #{:test} :count 1 :foo {:test 1 :testing 2}})
  (facts "nested structures are ok"
         @history2 => {:tags #{:test} :count 2 :foo {:test 1 :testing 2}})
  (add-sink :history2 history-sink2 :force? true)
  (write-state {:tags #{:test} :count 1})
  (facts "updating sinks re-establish existing subscriptions"
         @history2 => {:tags #{:test} :count 2})
  (reset! history2 nil)
  (subscribe #{:test} :history2)
  (write-state {:tags #{:test} :count 1})
  (facts "updating subscriptions re-establish connection"
         @history2 => {:tags #{:test} :count 1})
  (unsubscribe-all))

(defn match-state [& {:as match}]
  (fn [state]
    (= (dissoc state :line :ts :thread-id :ns :tags) match)))

(facts "probes to memory"
  (let [mem (sink/make-memory)]
   (rem-sink :memory)
   (add-sink :memory (sink/memory-sink mem))
   (subscribe #{:test} :memory)
   (probe #{:test} :test 1)
   (fact "can receive simple state"
     (dissoc (sink/last-value mem) :line :ts :thread-id)
     => {:tags #{:test :ns/probe :ns/probe.core-test} :test 1 :ns 'probe.core-test})
   (fact "can use matcher for state tests"
     (sink/last-value mem)
     => (match-state :test 1))
   (probe #{:test} :test 2)
   (Thread/sleep 100)
   (let [scan (sink/scan-memory mem)]
     (fact "accumulates state"
       (count scan) => 2)
     (fact "scans in ascending date order"
       (.getTime (:ts (second scan)))
       => (partial < (.getTime (:ts (first scan))))))))


(facts "probe expr"
  (let [mem (sink/make-memory)]
    (rem-sink :memory)
    (add-sink :memory (sink/memory-sink mem))
    (subscribe #{:probe/expr} :memory)
    (probe-expr (+ 1 2))
    (fact "returns expression"
      (sink/last-value mem)
      => (match-state :form '(do (+ 1 2)) :value 3))))

(def ^:dynamic my-bindings {:foo :bar})

(facts "dynamic bindings"
  (capture-bindings! nil)
  (unsubscribe-all)
  (reset! history1 nil)
  (add-sink :history1 history-sink1 :force? true)
  (subscribe #{:test} :history1 :transform (incrementer-fn :count))

  (fact "are not captured by default"
    (probe #{:test} :count 1)
    (Thread/sleep 20)
    (select-keys (first @history1) [:tags :count :bindings])
    => {:tags #{:test :ns/probe :ns/probe.core-test} :count 2})

  (fact "can be captured"
    (capture-bindings! `[probe.core-test/my-bindings])
    (probe #{:test} :count 1)
    (Thread/sleep 20)
    (select-keys (first @history1) [:tags :count :bindings])
    => {:tags #{:test :ns/probe :ns/probe.core-test} :count 2
        :bindings {'probe.core-test/my-bindings {:foo :bar}}})

  (fact "capture can be inhibited"
    (without-bindings (probe #{:test} :count 1))
    (Thread/sleep 20)
    (select-keys (first @history1) [:tags :count :bindings])
    => {:tags #{:test :ns/probe :ns/probe.core-test} :count 2})
  (capture-bindings! nil))

(facts "sink policies enforced"
  (fact "policy all enforced"
    (reset! history1 nil)
    (add-sink :history1  history-sink1 :policy :all :force? true)
    (subscribe #{:test1} :history1)
    (subscribe #{:test2} :history1)
    (probe [:test1 :test2])
    (Thread/sleep 20)
    (count @history1)
    => 2)

  (fact "policy unique enforced"
    (reset! history1 nil)
    (add-sink :history1  history-sink1 :policy :unique :force? true)
    (subscribe #{:test1} :history1 :transform #(assoc % :new "value"))
    (subscribe #{:test2} :history1)
    (subscribe #{:test3} :history1)
    (probe [:test1 :test2 :test3])
    (Thread/sleep 20)
    (count @history1)
    => 2)

  (fact "policy first enforced"
    (reset! history1 nil)
    (add-sink :history1  history-sink1 :policy :first :force? true)
    (subscribe #{:test1} :history1)
    (subscribe #{:test2} :history1)
    (probe [:test1 :test2])
    (Thread/sleep 20)
    (count @history1)
    => 1)

  (fact "policy none enforced"
    (reset! history1 nil)
    (add-sink :history1  history-sink1 :policy :none :force? true)
    (subscribe #{:test1} :history1)
    (subscribe #{:test2} :history1)
    (probe [:test1 :test2])
    (Thread/sleep 20)
    (count @history1)
    => 0)

  (fact "policy none leaves other sinks unaffected"
    (let [mem (sink/make-memory)]
      (rem-sink :memory)
      (add-sink :memory (sink/memory-sink mem))
      (reset! history1 nil)
      (add-sink :history1  history-sink1 :policy :none :force? true)
      (subscribe #{:test1} :history1)
      (subscribe #{:test2} :memory)
      (probe [:test1 :test2])
      (Thread/sleep 20)
      (count @mem)
      => 1))
  (fact "Proper sink shut off"
    (count @history1)
    => 0))


;;TODO come up with a better way to test this
(facts "swap-sink-policy! works as intended"
  (fact "policy defaults to all"
    (reset! history1 nil)
    (add-sink :history1 history-sink1 :force? true)
    (subscribe #{:test1} :history1)
    (subscribe #{:test2} :history1)
    (probe [:test1 :test2])
    (Thread/sleep 20)
    (count @history1)
    => 2)

  (fact "policy swapped"
    (swap-sink-policy! :history1 :unique)
    (probe [:test1 :test2])
    (Thread/sleep 20)
    (count @history1)
    => 3))

(facts "Test Transform Helpers"
  (fact "mk-sample-transform works as expected"
    (reset! history1 nil)
    (add-sink :history1 history-sink1 :force? true)
    (subscribe #{:test} :history1 :transform (mk-sample-transform 3))
    (probe [:test])
    (probe [:test])
    (probe [:test])
    (Thread/sleep 20)
    (count @history1)
    => 1)

  (fact "mk-filter-transform works as expected"
    (reset! history1 nil)
    (add-sink :history1 history-sink1 :force? true)
    (subscribe #{:test}
               :history1
               :transform (mk-filter-transform #(= (:value %) 42)))
    (probe [:test])
    (probe [:test] :value 42)
    (Thread/sleep 20)
    (:value (first @history1))
    => 42)

  (fact "mk-filter-transform filters"
    (count @history1)
    => 1)

  (fact "mk-remove-transform works as expected"
    (reset! history1 nil)
    (add-sink :history1 history-sink1 :force? true)
    (subscribe #{:test}
               :history1
               :transform (mk-remove-transform #(not= (:value %) 42)))
    (probe [:test])
    (probe [:test] :value 42)
    (Thread/sleep 20)
    (:value (first @history1))
    => 42)

  (fact "mk-remove-transform removes"
    (count @history1)
    => 1))



(future-facts "probe state")
(future-facts "probe fns")
(future-facts "probe namespace")
