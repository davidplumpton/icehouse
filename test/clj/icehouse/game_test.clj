(ns icehouse.game-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.game :as game]))

(deftest initial-pieces-test
  (testing "initial-pieces returns correct starting pieces"
    (let [pieces (game/initial-pieces)]
      (is (= 5 (:small pieces)))
      (is (= 5 (:medium pieces)))
      (is (= 5 (:large pieces))))))

(deftest create-game-test
  (testing "create-game initializes game state correctly"
    (let [players [{:id "p1" :name "Alice" :colour "#ff0000"}
                   {:id "p2" :name "Bob" :colour "#0000ff"}]
          game (game/create-game "room-1" players)]
      (is (= "room-1" (:room-id game)))
      (is (= [] (:board game)))
      (is (number? (:started-at game)))
      (is (= 2 (count (:players game))))
      (is (= "Alice" (get-in game [:players "p1" :name])))
      (is (= "#0000ff" (get-in game [:players "p2" :colour])))
      (is (= 5 (get-in game [:players "p1" :pieces :small]))))))

(deftest valid-placement-test
  (let [game {:players {"p1" {:pieces {:small 3 :medium 0 :large 5}}}}]
    (testing "valid placement when pieces remain"
      (is (game/valid-placement? game "p1" {:size :small}))
      (is (game/valid-placement? game "p1" {:size :large})))

    (testing "invalid placement when no pieces remain"
      (is (not (game/valid-placement? game "p1" {:size :medium}))))

    (testing "invalid placement for unknown player"
      (is (not (game/valid-placement? game "p2" {:size :small}))))))

