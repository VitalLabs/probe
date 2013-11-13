(defproject probe "0.1.0"
  :description "A library for interacting with dynamic program state"
  :url "http://github.com/vitalreactor/clj-probe"
  :license {:name "MIT License" :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/core.logic "0.8.3"]
                 [org.clojure/core.memoize "0.5.3"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [com.datomic/datomic-pro "0.8.3843"
                  :exclusions [org.slf4j/slf4j-nop org.slf4j/slf4j-log4j12]]]
  :repl-options {:port 4005})


