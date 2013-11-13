(ns probe.sink
  (require [clojure.string :as str]))

(defn- state->string [state]
  (pr-str state))

(defn- most-specific-tag [tags]
  {:pre [(set? tags)]}
  (or (first (filter tags [:trace :debug :info :warn :error]))
      :trace))

(def log-df
  (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm.SSS")
    (.setTimeZone (java.util.TimeZone/getTimeZone "UTC"))))

(defn- human-date [ts]
  (str (.format log-df (java.util.Date. (long ts)))))

(defn- state->log-string [state]
  (let [new (dissoc state :ts :ns :line :tags)]
    (print-str (human-date (:ts state))
                 (str (:ns state) ":" (:line state))
                 new)))

;; CONSOLE SINK

(defn console-raw
  "Print state vectors to the console"
  [state]
  (println (state->string state))
  state)

(defn console-log
  [state]
  (println (state->log-string state))
  state)

;; LOGGER SINK

(defn log [state]
  "Use slf4j to log string copies of state"
  (let [{tags :tags} state
        tag (most-specific-tag tags)
        enabled (symbol (str "is" (str/capitalize (name tag)) "Enabled"))
        method (symbol (name tag))
        logger "nil"
;;        logger (org.slf4j.LoggerFactory/getLogger (name (ns-name (:ns state))))
        sstate (dissoc state :ns :tags :exception)]
    (when (. logger enabled-method)
      (let [string (binding [*print-length* 100] (state->string sstate))]
        (if-let [exception (:exception state)]
          (. logger method string exception)
          (. logger method string))))))


;; ## MEMORY SINK

(defn make-memory []
  (atom clojure.lang.PersistentQueue/EMPTY))

(def global (atom (make-memory)))

(defn reset-global-memory []
  (reset! global (make-memory)))

(defn memory
  "Save state to the provided atom or the global atom (for all state)"
  ([state]
     (memory state global))
  ([state ref]
     (let [atom (if (atom ref) ref (var-get (resolve ref)))]
       (swap! atom conj state)
       state)))

(defn fixed-memory
  ([state max]
     (fixed-memory state global max))
  ([state ref max]
     (let [atom (if (= (class ref) clojure.lang.Atom)
                  ref (var-get (resolve ref)))]
       (assert (= (class atom) clojure.lang.Atom))
       (if (>= (count @atom) max)
         (swap! atom (fn [coll] (conj (pop coll) state)))
         (swap! atom conj state)))))
     
