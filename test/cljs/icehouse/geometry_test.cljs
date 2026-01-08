(ns icehouse.geometry-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.geometry :as geo]))

(deftest rotate-point-test
  (testing "no rotation returns same point"
    (let [[x y] (geo/rotate-point [10 0] 0)]
      (is (< (js/Math.abs (- x 10)) 0.001))
      (is (< (js/Math.abs y) 0.001))))

  (testing "90 degree rotation"
    (let [[x y] (geo/rotate-point [10 0] (/ js/Math.PI 2))]
      (is (< (js/Math.abs x) 0.001))
      (is (< (js/Math.abs (- y 10)) 0.001))))

  (testing "180 degree rotation"
    (let [[x y] (geo/rotate-point [10 0] js/Math.PI)]
      (is (< (js/Math.abs (- x -10)) 0.001))
      (is (< (js/Math.abs y) 0.001)))))

(deftest piece-vertices-test
  (testing "standing piece returns 4 vertices (square)"
    (let [piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          verts (geo/piece-vertices piece)]
      (is (= 4 (count verts)))))

  (testing "pointing piece returns 3 vertices (triangle)"
    (let [piece {:x 100 :y 100 :size :small :orientation :pointing :angle 0}
          verts (geo/piece-vertices piece)]
      (is (= 3 (count verts)))))

  (testing "standing piece vertices are centered correctly"
    (let [piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          verts (geo/piece-vertices piece)
          ;; small = 40px, so half = 20
          ;; vertices should be at (80,80), (120,80), (120,120), (80,120)
          expected [[80 80] [120 80] [120 120] [80 120]]]
      (doseq [[expected-v actual-v] (map vector expected verts)]
        (is (< (js/Math.abs (- (first expected-v) (first actual-v))) 0.001))
        (is (< (js/Math.abs (- (second expected-v) (second actual-v))) 0.001))))))

(deftest pieces-intersect-test
  (testing "overlapping pieces intersect"
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 110 :y 110 :size :small :orientation :standing :angle 0}]
      (is (geo/pieces-intersect? piece1 piece2))))

  (testing "same position pieces intersect"
    (let [piece1 {:x 100 :y 100 :size :medium :orientation :standing :angle 0}
          piece2 {:x 100 :y 100 :size :small :orientation :pointing :angle 0}]
      (is (geo/pieces-intersect? piece1 piece2))))

  (testing "non-overlapping pieces don't intersect"
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 200 :y 200 :size :small :orientation :standing :angle 0}]
      (is (not (geo/pieces-intersect? piece1 piece2)))))

  (testing "pieces just touching edges don't intersect (gap between them)"
    ;; small = 40px, so two pieces 45px apart (center to center) should not overlap
    ;; 40/2 + 40/2 = 40px needed for edge-to-edge, so 45px apart means 5px gap
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 145 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (geo/pieces-intersect? piece1 piece2)))))

  (testing "rotated pieces intersection"
    ;; Two pieces at same location but different rotations still intersect
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 100 :y 100 :size :small :orientation :standing :angle (/ js/Math.PI 4)}]
      (is (geo/pieces-intersect? piece1 piece2))))

  (testing "pointing pieces at distance don't intersect"
    (let [piece1 {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          piece2 {:x 300 :y 100 :size :large :orientation :pointing :angle js/Math.PI}]
      (is (not (geo/pieces-intersect? piece1 piece2))))))

(deftest in-front-of-test
  (testing "ray from tip intersects target at various angles"
    ;; Large attacker at (100, 100) pointing right (angle=0) at small defender at (200, 100)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 200 :y 100 :size :small :orientation :standing :angle 0}]
      (is (geo/in-front-of? attacker defender) "Should hit defender directly ahead")))

  (testing "ray misses target that is off-angle"
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}  ;; pointing right
          defender {:x 100 :y 200 :size :small :orientation :standing :angle 0}] ;; below
      (is (not (geo/in-front-of? attacker defender)) "Should miss defender below")))

  (testing "close-range attack - tip near target edge"
    ;; Large piece: tip-offset = 0.75 * 60 = 45px from center
    ;; At x=100, tip is at x=145
    ;; Small defender at x=180 has left edge at x=160 (180 - 20)
    ;; Ray should hit the defender
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 180 :y 100 :size :small :orientation :standing :angle 0}]
      (is (geo/in-front-of? attacker defender) "Close-range attack should hit")))

  (testing "very close attack - tip almost touching target"
    ;; Tip at x=145, defender left edge at x=150 (170-20)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 170 :y 100 :size :small :orientation :standing :angle 0}]
      (is (geo/in-front-of? attacker defender) "Very close attack should hit"))))

(deftest within-range-test
  (testing "target edge within range"
    ;; Large attacker has range of 90px from tip (height = 2 * 0.75 * 60 = 90)
    ;; Tip at x=145 (100 + 45), target center at x=200
    ;; Target left edge at x=180 (200-20), distance to edge = 35px < 90px
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 200 :y 100 :size :small :orientation :standing :angle 0}]
      (is (geo/within-range? attacker defender) "Should be in range")))

  (testing "target edge just outside range"
    ;; Tip at x=145, range = 90px, so max reach is x=235
    ;; For small defender (half-size=20), if center at x=260, left edge at x=240
    ;; Distance to edge = 240 - 145 = 95px > 90px (out of range)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 260 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (geo/within-range? attacker defender)) "Should be out of range")))

  (testing "small piece has shorter range"
    ;; Small attacker: tip-offset = 0.75 * 40 = 30px, range = 60px (height = 2 * 0.75 * 40)
    ;; Tip at x=130, max reach is x=190
    ;; Target center at x=220, left edge at x=200
    ;; Distance to edge = 200 - 130 = 70px > 60px (out of range)
    (let [attacker {:x 100 :y 100 :size :small :orientation :pointing :angle 0}
          defender {:x 220 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (geo/within-range? attacker defender)) "Small piece should be out of range")))

  (testing "target edge exactly at range still counts"
    ;; Large: tip-offset=45, range=90. Tip at x=145, max reach at x=235
    ;; Small defender (half=20) at x=255 has left edge at x=235 (exactly at range)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 255 :y 100 :size :small :orientation :standing :angle 0}]
      (is (geo/within-range? attacker defender) "Edge at exact range should count"))))
