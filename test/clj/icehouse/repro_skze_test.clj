(ns icehouse.repro-skze-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.game-handlers :as handlers]
            [icehouse.game-rules :as rules]
            [icehouse.messages :as msg]))

(deftest first-two-pieces-must-be-defensive-test
  (testing "first piece must be defensive"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5} :captured []}}
                :board []}
          ;; Pointing piece (attacker)
          piece {:x 100 :y 100 :size :small :orientation :pointing :angle 0}
          result (handlers/validate-placement game "p1" piece)]
      (is (some? result) "First piece should not be allowed to be pointing")
      (is (= "FIRST_PIECES_MUST_BE_DEFENSIVE" (:code result)))))

  (testing "second piece must be defensive"
    (let [game {:players {"p1" {:pieces {:small 4 :medium 5 :large 5} :captured []}}
                :board [{:player-id "p1" :orientation :standing :size :small :x 50 :y 50 :angle 0}]}
          ;; Pointing piece (attacker)
          piece {:x 150 :y 150 :size :small :orientation :pointing :angle 0}
          result (handlers/validate-placement game "p1" piece)]
      (is (some? result) "Second piece should not be allowed to be pointing")
      (is (= "FIRST_PIECES_MUST_BE_DEFENSIVE" (:code result)))))

  (testing "third piece can be attacking"
    (let [game {:players {"p1" {:pieces {:small 3 :medium 5 :large 5} :captured []}}
                :board [{:id "d1" :player-id "p1" :orientation :standing :size :small :x 50 :y 50 :angle 0}
                        {:id "d2" :player-id "p1" :orientation :standing :size :small :x 100 :y 50 :angle 0}
                        {:id "d3" :player-id "p2" :orientation :standing :size :small :x 200 :y 200 :angle 0}]}
          ;; Pointing piece (attacker) pointing at d3
          piece {:x 150 :y 200 :size :small :orientation :pointing :angle 0}
          result (handlers/validate-placement game "p1" piece)]
      (is (nil? result) "Third piece should be allowed to be pointing if it has a target")))
)
