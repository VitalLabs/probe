(ns playground.other)

(defn add-one [a]
  (inc a))

(defn- add-two [a]
  (+ a 2))

(add-one 1)
(add-two 1)
