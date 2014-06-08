(ns playground.core
  (:require [playground.other :as o]
            [probe.core :as p]
            [probe.sink :as s]))

(comment
  {:namespace <namespace>
   :public <BOOL> ;; optional, defaults to true
   :private <BOOL> ;; optional, defaults to false
   :tags <SET|VECTOR> ;; optional
   :suppress-results? <BOOL> ;; optional, defaults to false
   }
  )

(def probe-config
  [{:namespace 'playground.core
    :publics {:tags #{} :level 0}
    :private {:tags #{} :level 1}}])

(defn dumb [x] x)
(defn- dumb-intern [x] x)

(p/add-sink :printer s/console-raw)
;; (p/rem-sink :printer)

(p/subscribe #{:test} :printer)

@probe.wrap/probed-originals

(p/probe-ns-all! [:test] 'playground.other)
(p/unprobe-ns-all! 'playground.other)
