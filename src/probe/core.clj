(ns probe.core
  (:require [clojure.core.async :refer [>! <! <!! >!! go go-loop] :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [probe.wrap :as w]
            [probe.fabric :as fab]))

;; Tag Management
;; -----------------------------------------

(defn expand-namespace
  "probe.foo.bar => [:ns/probe :ns/probe.foo :ns/probe.foo.bar"
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
;; Direct probes
;; -----------------------------------------

(defn probe*
  "Probe the provided state in the current namespace using tags for dispatch"
  ([ns tags state]
     (let [ntags (expand-namespace ns)
           etags (expand-tags tags)
           state (assoc state
                   :tags (concat etags ntags)
                   :ns (ns-name ns)
                   :thread-id  (.getId (Thread/currentThread))
                   :ts (java.util.Date.))]
       (fab/write-probe state)))
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

(defn probe-state!
  "Add a probe function to a state element or a symbol
   that resolves to a reference."
  [tags transform-fn ref]
  {:pre [(fn? transform-fn) (state? ref)]}
  (add-watch
   (if (symbol? ref)
     (if-let [actual-ref (var-get (resolve ref))]
       actual-ref
       (throw (ex-info "Symbol is not " {:symbol ref})))
     ref)
   ::probe (state-watcher tags transform-fn)))

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
  [tags v f]
  (let [m (meta v)
        static (array-map :line (:line m) :fname (:name m))
        except-fn (set (cons :probe/fn-except tags))
        enter-tags (set (cons :probe/fn-enter tags))
        exit-tags (set (cons :probe/fn-exit tags))]
    (fn [& args]
      (do (probe* enter-tags (assoc static
                               :fn :enter
                               :args args))
          (let [result (try (apply f args)
                            (catch java.lang.Throwable e
                              (probe* except-fn (assoc static
                                                  :fn :except
                                                  :exception e
                                                  :args args))
                              (throw e)))]
            (probe* exit-tags (assoc static
                                :fn :exit
                                :args args
                                :return result))
            result)))))

;; Function probe API
;; --------------------------------------------

(defn probe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (w/wrap-var-fn fsym (partial probe-fn-wrapper tags)))
  ([fsym]
     (probe-fn! [] fsym)))

(defn unprobe-fn!
  ([tags fsym]
     {:pre [(symbol? fsym)]}
     (w/unwrap-var-fn fsym))
  ([fsym]
     (unprobe-fn! [] fsym)))

;; Namespace probe API
;; --------------------------------------------

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

