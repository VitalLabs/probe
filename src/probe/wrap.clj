(ns probe.wrap
  "Utility library for instrumenting vars by wrapping them")

;;
;; Simple Var function wrapper
;; -----------------------------------------

;; - Can only wrap a function once (no chaining, etc)
;; - A wrapper takes a Var and a Fn object as arguments
;;   and returns a replacement for the original function
;; - NOTE: Potential race conditions in maintaining metadata
;;   with wrapped function state.  Rapid redefinition
;;   or compilation/loading may trigger.

(defn as-var [ref]
  (if (var? ref) ref (resolve ref)))

(defn fq-sym
  "Fully-qualified symbol."
  [sym]
  (if-let [ns (namespace sym)]
    sym
    (symbol (str *ns*) (name sym))))

(def probed-originals (atom {}))

(defn wrap-var-fn
  [fn-sym wrapper]
  (let [v (as-var fn-sym)
        f (var-get v)
        fqs (fq-sym fn-sym)]
    (assert (fn? f))
    (if-let [orig (get @probed-originals fqs)]
      ;; re-wrap the original definition.
      (alter-var-root v (fn [_] (wrapper v orig)))
      ;; Save the original definition, change the root.
      (do
        (swap! probed-originals assoc fqs f)
        (alter-var-root v (fn [_] (wrapper v f)))))))

(defn unwrap-var-fn
  "Revert to original (latest) function definition
   and remove tracking metadata. If unwrapped, ignores."
  [fn-sym]
  (let [v (as-var fn-sym)
        f (var-get v)
        fqs (fq-sym fn-sym)]
    (when (and (fn? f) (get @probed-originals fqs))
      (alter-var-root v (fn [_] (get @probed-originals fqs))))))


