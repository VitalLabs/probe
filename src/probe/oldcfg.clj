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

(defn remove-config!
  ([ns tags]
     (swap! config update-in [ns] dissoc (as-tags tags))))

(declare expand-tags)

(defn- conflicting-tagsets
  "No two tag sets should overlap at a given NS level"
  [ns tags]
  (let [tags (expand-tags tags)]
    (->> (keys (get @config ns))
         (filter (fn [existing-tags]
                   (not (empty? (apply set/intersection tags existing-tags))))))))

(defn- import-config
  "TODO: Allow shorthand format?"
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
     (swap! config (fn [old]
                     (-> old
                         (update-in [ns] dissoc (conflicting-tagsets ns tags))
                         (assoc-in [ns (as-tags tags)] policy))))
     true))

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
         (throw (Exception. "oops")) ;;(set-config-cache! orig tags policy)
         (if-let [parent (parent-ns ns)]
           (active-policy parent ns tags)
           (set-config-cache! orig tags [:default]))))))
     

