(defproject com.vitalreactor/probe "0.9.0"
  :description "A library for interacting with dynamic program state"
  :url "http://github.com/vitalreactor/probe"
  :license {:name "MIT License" :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; Libraries
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/core.memoize "0.5.3"]
                 ;; Logging
                 [ch.qos.logback/logback-classic "1.0.13"
                  :exclusions [org.slf4j/slf4j-api]]
                 [org.clojure/tools.logging "0.2.4"]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 ;; Testing
                 [midje "1.5.0"]]
  :plugins [[lein-midje "3.0.0"]]
  :repl-options {:port 4005})
                 


