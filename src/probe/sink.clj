(ns probe.sink
  "A set of default sinks"
  (:require [clojure.string :as str])
  (:import [java.util.logging LogManager Logger Level]
           [org.slf4j LoggerFactory]))

;; Utilities
;; ----------------------------------

(defn- state->string [state]
  (pr-str state))

(def ^:private log-df
  (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm.SSS")
    (.setTimeZone (java.util.TimeZone/getTimeZone "UTC"))))

(defn- human-date [ts]
  (str (.format log-df (java.util.Date. (long ts)))))

(defn- state->log-string [state]
  (let [new (dissoc state :ts :ns :line :tags)]
    (print-str (human-date (:ts state))
                 (str (:ns state) ":" (:line state))
                 new)))

;; CONSOLE SINKS
;; ----------------------------------

(defn console-raw
  "Print raw state to the console"
  [state]
  (println (state->string state)))

(defn console-pretty
  "Print raw state to the console that looks pretty."
  [state]
  (clojure.pprint/pprint state))

(defn console-log
  "A console sink that produces log-style formatted string"
  [state]
  (println (state->log-string state)))

;; LOGGER SINK
;; -----------------------------------

(defn state-level [state]
  (let [selector (set (:tags state))]
    (loop [levels [:error :warn :info :debug :trace]]
      (cond (empty? levels) :trace
            (selector (first levels)) (first levels)
            :default (recur (rest levels))))))

(defn- enabled? ^Boolean [^clojure.lang.Keyword level ^Logger logger]
  (case level
    :error (.isErrorEnabled logger)
    :warn (.isWarnEnabled logger)
    :info (.isInfoEnabled logger)
    :debug (.isDebugEnabled logger)
    :trace (.isTraceEnabled logger)
    (throw (ex-info "Invalid level derived in log sink"
                    {:level level :logger logger}))))

(defn- do-log
  ([^clojure.lang.Keyword level logger ^String string]
     (case level
       :error (.error logger string)
       :warn (.warn logger string)
       :info (.info logger string)
       :debug (.debug logger string)
       :trace (.trace logger string)))
  ([^clojure.lang.Keyword level ^Logger logger ^String string exception]
     (case level
       :error (.error logger string exception)
       :warn (.warn logger string exception)
       :info (.info logger string exception)
       :debug (.debug logger string exception)
       :trace (.trace logger string exception))))


(defn log-sink
  "Use slf4j to log stringified state"
  ([]
     (log-sink nil))
  ([at-level]
     (fn [state]
       {:pre [(map? state)]}
       (let [level (or at-level (state-level state) :trace)
             ns (or (:ns state) 'probe.sink)
             logger (LoggerFactory/getLogger (name ns))
             sstate (dissoc state :ns :exception)]
         (when (enabled? level logger)
           (let [string (binding [*print-length* 100] (state->string sstate))]
             (if-let [exception (:exception state)]
               (do-log level logger string exception)
               (do-log level logger string))))))))


;; MEMORY SINK
;; -----------------------------------

(defn make-memory [] (atom nil))
(def global-memory (make-memory))
(defn last-value [memory] (first @memory))
(defn scan-memory
  ([memory]
     (reverse @memory))
  ([memory start]
     (filter #(> (.getTime (:date %)) (.getTime start))
             (scan-memory memory)))
  ([memory start end]
     (filter #(and (>= (.getTime (:date %)) (.getTime start))
                   (< (.getTime (:date %)) (.getTime end)))
             (scan-memory memory))))
(defn reset-memory [memory] (reset! ref nil))

(defn memory-sink
  "Save state to the provided queue, use global queue for default"
  ([]
     (memory-sink global-memory nil))
  ([ref]
     (memory-sink ref nil))
  ([ref max]
     (let [atom (if (= (class ref) clojure.lang.Atom) ref (var-get (resolve ref)))]
       (assert (= (class atom) clojure.lang.Atom))
       (fn [state]
         (if (and max (>= (count @atom) max))
           (swap! atom (fn [coll] (conj (pop coll) state)))
           (swap! atom conj state))))))