(deftest calculate-attacks-test
  (testing "no attacks when no pointing pieces"
    (let [board [{:id "piece1" :orientation :standing}
                 {:id "piece2" :orientation :standing}]]
      (is (= #{} (game/calculate-attacks board)))))

  (testing "pointing pieces with targets create attacks"
    (let [board [{:id "piece1" :orientation :pointing :target-id "piece2"}
                 {:id "piece2" :orientation :standing}]]
      (is (= #{"piece2"} (game/calculate-attacks board)))))

  (testing "pointing pieces without targets don't create attacks"
    (let [board [{:id "piece1" :orientation :pointing :target-id nil}
                 {:id "piece2" :orientation :standing}]]
      (is (= #{} (game/calculate-attacks board)))))

  (testing "multiple attacks"
    (let [board [{:id "a1" :orientation :pointing :target-id "d1"}
                 {:id "a2" :orientation :pointing :target-id "d2"}
                 {:id "d1" :orientation :standing}
                 {:id "d2" :orientation :standing}]]
      (is (= #{"d1" "d2"} (game/calculate-attacks board))))))

(deftest calculate-scores-test
  (testing "unattacked pieces score points"
    (let [game {:board [{:id "p1" :player-id "alice" :size :small}
                        {:id "p2" :player-id "alice" :size :medium}
                        {:id "p3" :player-id "bob" :size :large}]}
          scores (game/calculate-scores game)]
      (is (= 3 (get scores "alice")))  ; 1 + 2 = 3
      (is (= 3 (get scores "bob")))))  ; 3

  (testing "attacked pieces don't score"
    (let [game {:board [{:id "p1" :player-id "alice" :size :large :orientation :standing}
                        {:id "p2" :player-id "bob" :size :small :orientation :pointing :target-id "p1"}]}
          scores (game/calculate-scores game)]
      (is (= 0 (get scores "alice" 0)))  ; attacked, no points
      (is (= 1 (get scores "bob")))))    ; attacker scores

  (testing "empty board returns empty scores"
    (let [game {:board []}]
      (is (= {} (game/calculate-scores game))))))

(deftest game-over-test
  (testing "game not over when pieces remain"
    (let [game {:players {"p1" {:pieces {:small 1 :medium 0 :large 0}}
                          "p2" {:pieces {:small 0 :medium 0 :large 0}}}}]
      (is (not (game/game-over? game)))))

  (testing "game over when all pieces placed"
    (let [game {:players {"p1" {:pieces {:small 0 :medium 0 :large 0}}
                          "p2" {:pieces {:small 0 :medium 0 :large 0}}}}]
      (is (game/game-over? game))))

  (testing "single player game over"
    (let [game {:players {"p1" {:pieces {:small 0 :medium 0 :large 0}}}}]
      (is (game/game-over? game)))))

;; Intersection detection tests

(deftest rotate-point-test
  (testing "no rotation returns same point"
    (let [[x y] (game/rotate-point [10 0] 0)]
      (is (< (Math/abs (- x 10)) 0.001))
      (is (< (Math/abs y) 0.001))))

  (testing "90 degree rotation"
    (let [[x y] (game/rotate-point [10 0] (/ Math/PI 2))]
      (is (< (Math/abs x) 0.001))
      (is (< (Math/abs (- y 10)) 0.001))))

  (testing "180 degree rotation"
    (let [[x y] (game/rotate-point [10 0] Math/PI)]
      (is (< (Math/abs (- x -10)) 0.001))
      (is (< (Math/abs y) 0.001)))))

(deftest piece-vertices-test
  (testing "standing piece returns 4 vertices (square)"
    (let [piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          verts (game/piece-vertices piece)]
      (is (= 4 (count verts)))))

  (testing "pointing piece returns 3 vertices (triangle)"
    (let [piece {:x 100 :y 100 :size :small :orientation :pointing :angle 0}
          verts (game/piece-vertices piece)]
      (is (= 3 (count verts)))))

  (testing "standing piece vertices are centered correctly"
    (let [piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          verts (game/piece-vertices piece)
          ;; small = 30px, so half = 15
          ;; vertices should be at (85,85), (115,85), (115,115), (85,115)
          expected [[85 85] [115 85] [115 115] [85 115]]]
      (doseq [[expected-v actual-v] (map vector expected verts)]
        (is (< (Math/abs (- (first expected-v) (first actual-v))) 0.001))
        (is (< (Math/abs (- (second expected-v) (second actual-v))) 0.001))))))

(deftest pieces-intersect-test
  (testing "overlapping pieces intersect"
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 110 :y 110 :size :small :orientation :standing :angle 0}]
      (is (game/pieces-intersect? piece1 piece2))))

  (testing "same position pieces intersect"
    (let [piece1 {:x 100 :y 100 :size :medium :orientation :standing :angle 0}
          piece2 {:x 100 :y 100 :size :small :orientation :pointing :angle 0}]
      (is (game/pieces-intersect? piece1 piece2))))

  (testing "non-overlapping pieces don't intersect"
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 200 :y 200 :size :small :orientation :standing :angle 0}]
      (is (not (game/pieces-intersect? piece1 piece2)))))

  (testing "pieces just touching edges don't intersect (gap between them)"
    ;; small = 30px, so two pieces 35px apart (center to center) should not overlap
    ;; 30/2 + 30/2 = 30px needed for edge-to-edge, so 35px apart means 5px gap
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 135 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (game/pieces-intersect? piece1 piece2)))))

  (testing "rotated pieces intersection"
    ;; Two pieces at same location but different rotations still intersect
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 100 :y 100 :size :small :orientation :standing :angle (/ Math/PI 4)}]
      (is (game/pieces-intersect? piece1 piece2))))

  (testing "pointing pieces at distance don't intersect"
    (let [piece1 {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          piece2 {:x 300 :y 100 :size :large :orientation :pointing :angle Math/PI}]
      (is (not (game/pieces-intersect? piece1 piece2))))))

(deftest intersects-any-piece-test
  (testing "empty board has no intersections"
    (let [piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (game/intersects-any-piece? piece [])))))

  (testing "piece intersects when overlapping board piece"
    (let [board-piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          new-piece {:x 110 :y 110 :size :small :orientation :standing :angle 0}]
      (is (game/intersects-any-piece? new-piece [board-piece]))))

  (testing "piece doesn't intersect when far from board pieces"
    (let [board [{:x 100 :y 100 :size :small :orientation :standing :angle 0}
                 {:x 200 :y 200 :size :medium :orientation :pointing :angle 0}]
          new-piece {:x 400 :y 400 :size :large :orientation :standing :angle 0}]
      (is (not (game/intersects-any-piece? new-piece board))))))

(deftest valid-placement-intersection-test
  (testing "valid placement on empty board"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/valid-placement? game "p1" piece))))

  (testing "invalid placement when overlapping existing piece"
    (let [existing {:id "existing" :x 100 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [existing]}
          new-piece {:x 110 :y 110 :size :small :orientation :standing :angle 0}]
      (is (not (game/valid-placement? game "p1" new-piece)))))

  (testing "valid placement when not overlapping"
    (let [existing {:id "existing" :x 100 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [existing]}
          new-piece {:x 200 :y 200 :size :small :orientation :standing :angle 0}]
      (is (game/valid-placement? game "p1" new-piece))))

  (testing "invalid when no pieces AND overlapping"
    (let [existing {:id "existing" :x 100 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 0 :medium 5 :large 5}}}
                :board [existing]}
          new-piece {:x 100 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (game/valid-placement? game "p1" new-piece))))))
