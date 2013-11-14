(ns probe.logging
  "Support a standard logging API, using the underlying
   Logger state to enable/disable log statements but routing
   the raw data to an internal probe."
  (:require [probe.core :as p])
  (:import [java.util.logging LogManager Logger Level]
           [org.slf4j LoggerFactory]))
  

(defn- log-expr [form level keyvals]
  ;; Pull out :exception, otherwise preserve order
  (let [exception' (:exception (apply array-map keyvals))
        keyvals' (mapcat identity (remove #(= :exception (first %))
                                          (partition 2 keyvals)))
        logger' (gensym "logger")  ; for nested syntax-quote
        string' (gensym "string")
        enabled-method' (symbol (str ".is"
                                     (clojure.string/capitalize (name level))
                                     "Enabled"))
        log-method' (symbol (str "." (name level)))]
    `(let [~logger' (LoggerFactory/getLogger ~(name (ns-name *ns*)))]
       (when (~enabled-method' ~logger')
         ~(if exception'
            `(p/probe [~level]
                       :exception
                       ~(with-meta exception'
                          {:tag 'java.lang.Throwable})
                       ~@keyvals')
            `(p/probe [~level]
                       ~@keyvals'))))))

(defmacro trace [& keyvals] (log-expr &form :trace keyvals))

(defmacro debug [& keyvals] (log-expr &form :debug keyvals))

(defmacro info [& keyvals] (log-expr &form :info keyvals))

(defmacro warn [& keyvals] (log-expr &form :warn keyvals))

(defmacro error [& keyvals] (log-expr &form :error keyvals))
