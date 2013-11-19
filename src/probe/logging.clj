(ns probe.logging
  "Support a standard logging API, using the underlying
   Logger state to enable/disable log statements but routing
   the raw data to an internal probe."
  (:use [probe.core])
  (:require [clojure.set :as set]))
  
;; Tags

(def log-tag-seq
  [:error :warn :info :debug :trace])

(defn expand-tags [tags]
  (let [logs (set/intersection (set log-tag-seq) (set tags))]
    (loop [logtags log-tag-seq]
      (if (logs (first logtags))
        (concat (rest logtags) tags)
        (recur (rest logtags))))))

;; Log entry point

(defn- log-expr [form level keyvals]
  ;; Pull out :exception, otherwise preserve order
  {:pre [(keyword? level)]}
  (let [amap (apply array-map keyvals)
        exception' (:exception amap)
        tags' (set (concat (expand-tags [level]) (:tags amap)))
        keyvals' (mapcat identity (remove #(#{:exception :tags} (first %))
                                          (partition 2 keyvals)))]
    `(when (subscribers? ~tags')
       ~(if exception'
          `(probe ~tags'
                    :exception ~(with-meta exception'
                                  {:tag 'java.lang.Throwable})
                    ~@keyvals')
          `(probe ~tags'
                    ~@keyvals')))))

(defmacro trace [& keyvals] (log-expr &form :trace keyvals))

(defmacro debug [& keyvals] (log-expr &form :debug keyvals))

(defmacro info [& keyvals] (log-expr &form :info keyvals))

(defmacro warn [& keyvals] (log-expr &form :warn keyvals))

(defmacro error [& keyvals] (log-expr &form :error keyvals))
