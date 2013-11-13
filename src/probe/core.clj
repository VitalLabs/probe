(ns probe.core
  (:refer-clojure :exclude [==])
  (:use [clojure.core.logic])
  (:require [clj-probe.wrap :as w]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.core.memoize :as memo]))

(defn- as-sequence [value]
  (if (sequential? value) value
      [value]))

(defn- as-tags [tags]
  (if (set? tags) tags
      (set (as-sequence tags))))

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

;; Add new rules to generate policies
;; lhs: tags + ns + fname 
;; rhs: policy (:debug 

(defrel probe-rule ^:index scopes ^:index tags policy)

(comment
  (map (partial apply probe-rule)
       [[ [ experiment ]
               [ :warn ]
               :log ]
        [ [ experiment.infra ]
          [ :debug ]
          :log ]
        [ [ experiment.infra.models 
           experiment.infra.client ]
          [ :info ]
          :record ]]))

;; For tags that encompass other tags
;; e.g. :warn enables :error
(defrel enables child parent)

;; Log level rules
(facts enables
  [[:warn :error]
   [:info :warn]
   [:debug :info]
   [:trace :debug]])

(defne expanded-tags [original expanded]
  ([() ex] (== ex ()))
  ([[f . rest] expanded]
     (fresh [g ex2]
       (enables f g)
       (conso f expanded ex2)
       (expanded-tags rest ex2)))
  ([[f . rest] ex]
     (fresh [g o2 ex2]
       (enables f g)
       (!= g nil)
       (conso g expanded ex2)
       (conso g rest o2)
       (expanded-tags o2 ex2))))
     

;; - most specific matching ns
;; - most specific matching level tag
;; - all matches other tags
;; - fname can be empty

;; Logic utilities
(defn intersecto [l1 l2 elt]
  (membero elt l1)
  (membero elt l2))

(defn matching-tag [ptags rtags]
  (fresh [tag etags]
    (expanded-tags rtags etags)
    (intersecto ptags etags tag)))

;; Active rules are those that equal or subsume
;; one of the probe tags.  Matching tags are the
;; most dominate rule tag.

(defn- parent-ns
  "Return the next level of the namespace hierarchy"
  [ns]
  {:pre [(symbol? ns)]}
  (let [path (str/split (name ns) #"\.")]
    (if (> (count path) 1) 
     (symbol (str/join "." (take (- (count path) 1) path)))
     nil)))

(defn parent-scope 
  [scope parent]
  (project [scope]
    (== parent (parent-ns scope))))
  
(defne specific-scope [pscope rscopes]
  ([_ [ps . tail]])
  ([ps [head . tail]] 
     (specific-scope ps tail))
  ([ps ()]
     (fresh [parent] 
            (parent-scope ps parent)
            (specific-scope parent rscopes))))

(defn active-probe-policies 
  "Return the policies that are valid/active for the
   current probe point according to the current rule 
   set"
  [pscope ptags]
  (distinct
   (run* [policy]
      (fresh [rscopes rtags]
        (probe-rule rscopes rtags policy)
        (matching-tag ptags rtags)
        (firsto (specific-scope pscope rscopes))))))

;; Update ruleset

(defn assert-rule [rule]
  (let [[scopes tags _ policy] rule]
    (fact probe-rule scopes tags policy)))

(defn retract-rules [constraints]
  )

(defn all-rules []
  (run* [rule]
    (fresh [s t p]
      (probe-rule s t p)
      (== rule [s t p]))))

(defn print-rules []
  (/ 1 0)
  (clojure.pprint/pprint 
   (all-rules)))

;; Memoize logic operations w/ LRU policy

(declare active-policy)


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

