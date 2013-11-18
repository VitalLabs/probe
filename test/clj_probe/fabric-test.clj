(ns probe.fabric
  (:use [midje.sweet])
  (:require [clojure.core.async :as async]))

(def history1 (atom nil))

(defn history-sink1 [state]
  (clojure.tools.logging/trace "  History writer received: " state)
  (swap! history1 conj state))

(facts "sinks"
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

(defn incrementer-fn [key]
  (fn [state]
    (clojure.tools.logging/trace "  Increment received state: " state)
    (if (and (map? state) (state key))
      (update-in state [key] inc)
      state)))

(defn incrementing-channel [key]
  (async/map> (incrementer-fn key) (chan)))

(facts "subscriptions"
  (unsubscribe-all)
  (fact "start with the empty state" @subscriptions => #(= (count %) 0))
  (add-sink :history1 history-sink1)
  (subscribe #{:test} (incrementing-channel :count) :history1)
  (fact "can be created" (@subscriptions [#{:test} :history1]) => #(= (:sink %) :history1))
  (fact "can be retrieved" (:selector (get-subscription #{:test} :history1)) => #{:test})
  (fact "can be retrived by sink" (sink-subscriptions :history1) => #(= (count %) 1))
  (fact "ok to retrieve the empty set" (sink-subscriptions :fubar) => empty?)
  (unsubscribe-all))

(facts "routing"
  (add-sink :history1 history-sink1)
  (add-sink :printer println)
  (subscribe #{:test} (incrementing-channel :count) :history1)
  (subscribe #{:test} (incrementing-channel :count) :printer)
  (write-probe {:tags #{:test} :foo :bar})
  (facts "sends state to sink" (first @history1) => {:tags #{:test} :foo :bar})
  (unsubscribe-all))
