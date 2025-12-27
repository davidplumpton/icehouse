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

(deftest calculate-iced-pieces-test
  (testing "no icing when no pointing pieces"
    (let [board [{:id "piece1" :orientation :standing :size :small}
                 {:id "piece2" :orientation :standing :size :medium}]]
      (is (= #{} (game/calculate-iced-pieces board)))))

  (testing "single attacker must exceed defender pips to ice"
    ;; Small attacker (1 pip) vs Small defender (1 pip) - no ice (must exceed)
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :small}
                 {:id "d1" :orientation :standing :size :small}]]
      (is (= #{} (game/calculate-iced-pieces board))))
    ;; Medium attacker (2 pips) vs Small defender (1 pip) - iced!
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :medium}
                 {:id "d1" :orientation :standing :size :small}]]
      (is (= #{"d1"} (game/calculate-iced-pieces board)))))

  (testing "multiple attackers combine pips"
    ;; Two small attackers (2 pips total) vs Medium defender (2 pips) - no ice
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :small}
                 {:id "a2" :orientation :pointing :target-id "d1" :size :small}
                 {:id "d1" :orientation :standing :size :medium}]]
      (is (= #{} (game/calculate-iced-pieces board))))
    ;; Three small attackers (3 pips total) vs Medium defender (2 pips) - iced!
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :small}
                 {:id "a2" :orientation :pointing :target-id "d1" :size :small}
                 {:id "a3" :orientation :pointing :target-id "d1" :size :small}
                 {:id "d1" :orientation :standing :size :medium}]]
      (is (= #{"d1"} (game/calculate-iced-pieces board)))))

  (testing "large defender needs 4+ pips to ice"
    ;; Large attacker (3 pips) vs Large defender (3 pips) - no ice
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :large}
                 {:id "d1" :orientation :standing :size :large}]]
      (is (= #{} (game/calculate-iced-pieces board))))
    ;; Large + Small attackers (4 pips) vs Large defender (3 pips) - iced!
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :large}
                 {:id "a2" :orientation :pointing :target-id "d1" :size :small}
                 {:id "d1" :orientation :standing :size :large}]]
      (is (= #{"d1"} (game/calculate-iced-pieces board)))))

  (testing "pointing pieces without targets don't contribute"
    (let [board [{:id "a1" :orientation :pointing :target-id nil :size :large}
                 {:id "d1" :orientation :standing :size :small}]]
      (is (= #{} (game/calculate-iced-pieces board))))))

(deftest calculate-over-ice-test
  (testing "no over-ice when exactly enough pips to ice"
    ;; Medium attacker (2 pips) vs Small defender (1 pip) - iced with 2 pips, needs 2, no excess
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :medium :player-id "bob"}
                 {:id "d1" :orientation :standing :size :small :player-id "alice"}]]
      (is (= {} (game/calculate-over-ice board)))))

  (testing "over-ice when more pips than needed"
    ;; Large attacker (3 pips) vs Small defender (1 pip) - needs 2, has 3, excess = 1
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :large :player-id "bob"}
                 {:id "d1" :orientation :standing :size :small :player-id "alice"}]
          over-ice (game/calculate-over-ice board)]
      (is (= 1 (get-in over-ice ["d1" :excess])))
      (is (= "alice" (get-in over-ice ["d1" :defender-owner])))))

  (testing "large over-ice with multiple attackers"
    ;; 2 Large attackers (6 pips) vs Medium defender (2 pips) - needs 3, has 6, excess = 3
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :large :player-id "bob"}
                 {:id "a2" :orientation :pointing :target-id "d1" :size :large :player-id "carol"}
                 {:id "d1" :orientation :standing :size :medium :player-id "alice"}]
          over-ice (game/calculate-over-ice board)]
      (is (= 3 (get-in over-ice ["d1" :excess])))))

  (testing "capturable-attackers returns valid options"
    ;; 2 Large attackers (6 pips) vs Small defender (1 pip) - needs 2, has 6, excess = 4
    ;; Can capture up to 4 pips worth of attackers
    (let [board [{:id "a1" :orientation :pointing :target-id "d1" :size :large :player-id "bob"}
                 {:id "a2" :orientation :pointing :target-id "d1" :size :large :player-id "carol"}
                 {:id "d1" :orientation :standing :size :small :player-id "alice"}]
          over-ice (game/calculate-over-ice board)
          capturable (game/capturable-attackers (get over-ice "d1"))]
      ;; With 4 excess, can capture a large (3 pips) attacker
      (is (= 2 (count capturable)))
      (is (every? #(= 3 (:pips %)) capturable)))))

(deftest calculate-scores-test
  (testing "standing pieces score points"
    (let [game {:board [{:id "p1" :player-id "alice" :size :small :orientation :standing}
                        {:id "p2" :player-id "alice" :size :medium :orientation :standing}
                        {:id "p3" :player-id "bob" :size :large :orientation :standing}]}
          scores (game/calculate-scores game)]
      (is (= 3 (get scores "alice")))  ; 1 + 2 = 3
      (is (= 3 (get scores "bob")))))  ; 3

  (testing "attacking pieces don't score"
    (let [game {:board [{:id "a1" :player-id "bob" :size :medium :orientation :pointing :target-id "d1"}
                        {:id "d1" :player-id "alice" :size :small :orientation :standing}]}
          scores (game/calculate-scores game)]
      ;; Medium (2 pips) > Small (1 pip), so defender is iced
      (is (= 0 (get scores "alice" 0)))  ; iced, no points
      (is (= 0 (get scores "bob" 0)))))  ; attacker is pointing, doesn't score

  (testing "weak attack doesn't ice defender"
    (let [game {:board [{:id "a1" :player-id "bob" :size :small :orientation :pointing :target-id "d1"}
                        {:id "d1" :player-id "alice" :size :large :orientation :standing}]}
          scores (game/calculate-scores game)]
      ;; Small (1 pip) <= Large (3 pips), defender NOT iced
      (is (= 3 (get scores "alice")))    ; not iced, scores 3
      (is (= 0 (get scores "bob" 0)))))  ; attacker doesn't score

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

(deftest attack-validation-error-messages-test
  (testing "attack not pointed at any enemy piece"
    ;; Attacker pointing away from the defender (angle = PI, pointing left)
    ;; Defender is to the right at x=200
    (let [defender {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          ;; Attacker at x=100, pointing left (angle = PI), defender is to the right
          attacker {:x 100 :y 100 :size :small :orientation :pointing :angle Math/PI}]
      (is (= "Attacking piece must be pointed at an opponent's piece"
             (game/validate-placement game "p1" attacker)))))

  (testing "attack pointed at enemy but out of range"
    ;; Small piece has range of 30px
    ;; Place defender 100px away (well out of range) but in trajectory
    (let [defender {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          ;; Attacker at x=100, pointing right (angle = 0) at defender 100px away
          attacker {:x 100 :y 100 :size :small :orientation :pointing :angle 0}]
      (is (= "Target is out of range"
             (game/validate-placement game "p1" attacker)))))

  (testing "valid attack in trajectory and in range"
    ;; Large piece has range of 70px, tip extends 52.5px from center
    ;; Position attacker so tip doesn't overlap defender but is in range
    (let [defender {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          ;; Large attacker at x=131, tip at x=183.5 (before defender left edge at 185)
          ;; Distance = 69px < 70px range
          attacker {:x 131 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (nil? (game/validate-placement game "p1" attacker)))))

  (testing "cannot attack own piece"
    ;; Attacker pointing at own piece should fail with trajectory error
    ;; Use large attacker positioned so it doesn't overlap
    (let [own-piece {:id "d1" :player-id "p1" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [own-piece]}
          attacker {:x 131 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (= "Attacking piece must be pointed at an opponent's piece"
             (game/validate-placement game "p1" attacker)))))

  (testing "cannot attack pointing piece (must target standing)"
    ;; Attacker pointing at enemy's attacking piece should fail
    ;; Enemy pointing up (angle = -PI/2) so its back doesn't face attacker
    (let [enemy-attacker {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :pointing :angle (- (/ Math/PI 2))}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [enemy-attacker]}
          attacker {:x 131 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (= "Attacking piece must be pointed at an opponent's piece"
             (game/validate-placement game "p1" attacker))))))

(deftest bounds-validation-test
  (testing "piece within play area is valid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          piece {:x 400 :y 300 :size :small :orientation :standing :angle 0}]
      (is (nil? (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside left edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Small piece is 30x30, so at x=10 the left edge would be at x=-5
          piece {:x 10 :y 300 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside top edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          piece {:x 400 :y 10 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside right edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; At x=795, right edge would be at 795+15=810 which exceeds 800
          piece {:x 795 :y 300 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside bottom edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          piece {:x 400 :y 595 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "large piece near edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Large piece is 70x70, so at x=30 left edge would be at x=-5
          piece {:x 30 :y 300 :size :large :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece))))))
