(ns playground.core
  (:require [probe.core :as p]
            [probe.sink :as s]
            [probe.ns :refer [defprobes]]))

;; (p/add-sink :printer s/console-raw)

;; (defprobes test-probes #{:printer}
;;   ;; I want to return a function that, when called, probes this
;;   ;; entire namespace according to the options
;;   [:other   playground.other   #{:test} {:private true :suppress-results? true}]
;;   [:another playground.another #{:test} {:public false :private true}]
;;   )

;; (p/ns-privates 'playground.another)
;; (probe-another!)
;; (unprobe-another!)
;; (install-test-probes!)
;; (uninstall-test-probes!)

(comment

  {:<NAME>
   {:namespace <namespace>
    :tags <SET|VECTOR>
    :public <BOOL> ;; optional, defaults to true
    :private <BOOL> ;; optional, defaults to false
    :suppress-results? <BOOL> ;; optional, defaults to false
    :level <INT>}}
  )


;; (p/rem-sink :printer)

;; (p/subscribe #{:test} :printer)

;; @probe.wrap/probed-originals

;; (p/probe-ns-all! [:test] 'playground.other)
;; (p/unprobe-ns-all! 'playground.other)
