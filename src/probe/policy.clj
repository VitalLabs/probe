(ns probe.policy)

(defn random-sample [state freq]
  {:pre [(float? freq)]}
  (when (<= (rand) freq)
    state))

(defn select-fn
  [state fname]
  {:pre [(symbol? fname)]}
  (when ((set fname) (:fname state))
    state))

(defn system-ctx [state]
  (assoc state
    :host       (.getCanonicalHostName (java.net.InetAddress/getLocalHost))
    :process-id 0)) ;; TODO

(defn string-exception
  "TODO: Convert exception object to a string w/ stacktrace"
  [state]
  (if-let [except (:exception state)]
    (assoc state :exception (str except))
    state))

(defn remove-args [state key & ks]
  (dissoc state :args))

  
  
