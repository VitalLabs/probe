(ns probe.fabric
  "Build and inspect a probe topology based on core.async channels
   and 'go' routers"
  (:require [clojure.core.async :refer [go go-loop chan >! >!! <! <!!] :as async]
            [clojure.set :as set]))

;; Sinks (target for transformed state; exposes a mix)
;; Subscription (conjunction of origin tags filters probes)
;; Router (submits probe state into subscription channels)

;;
;; Exception handling
;;

(def error-channel (chan))

(def error-router
  (go
   (while true
     (when-let [{:keys [state exception]} (<! error-channel)]
       (clojure.tools.logging/error state exception)
       (recur)))))

;;
;; Sinks
;;

(def sinks (atom {}))

(defn sink-processor [f c]
  (go-loop []
    (when-let [state (<! c)]
      (try
        (f state)
        (catch java.lang.Throwable t
          (>! error-channel {:state ~state :exception t})))
      (recur))))

(defn add-sink
  "Create a named sink function for probe state"
  [name f]
  {:pre [(keyword? name)]}
  (let [c (chan)
        mix (async/mix c)
        out (sink-processor f c)
        sink {:name name
              :function f
              :in c
              :mix mix
              :out out}]
    (swap! sinks assoc name sink)
    sink))

(defn get-sink [name]
  (@sinks name))

(declare unsubscribe sink-subscriptions)

(defn rem-sink
  "Remove a sink and all subscriptions"
  ([name]
     (let [{:keys [mix out in]} (get-sink name)]
       (assert mix out)
       (doall
        (map (fn [sub] (unsubscribe (:name sub) (:sink sub)))
             (sink-subscriptions name)))
       (async/close! in)
       (assert (nil? (<!! out)))
       (swap! sinks dissoc name))))
       
;;
;; Subscriptions
;;
       
(def subscriptions (atom {}))

(defn subscribe
  ([selector channel sink-name]
     {:pre [(set? selector) (keyword? sink-name)]}
     (let [{:keys [mix] :as sink} (get-sink sink-name)
           subscription {:selector (set selector)
                         :channel channel
                         :sink sink-name
                         :name selector}]
       (assert (and sink mix))
       (async/admix mix channel)
       (swap! subscriptions assoc [selector sink-name] subscription)
       subscription)))

(defn get-subscription [selector sink-name]
  {:pre [(coll? selector) (keyword? sink-name)]}
  (@subscriptions [(set selector) sink-name]))

(defn subscribers [tags]
  (let [selector (set tags)]
    (filter #(set/subset? selector (:selector %))
            (vals @subscriptions))))

(defn subscribers? [tags]
  (not (empty? (subscribers tags))))

(defn sink-subscriptions [name]
  (filter #(= (:sink %) name) (vals @subscriptions)))

(defn unsubscribe
  ([selector sink]
     (let [{:keys [channel sink]} (get-subscription selector sink)
           {:keys [mix]}          (get-sink sink)]
       (async/unmix mix channel)
       (async/close! channel)
       (swap! subscriptions dissoc [selector sink]))))

(defn unsubscribe-all []
  (doall
   (map (fn [[sel sink]]
          (unsubscribe sel sink))
        (keys @subscriptions)))
  {})

;;
;; Router
;;
               
(def input (chan))

(defn write-probe
  "External API to submit probe state to the fabric"
  [state]
  (>!! input state))

(def router-handler 
  (go-loop []
    (let [state (<! input)]
      (clojure.tools.logging/trace "Received input: " state)
      (when-let [tags (and (map? state) (:tags state))]
        (when (coll? tags)
          (doseq [sub (subscribers tags)]
            (try
              (clojure.tools.logging/trace "  Writing channel: " sub)
              (>! (:channel sub) state)
              (catch java.lang.Throwable t
                (>! error-channel {:state ~state :exception t}))))))
      (recur))))



;;
;; Transforming channel utilities for creating subscriptions
;;

