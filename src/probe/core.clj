(ns probe.core
  "Treat the program's dynamic execution traces as first class system state;
   construct a simple di-graph topology between probe points and reporting
   sinks using core.async channel transforms and filters to manage the flow
   of data."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as clog]
            [clojure.core.memoize :as memo]
            [clojure.core.async :refer [chan >! <! <!! >!! go go-loop] :as async]
            [probe.wrap :as wrap]
            [probe.sink :as sink]))

;; =====================================
;;  TOPOLOGY
;; =====================================

;;
;; ## Exception handling
;;

(defonce error-channel (chan))

(defonce error-router
  (go
   (while true
     (when-let [{:keys [state exception]} (<! error-channel)]
       (clog/error "Probe error detected" state exception)
       (recur)))))




;;
;; ## Sinks
;;

(defonce sinks (atom {}))

(defn sink-processor [f c]
  (go-loop []
    (when-let [state (<! c)]
      (try
        (f state)
        (catch java.lang.Throwable e
          (>! error-channel {:state state :exception e})))
      (recur))))

(defn sink-names []
  (keys @sinks))

(defn get-sink [name]
  (@sinks name))

(declare unsubscribe sink-subscriptions link-to-sink
         unlink-from-sink parse-sink-policy)

(defn rem-sink
  "Remove a sink and all subscriptions"
  ([name unsub?]
     (let [{:keys [mix out in] :as sink} (get-sink name)]
       (when sink
         (doall
          (map (fn [sub] (unsubscribe (:name sub) (:sink sub)))
               (sink-subscriptions name)))
         (async/close! in)
         (assert (nil? (<!! out)))
         (swap! sinks dissoc name))))
  ([name]
     (rem-sink name true)))

(defn add-sink
  "Create a named sink function for probe state"
  ([name f & {:keys [force? policy]}]
     {:pre [(keyword? name)]}
     (let [prior (get-sink name)]
       (when prior
         (if force?
           (map (fn [sub] (unlink-from-sink sub))
                (sink-subscriptions name))
           (throw (ex-info "Sink already configured; remove or force add" {:sink name})))))
     (let [c (chan)
           mix (async/mix c)
           out (sink-processor f c)
           policy-fn (parse-sink-policy policy)
           sink {:name name
                 :function f
                 :in c
                 :mix mix
                 :out out
                 :policy-fn policy-fn}]
       (swap! sinks assoc name sink)
       (when force?
         (map (fn [sub] (link-to-sink sub))
              (sink-subscriptions name)))
       sink)))

