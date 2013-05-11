(ns clj-probe.policy)

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

  
