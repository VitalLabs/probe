(ns probe.policy-rules
  (:refer-clojure :exclude [==])
  (:use [clojure.core.logic])
  (:require [probe.wrap :as w]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.core.memoize :as memo]))

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