(defn swap-sink-policy!
  "Change the duplication policy on an existing sink"
  [name policy]
  {:pre [(keyword? name) (or (keyword? policy) (fn? policy))]}
  (if-let [prior (get-sink name)]
    (let [policy-fn (parse-sink-policy policy)
          with-policy (assoc prior :policy-fn policy-fn)]
      (swap! sinks #(assoc % name with-policy))
      with-policy)
    (throw (ex-info (str "No sink configured for name " name) {:sink name}))))


;;
;; sink data de-duplication Policy Helpers
;; ---------------------------------------


(defn mk-state-sub-map
  [state subscribers]
  (map #(hash-map :sub % :new-state ((:transform %) state)) subscribers))

(defn policy-unique
  "Deduplicates transformed states. returns
   a seq of maps of :sub (subscriber ) and
   :new-state (state transformed by (:transform sub))"
  [state subscribers]
  (let [state-sub-map (mk-state-sub-map state subscribers)]
    (map first
         (->
          (group-by :new-state state-sub-map)
          (dissoc nil)
          (vals)))))

(defn policy-first
  "Grabs the first subscriber passed to a sink,
   applies transform and returns only that state"
  [state subscribers]
  (let [state-sub-map (mk-state-sub-map state subscribers)]
    (if-let [s (ffirst
                (->
                 (group-by :new-state state-sub-map)
                 (dissoc nil)
                 (vals)))]
      (vector s)
      (vector))))

(defn policy-all
  "Applies transform to state for all subscribers"
  [state subscribers]
  (let [state-sub-map (mk-state-sub-map state subscribers)]
    (reduce concat
     (->
      (group-by :new-state state-sub-map)
      (dissoc nil)
      (vals)))))

(defn policy-none
  "Returns an empty list, short circuiting any
   further processing of state by passing an
   empty list"
  [_ _]
  '())

;;Map of provided sink policy fns
(def sink-policies
  {:all policy-all
   :first policy-first
   :unique policy-unique
   :none policy-none})

(defn parse-sink-policy
  [policy]
  (cond
   (fn? policy) policy
   (keyword? policy) (or
                      (get sink-policies policy)
                      (throw (ex-info (str "No policy of name: " policy) {})))
   :else (:all sink-policies)))



;;
;; ## Subscriptions
;;

(defonce ^:private subscription-table (atom {}))

(defn subscriptions []
  (keys @subscription-table))

(defn get-subscription [selector sink-name]
  {:pre [(coll? selector) (keyword? sink-name)]}
  (@subscription-table [(set selector) sink-name]))

(defn sink-subscriptions [name]
  (filter #(= (:sink %) name) (vals @subscription-table)))

(defn- subscribers*
  "Get any subscriptions for the provided tag set.
   This is not a terribly cheap operation so use the
   public memoized version in subscribers below."
  [tags]
  (let [probe-tags (set tags)]
    (filter #(set/subset? (:selector %) probe-tags)
            (vals @subscription-table))))

(def subscribers (memo/memo subscribers*))

(defn subscribers? [tags]
  (not (empty? (subscribers tags))))

(defn- link-to-sink [subscription]
  (let [{:keys [mix] :as sink} (get-sink (:sink subscription))]
    (assert (and sink subscription))
    (async/admix (:mix sink) (:channel subscription))))

(defn- unlink-from-sink [subscription]
  (let [{:keys [mix] :as sink} (get-sink (:sink subscription))]
    (assert (and sink subscription))
    (async/unmix (:mix sink) (:channel subscription))))

(defn subscribe
  ([selector sink-name & {:keys [transform]}]
     {:pre [(set? selector) (keyword? sink-name)]}
     (let [existing (get-subscription selector sink-name)
           ;transform-fn (mk-transform-fn transform filter sample-freq)
           subscription {:selector (set selector)
                         :channel (chan)
                         :sink sink-name
                         :name selector
                         :transform (or transform identity)}]
       (when existing
         (unsubscribe selector sink-name))
       (link-to-sink subscription)
       (swap! subscription-table assoc [selector sink-name] subscription)
       (memo/memo-clear! subscribers)
       subscription)))


(defn unsubscribe
  ([selector sink-name]
     (let [{:keys [channel] :as sub} (get-subscription selector sink-name)]
       (when-not sub
         (throw (ex-info "Selector-sink pair not found"
                         {:selector selector :sink sink-name})))
       (unlink-from-sink sub)
       (async/close! channel)
       (swap! subscription-table dissoc [selector sink-name])
       (memo/memo-clear! subscribers)
       nil)))

(defn unsubscribe-all []
  (doall
   (map (fn [[sel sink]]
          (unsubscribe sel sink))
        (keys @subscription-table)))
  {})

;;
;; ## Router
;;

(defonce input (chan))

(defn write-state
  "External API to submit probe state to the fabric"
  [state]
  (>!! input state))

(defn- apply-sink-policy
  "Map dedup-states to each sink, preventing
   us from sending multiple copies of the same
   state to each sink"
  [tags state]
  (let [subs (subscribers tags)]
    (mapcat
     #((:policy-fn (get-sink (first %))) state (second %))
     (group-by :sink subs))))

(def router-handler
  (go-loop []
    (let [state (<! input)]
      (clog/trace "Routing probe state: " state)
      (when-let [tags (and (map? state) (:tags state))]
        (when (coll? tags)
          (doseq [sub (apply-sink-policy tags state)]
            (try
              (clog/trace "Writing channel for: " [(:name (:sub sub))
                                                   (:sink (:sub sub))])
              (>! (:channel (:sub sub)) (:new-state sub))
              (catch java.lang.Throwable t
                (>! error-channel {:state ~state :exception t}))))))
      (recur))))

;; ==================================
;; Transform Helpers
;; ==================================


(defn- sampler-fn [max]
  "Creates function that returns state once
   every max calls"
  (let [count (atom 1)]
    (fn [state]
      (if (>= @count max)
        (do (reset! count 1) state)
        (do (swap! count inc) nil)))))

(defn mk-sample-transform
  [freq]
  {:pre [(number? freq)]}
  (sampler-fn freq))

(defn mk-filter-transform
  [filter-fn]
  {:pre [(fn? filter-fn)]}
  (fn [state]
    (if (filter-fn state) state nil)))

(defn mk-remove-transform
  [remove-fn]
  {:pre [(fn? remove-fn)]}
  (fn [state]
    (if (remove-fn state) nil state)))


;; =============================================
;;  PROBE POINTS
;; =============================================


;; Tag Management
;; -----------------------------------------

(defn expand-namespace
  "Generate all sub-namespaces for tag filtering:
   probe.foo.bar => [:ns/probe :ns/probe.foo :ns/probe.foo.bar]"
  [ns]
  {:pre [(string? (name ns))]}
  (->> (str/split (name ns) #"\.")
       (reduce (fn [paths name]
                 (if (empty? paths)
                   (list name)
                   (cons (str (first paths) "." name) paths)))
               nil)
       (map (fn [path] (keyword "ns" path)))))

;;
;; Expression probes
;; -----------------------------------------

(defonce ^:dynamic capture-bindings nil)

(defn- dynamic-var? [sym]
  (:dynamic (meta (resolve sym))))

(defn- valid-bindings? [list]
  (and (sequential? list)
       (every? symbol? list)
       (every? namespace list)
       (every? dynamic-var? list)))

(defn capture-bindings!
  "Establish a global capture list for bindings to be passed on the
   :probe.core/bindings key in the state object.  You'll need to filter
   these in a transform if you don't want them in your sink!  Also, this
   function expects fully qualified symbol names of the vars you wish to
   grab bindings for."
  [list]
  {:pre [(or (nil? list) (valid-bindings? list))]}
  (alter-var-root #'capture-bindings (fn [old] list)))

(defn grab-bindings []
  (->> capture-bindings
       (map (fn [sym]
              (when-let [var (resolve sym)]
                (when-let [val (var-get var)]
                  [sym val]))))
       (into {})))

(defmacro without-bindings [& body]
  `(binding [capture-bindings nil]
     ~@body))

(defn probe*
  "Probe the provided state in the current namespace using tags for dispatch"
  ([ns tags state]
     (let [ntags (expand-namespace ns)
           bindings (grab-bindings)
           state (assoc state
                   :tags (set (concat tags ntags))
                   :ns (ns-name ns)
                   :thread-id  (.getId (Thread/currentThread))
                   :ts (java.util.Date.))
           state (if (and bindings (not (empty? bindings)))
                   (assoc state :bindings bindings)
                   state)]
       (write-state state)))
  ([tags state]
     (probe* (ns-name *ns*) tags state)))

(defmacro probe
  "Take a single map as first keyvals element, or an upacked
   list of key and value pairs."
  [tags & keyvals]
  {:pre [(every? keyword? tags)]}
  `(probe* (quote ~(ns-name *ns*))
           ~tags
           (assoc ~(if (= (count keyvals) 1)
                     (first keyvals)
                     (apply array-map keyvals))
             :line ~(:line (meta &form)))))

(defmacro probe-expr
  "Like logging/spy; generates a probe state with :form and return
   :value keys and the :probe/expr tag"
  [& body]
  (let [[tags thebody] (if (and (set? (first body)) (> (count body) 1))
                         [(cons :probe/expr (first body)) (rest body)]
                         [#{:probe/expr} body])]
    `(let [value# (do ~@thebody)]
       (probe ~tags
              :form '(do ~@(rest &form))
              :value value#)
       value#)))

;;
;; State probes
;; -----------------------------------------

(defn- state-watcher [tags transform-fn]
  {:pre [(fn? transform-fn)]}
  (let [thetags (set (cons :probe/watch tags))]
    (fn [_ _ _ new]
      (probe* thetags (transform-fn new)))))

(defn- state? [ref]
  (let [type (type ref)]
    (or (= clojure.lang.Var type)
        (= clojure.lang.Ref type)
        (= clojure.lang.Atom type)
        (= clojure.lang.Agent type))))

(defn- resolve-ref
  "Usually we want to the value of a Var and not the var itself when
   submitting a watcher.  Thus, we dereference the val with val-get
   and if the result is a state? element, we use that instead of the
   provided or referenced var"
  [ref]
  (cond (and (symbol? ref) (state? (var-get (resolve ref))))
        (var-get (resolve ref))
        (or (and (symbol? ref) (fn? (state? (resolve ref))))
            (and (var? ref) (fn? (var-get ref))))
        (throw (ex-info "Probing Vars that hold functions is verboten"))
        (and (symbol? ref) (state? (resolve ref)))
        (resolve ref)
        (and (var? ref) (state? (var-get ref)))
        (var-get ref)
        (state? ref)
        ref
        :default
        (throw (ex-info "Do not know how to probe provided reference"
                        {:ref ref :type (type ref)}))))


(defn probe-state!
  "Add a probe function to a state element or symbol
   that resolves to a state location."
  [tags transform-fn ref]
  {:pre [(fn? transform-fn)]}
  (let [stateval (resolve-ref ref)]
    (add-watch stateval ::probe (state-watcher tags transform-fn))))

(defn unprobe-state!
  "Remove the probe function from the provided reference"
  [ref]
  (let [stateval (resolve-ref ref)]
    (remove-watch stateval ::probe)))


;;
;; Function probes
;; -----------------------------------------

(defn- probe-fn-wrapper
  "Wrap f of var v to insert pre,post, and exception wrapping probes that
   match tags :entry-fn, :exit-fn, and :except-fn."
  [tags v f]
  (let [m (meta v)
        static (array-map :line (:line m) :fname (:name m))
        except-fn (set (concat [:probe/fn :probe/fn-except] tags))
        enter-tags (set (concat [:probe/fn :probe/fn-enter] tags))
        exit-tags (set (concat [:probe/fn :probe/fn-exit] tags))]
    (fn [& args]
      (do (probe* enter-tags (assoc static
                               :args args))
          (let [result (try (apply f args)
                            (catch java.lang.Throwable e
                              (probe* except-fn (assoc static
                                                  :exception e
                                                  :args args))
                              (throw e)))]
            (probe* exit-tags (assoc static
                                :args args
                                :value result))
            result)))))

;; Function probe API
;; --------------------------------------------

(defn probe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (wrap/wrap-var-fn fsym (partial probe-fn-wrapper tags)))
  ([fsym]
     (probe-fn! [] fsym)))

(defn unprobe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (wrap/unwrap-var-fn fsym))
  ([fsym]
     (unprobe-fn! [] fsym)))

;; Namespace probe API
;; --------------------------------------------

(defn- probe-var-fns
  "Probe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get wrap/as-var))
        (map probe-fn!))))

(defn- unprobe-var-fns
  "Unprobe all function carrying vars"
  [vars]
  (doall
   (->> vars
        (filter (comp fn? var-get wrap/as-var))
        (map probe-fn!))))

(defn probe-ns! [ns]
  (probe-var-fns (keys (ns-publics ns))))
(defn unprobe-ns! [ns]
  (unprobe-var-fns (keys (ns-publics ns))))

(defn probe-ns-all! [ns]
  (probe-var-fns (keys (ns-interns ns))))
(defn unprobe-ns-all! [ns]
  (unprobe-var-fns (keys (ns-interns ns))))
