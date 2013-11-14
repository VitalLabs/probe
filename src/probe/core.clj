(ns probe.core
  (:refer-clojure :exclude (==))
  (:require [probe.wrap :as w]
            [probe.config :as cfg]))

;; Policies
;; -----------------------

;; Hierarchical catalog of named policies
;; {:name [fn-fnsym-or-name fn-fnsym-or-name [:split ...]]}

(defonce policies (atom {:default [identity]}))

(defn set-policy!
  "Add a named policy pipeline to the policy catalog"
  [name policy]
  (swap! policies assoc name policy))

(defn clear-policy!
  "Remove a policy from the catalog"
  [name]
  (swap! policies dissoc name))
             
(defn lookup-policy
  [name]
  (get @policies name))


;; Probe Configuration
;; ------------------------

(defn set-config! [ns tags policy]
  {:pre [(sequential? tags)]}
  (cfg/set-config! ns tags policy))

(defn clear-config! [ns tags]
  {:pre [(sequential? tags)]}
  (cfg/remove-config! ns tags))

(defn active-policy [ns tags]
  (or (cfg/active-policy ns tags) :default))

;; Policy execution
;; --------------------------

(defn- policy-step [state step]
  (when-not (nil? state)
    (cond
     (sequential? step) (apply (resolve (first step)) state (rest step))
     (keyword? step)    (reduce policy-step state (lookup-policy step))
     (symbol? step)     ((resolve step) state)
     (fn? step)         (step state))))

(comment
  (defn- policy-handler
    "TODO: Convert pipeline to middleware handler?"
    [policy]
    (fn [state]
      (if (empty? policy)
        state
        (policy-step state (first policy) (policy-handler (rest policy)))))))

(defn apply-policy [agent policy state]
  (when (:active agent)
    (try
      (doall (reduce policy-step state (cfg/as-sequence policy)))
      (catch java.lang.Throwable e
        (println "Apply policy error for policy: " policy)
        (println state)
        (println e))))
  agent)

;;
;; Direct probes
;; -----------------------------------------

(def probe-agent (agent {:active true}))
  
(defn probe*
  "Probe the provided state in the current namespace using tags for dispatch"
  ([ns tags state]
     (when-let [policy (active-policy ns (set tags))]
       (let [state (assoc state
                     :ns (ns-name ns)
                     :tags tags
                     :thread-id  (.getId (Thread/currentThread))
                     :date (java.util.Date.))]
         (send-off probe-agent apply-policy policy state)
         nil)))
  ([tags state]
     (probe* (ns-name *ns*) tags state)))

(defmacro probe
  "Take a single map as first keyvals element, or an upacked
   list of key and value pairs."
  [tags & keyvals]
  (assert (sequential? tags))
  `(probe* (quote ~(ns-name *ns*))
           ~(cfg/as-tags tags)
           (assoc ~(if (= (count keyvals) 1)
                     (first keyvals)
                     (apply array-map keyvals))
             :line ~(:line (meta &form)))))

;;
;; State probes
;; -----------------------------------------

(defn- state-watcher [transform-fn]
  {:pre [(fn? transform-fn)]}
  (fn [_ _ _ new]
    (probe* [:state] (transform-fn new))))

(defn- state? [ref]
  (let [type (type ref)]
    (or (= clojure.lang.Var type)    
        (= clojure.lang.Ref type)
        (= clojure.lang.Atom type)
        (= clojure.lang.Agent type))))

(defn probe-state!
  "Add a probe function to a state element or a symbol
   that resolves to a reference."
  [transform-fn ref]
  {:pre [(fn? transform-fn) (state? ref)]}
  (add-watch
   (if (symbol? ref)
     (if-let [actual-ref (var-get (resolve ref))]
       actual-ref
       (throw (ex-info "Symbol is not " {:symbol ref})))
     ref)
   ::probe (state-watcher transform-fn)))

(defn unprobe-state!
  "Remove the probe function from the provided reference"
  [ref]
  (remove-watch ref ::probe))

    
;;
;; Function probes
;; -----------------------------------------

(defn- probe-fn-wrapper
  "Wrap f of var v to insert pre,post, and exception wrapping probes that
   match tags :entry-fn, :exit-fn, and :except-fn."
  [v f]
  (let [m (meta v)
        static (array-map :line (:line m) :fname (:name m))
        except-fn #{:except-fn}
        enter-tags #{:enter-fn}
        exit-tags #{:exit-fn}]
    (fn [& args]
      (do (probe* enter-tags (assoc static :fn :enter :args args))
          (let [result (try (apply f args)
                            (catch java.lang.Throwable e
                              (probe* except-fn (assoc static :fn :except :exception e :args args))
                              (throw e)))]
            (probe* exit-tags (assoc static :fn :exit :args args :return result))
            result)))))

;; Function probe API
(defn probe-fn! [fsym]
  {:pre [(symbol? fsym)]}
  (w/wrap-var-fn fsym probe-fn-wrapper))

(defn unprobe-fn! [fsym]
  {:pre [(symbol? fsym)]}
  (w/unwrap-var-fn fsym))

;; Namespace probe API
(defn- probe-var-fns
  "Probe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get w/as-var))
        (map probe-fn!))))

(defn- unprobe-var-fns
  "Unprobe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get w/as-var))
        (map probe-fn!))))

(defn probe-ns! [ns]
  (probe-var-fns (keys (ns-publics ns))))
(defn unprobe-ns! [ns]
  (unprobe-var-fns (keys (ns-publics ns))))

(defn probe-ns-all! [ns]
  (probe-var-fns (keys (ns-interns ns))))
(defn unprobe-ns-all! [ns]
  (unprobe-var-fns (keys (ns-interns ns))))

