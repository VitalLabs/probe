(defproject com.vitalreactor/probe "0.9.5"
  :description "A library for interacting with dynamic program state"
  :url "http://github.com/vitalreactor/probe"
  :license {:name "MIT License" :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 ;; Libraries
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/core.memoize "0.5.6"]
                 ;; Logging
                 [ch.qos.logback/logback-classic "1.0.13"
                  :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]]
  :profiles {:dev {:dependencies [;; Testing
                                  [midje "1.6.3"]]}}
  :plugins [[lein-midje "3.0.0"]])
