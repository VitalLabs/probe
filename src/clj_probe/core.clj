(ns clj-probe.core
  (:require [clj-probe.wrap :as w]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn- as-sequence [value]
  (if (sequential? value) value
      [value]))

(defn- as-tags [tags]
  (if (set? tags) tags
      (set (as-sequence tags))))

;; Policies
;; -----------------------

;; Hierarchical catalog of named probe policies
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

;; Current probe configuration
;; {ns {tags1 policy2 tags2 policy2 ...}}

(defonce config (atom {}))
(def config-cache (atom {}))

(defn- set-config-cache!
  "Keep explicit matches in a cache for fast lookup"
  [ns tags policy]
  {:pre [(set? tags)]}
  (swap! config-cache assoc-in [ns tags] policy)
  policy)

(defn- clear-config-cache!
  "When updating the configuration, we must clear the cache"
  []
  (reset! config-cache {}))

(defn- cached-config [ns tags]
  ""
  {:pre [(set? tags)]}
  (get-in @config-cache [ns tags]))

(defn- unambiguous-tagset?
  "No two tag sets should overlap at a given NS level"
  [ns tags]
  (empty? (apply set/intersection tags (keys (get @config ns)))))

(defn- import-config
  ""
  [cfg]
  cfg)

(defn set-config!
  "Set configuration state; all at once or one at a time"
  ([cfg]
     (clear-config-cache!)
     (reset! config (import-config cfg))
     true)
  ([ns tags policy]
     (clear-config-cache!)
     (swap! config assoc-in [ns (as-tags tags)] policy)
     true))

(defn remove-config!
  ([ns tags]
     (swap! config update-in [ns] dissoc (as-tags tags))))

;; Match probe to configuration
;; -----------------------------------

(def log-hierarchy
  {:error nil
   :warn #{:error}
   :info #{:warn :error}
   :debug #{:info :warn :error}
   :trace #{:debug :info :warn :error}
   :exit-fn #{:fn}
   :except-fn #{:fn}
   :enter-fn #{:fn}})

(defn- expand-tags 
  "Implement traditional log hierarchy for backwards compatability"
  [tags]
  (apply set/union tags (map log-hierarchy tags)))

(defn- match-tags
  "Do the probe tags match these policy mtags?"
  [tags [mtags policy]]
  {:pre [(every? keyword? tags) (every? keyword? mtags)]}
  (when (not (empty? (set/intersection mtags (expand-tags tags))))
    policy))

(defn- matching-policy
  "Do any of the tagsets in matches"
  [matches tags]
  (second (first (filter (partial match-tags tags) matches))))

(defn- parent-ns
  "Return the next level of the namespace hierarchy"
  [ns]
  {:pre [(symbol? ns)]}
  (let [path (str/split (name ns) #"\.")]
    (if (> (count path) 1)      (symbol (str/join "." (take (- (count path) 1) path)))
      nil)))

(defn active-policy 
  "Given a namespace and tags for a probe point, find most specific
   matching ns with tag entries that match the probe tags, otherwise
   search up.  Multiple tag entries at the same level that both match
   tags will be selected arbitrarily."
  ;; Return policy if current level matches probe level
  ([^:clojure.lang.Symbol ns ^:clojure.lang.Set tags]
     (active-policy ns ns tags))
  ([ns orig tags]
     (if-let [policy (cached-config ns tags)]
       policy
       (if-let [policy (matching-policy (@config ns) tags)]
         (set-config-cache! orig tags policy)
         (if-let [parent (parent-ns ns)]
           (active-policy parent ns tags)
           (set-config-cache! orig tags [:default]))))))
     


;; Policy execution
;; --------------------------

(declare apply-policy)

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
      (doall (reduce policy-step state (as-sequence policy)))
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
                     :ns ns
                     :tags tags
                     :thread-id  (.getId (Thread/currentThread))
                     :ts (System/currentTimeMillis))]
         (send-off probe-agent apply-policy policy state)
         nil)))
  ([tags state]
     (probe* (.name *ns*) tags state)))

(defmacro probe [tags & keyvals]
  `(probe* ~(as-tags tags)
             (assoc ~(apply array-map keyvals)
               :line ~(:line (meta &form)))))

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

