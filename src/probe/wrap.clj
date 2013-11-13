(ns probe.wrap)

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

(defn- rewrapper
  "When the Var is updated, rewrap the function unless
   you are setting a wrapped function again, then remove
   yourself.  Makes assumptions about order of root setting
   and notification, and may interact badly with other var
   watchers"
  [wrapper orig]
  (fn [key v oldf f]
    (remove-watch v :rewrap)
    (if (= oldf f) ;; If setting a wrapped function again, clear
      (alter-var-root v (fn [oldf] orig))
      (do (alter-var-root v (fn [oldf] (wrapper v f)))
          (add-watch v :rewrap (rewrapper wrapper f))))))

(defn wrap-var-fn
  [fn-sym wrapper]
  (let [v (as-var fn-sym)
        f (var-get v)]
    (assert (fn? f))
    (alter-var-root v (fn [old] (wrapper v f)))
    (add-watch v :rewrap (rewrapper wrapper f))))

(defn unwrap-var-fn
  "Revert to original (latest) function definition
   and remove tracking metadata. If unwrapped, ignores."
  [fn-sym]
  (let [v (as-var fn-sym)
        f (var-get v)]
    (when (and (fn? f) (find (.getWatches v) :rewrap))
      (alter-var-root v (fn [old] old)))))
      
  
