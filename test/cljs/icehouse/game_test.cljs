(ns icehouse.game-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.game :as game]
            [icehouse.game.rendering :as render]
            [icehouse.game-logic :as logic]
            [icehouse.utils :as utils]
            [icehouse.geometry :as geo]))

(deftest potential-target-test
  (testing "potential-target? correctly identifies opponent standing pieces in trajectory"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target {:id "t1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing}]
      (is (render/potential-target? attacker target "p1") "Should hit opponent standing piece directly ahead")))

  (testing "potential-target? rejects own pieces"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target {:id "t1" :player-id "p1" :x 200 :y 100 :size :small :orientation :standing}]
      (is (not (render/potential-target? attacker target "p1")) "Should not target own pieces")))

  (testing "potential-target? rejects pointing pieces"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target {:id "t1" :player-id "p2" :x 200 :y 100 :size :small :orientation :pointing :angle js/Math.PI}]
      (is (not (render/potential-target? attacker target "p1")) "Should only target standing pieces")))

  (testing "potential-target? uses color-based validation"
    (let [attacker {:id "a1" :player-id "p1" :colour "#ff0000" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target1 {:id "t1" :player-id "p2" :colour "#00ff00" :x 200 :y 100 :size :small :orientation :standing} ;; different color
          target2 {:id "t2" :player-id "p2" :colour "#ff0000" :x 200 :y 120 :size :small :orientation :standing}] ;; same color as attacker
      (is (render/potential-target? attacker target1 "p1") "Should target piece with different color")
      (is (not (render/potential-target? attacker target2 "p1")) "Should NOT target piece with same color even if owned by opponent"))))

(deftest find-closest-target-test
  (testing "find-closest-target identifies the nearest valid target"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          target1 {:id "t1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing}
          target2 {:id "t2" :player-id "p2" :x 250 :y 100 :size :small :orientation :standing}
          board [target1 target2]]
      (is (= "t1" (:id (render/find-closest-target attacker "p1" board))) "Should target the closest piece")))

  (testing "find-closest-target ignores targets blocked by other pieces"
    (let [attacker {:id "a1" :player-id "p1" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          blocker {:id "b1" :player-id "p1" :x 150 :y 100 :size :small :orientation :standing}
          target {:id "t1" :player-id "p2" :x 250 :y 100 :size :small :orientation :standing}
          board [blocker target]]
      (is (nil? (render/find-closest-target attacker "p1" board))
          "Should not find target because it is blocked by my own piece"))))

(deftest calculate-iced-pieces-test
  (testing "calculate-iced-pieces identifies pieces with enough attack pips"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100}
                 {:id "a1" :player-id "p1" :size :medium :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100}]]
      ;; medium attacker (2 pips) vs small defender (1 pip)
      (is (contains? (logic/calculate-iced-pieces board) "d1") "Defender should be iced"))))

(deftest calculate-over-ice-test
  (testing "calculate-over-ice calculates excess pips correctly"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100}
                 {:id "a1" :player-id "p1" :size :large :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100}]]
      ;; large attacker (3 pips) vs small defender (1 pip)
      ;; needs 2 pips to ice, so excess is 1
      (let [over-ice (logic/calculate-over-ice board)]
        (is (= 1 (get-in over-ice ["d1" :excess])) "Excess should be 1")))))

(deftest capturable-piece-test
  (testing "capturable-piece? correctly identifies attackers that can be captured"
    (let [board [{:id "d1" :player-id "p2" :size :small :orientation :standing :x 200 :y 100} ;; 1 pip
                 {:id "a1" :player-id "p1" :size :medium :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 100} ;; 2 pips
                 {:id "a2" :player-id "p1" :size :small :orientation :pointing :angle 0 :target-id "d1" :x 100 :y 120}]] ;; 1 pip
      ;; Total attack: 3 pips. Needed to ice Small (1 pip): 2 pips. Excess: 1 pip.
      ;; Attacker a2 (Small, 1 pip) should be capturable. Attacker a1 (Medium, 2 pips) should NOT.
      (let [a1 (first (filter #(= (:id %) "a1") board))
            a2 (first (filter #(= (:id %) "a2") board))]
        (is (logic/capturable-piece? a2 "p2" board) "Owner of iced defender should be able to capture Small attacker (1 pip <= 1 excess)")
        (is (not (logic/capturable-piece? a1 "p2" board)) "Should NOT be able to capture Medium attacker (2 pips > 1 excess)")))))
