(ns icehouse.game-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.game :as game]
            [icehouse.utils :as utils]
            [icehouse.geometry :as geo]))

(deftest potential-target-test
  (testing "potential-target? correctly identifies opponent standing pieces in trajectory"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target {:id "t1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing}]
      (is (game/potential-target? attacker target "p1") "Should hit opponent standing piece directly ahead"))

    (testing "potential-target? rejects own pieces"
      (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
            target {:id "t1" :player-id "p1" :x 200 :y 100 :size :small :orientation :standing}]
        (is (not (game/potential-target? attacker target "p1")) "Should not target own pieces")))

    (testing "potential-target? rejects pointing pieces"
      (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
            target {:id "t1" :player-id "p2" :x 200 :y 100 :size :small :orientation :pointing :angle js/Math.PI}]
        (is (not (game/potential-target? attacker target "p1")) "Should only target standing pieces")))))

(deftest calculate-iced-pieces-test
  (testing "calculate-iced-pieces identifies pieces with enough attack pips"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100}
                 {:id "a1" :player-id "p1" :size :medium :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100}]]
      ;; medium attacker (2 pips) vs small defender (1 pip)
      (is (contains? (game/calculate-iced-pieces board) "d1") "Defender should be iced"))))

(deftest calculate-over-ice-test
  (testing "calculate-over-ice calculates excess pips correctly"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100}
                 {:id "a1" :player-id "p1" :size :large :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100}]]
      ;; large attacker (3 pips) vs small defender (1 pip)
      ;; needs 2 pips to ice, so excess is 1
      (let [over-ice (game/calculate-over-ice board)]
        (is (= 1 (get-in over-ice ["d1" :excess])) "Excess should be 1")))))

(deftest capturable-piece-test
  (testing "capturable-piece? correctly identifies attackers that can be captured"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100}
                 {:id "a1" :player-id "p1" :size :large :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100}]
          piece (second board)]
      (is (game/capturable-piece? piece "p2" board) "Owner of iced defender should be able to capture attacker"))))
