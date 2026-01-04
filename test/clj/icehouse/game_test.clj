(ns icehouse.game-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.game :as game]))

(deftest initial-pieces-test
  (testing "initial-piece-counts has correct starting pieces"
    (let [pieces game/initial-piece-counts]
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

(deftest icehouse-rule-test
  (testing "icehouse threshold is 8 pieces (fewer than 8 unplayed = 8+ placed)"
    (is (= 8 game/icehouse-min-pieces)))

  (testing "player not in icehouse with exactly 7 pieces (edge case)"
    ;; 7 pieces placed means 8 unplayed, so icehouse rule doesn't apply
    (let [alice-pieces (for [i (range 6)]
                         {:id (str "alice-a" i) :player-id "alice"
                          :size :small :orientation :pointing :target-id "bob-d0"})
          alice-defender {:id "alice-d0" :player-id "alice" :size :small :orientation :standing}
          bob-attacker {:id "bob-a0" :player-id "bob" :size :large :orientation :pointing :target-id "alice-d0"}
          bob-defender {:id "bob-d0" :player-id "bob" :size :large :orientation :standing}
          board (concat alice-pieces [alice-defender bob-attacker bob-defender])
          icehouse-players (game/calculate-icehouse-players board)]
      ;; Alice has 7 pieces with her only defender iced, but not in icehouse (needs 8+)
      (is (= #{} icehouse-players))))

  (testing "player not in icehouse with fewer than 8 pieces"
    ;; Even if all defenders are iced, need 8+ pieces to be in icehouse
    (let [board [{:id "a1" :player-id "bob" :size :large :orientation :pointing :target-id "d1"}
                 {:id "d1" :player-id "alice" :size :small :orientation :standing}]
          icehouse-players (game/calculate-icehouse-players board)]
      (is (= #{} icehouse-players))))

  (testing "player in icehouse with 8+ pieces and all defenders iced"
    ;; Create a board where alice has 8 pieces and all her defenders are iced
    (let [;; Alice's pieces: 5 attackers + 3 defenders (8 total)
          alice-attackers (for [i (range 5)]
                            {:id (str "alice-a" i) :player-id "alice"
                             :size :small :orientation :pointing :target-id (str "bob-d" i)})
          alice-defenders (for [i (range 3)]
                            {:id (str "alice-d" i) :player-id "alice"
                             :size :small :orientation :standing})
          ;; Bob attacks all of Alice's defenders with large pieces
          bob-attackers (for [i (range 3)]
                          {:id (str "bob-a" i) :player-id "bob"
                           :size :large :orientation :pointing
                           :target-id (str "alice-d" i)})
          ;; Bob's defenders (for alice's attackers)
          bob-defenders (for [i (range 5)]
                          {:id (str "bob-d" i) :player-id "bob"
                           :size :large :orientation :standing})
          board (concat alice-attackers alice-defenders bob-attackers bob-defenders)
          icehouse-players (game/calculate-icehouse-players board)]
      ;; Alice has 8 pieces and all 3 defenders are iced by large attackers
      (is (contains? icehouse-players "alice"))
      ;; Bob is not in icehouse (has un-iced defenders)
      (is (not (contains? icehouse-players "bob")))))

  (testing "player in icehouse gets zero score"
    (let [;; Alice: 8 pieces with all defenders iced
          alice-attackers (for [i (range 5)]
                            {:id (str "alice-a" i) :player-id "alice"
                             :size :small :orientation :pointing :target-id (str "bob-d" i)})
          alice-defenders (for [i (range 3)]
                            {:id (str "alice-d" i) :player-id "alice"
                             :size :large :orientation :standing})  ; Large defenders
          ;; Bob attacks with enough to ice all alice defenders
          bob-attackers (for [i (range 3)]
                          {:id (str "bob-a" i) :player-id "bob"
                           :size :large :orientation :pointing
                           :target-id (str "alice-d" i)})
          bob-extra-attackers (for [i (range 3)]
                                {:id (str "bob-a-extra" i) :player-id "bob"
                                 :size :medium :orientation :pointing
                                 :target-id (str "alice-d" i)})
          bob-defenders (for [i (range 5)]
                          {:id (str "bob-d" i) :player-id "bob"
                           :size :small :orientation :standing})
          board (concat alice-attackers alice-defenders bob-attackers bob-extra-attackers bob-defenders)
          game {:board (vec board)}
          scores (game/calculate-scores game)]
      ;; Alice is in icehouse, gets 0
      (is (= 0 (get scores "alice")))
      ;; Bob gets points from un-iced defenders (5 small = 5 pips, but some may be iced by alice)
      (is (pos? (get scores "bob" 0))))))

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
          ;; small = 40px, so half = 20
          ;; vertices should be at (80,80), (120,80), (120,120), (80,120)
          expected [[80 80] [120 80] [120 120] [80 120]]]
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
    ;; small = 40px, so two pieces 45px apart (center to center) should not overlap
    ;; 40/2 + 40/2 = 40px needed for edge-to-edge, so 45px apart means 5px gap
    (let [piece1 {:x 100 :y 100 :size :small :orientation :standing :angle 0}
          piece2 {:x 145 :y 100 :size :small :orientation :standing :angle 0}]
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
    ;; Small piece has range of 60px (height = 2 * 0.75 * 40)
    ;; Small attacker tip at x=130 (100 + 30), max reach x=190
    ;; Place defender at x=220 (left edge at 200), distance = 70px > 60px
    (let [defender {:id "d1" :player-id "p2" :x 220 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          ;; Attacker at x=100, pointing right (angle = 0) at defender out of range
          attacker {:x 100 :y 100 :size :small :orientation :pointing :angle 0}]
      (is (= "Target is out of range"
             (game/validate-placement game "p1" attacker)))))

  (testing "valid attack in trajectory and in range"
    ;; Large piece has range of 90px (height), tip extends 45px from center
    ;; Position attacker so tip doesn't overlap defender but is in range
    (let [defender {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          ;; Large attacker at x=138, tip at x=183 (before defender left edge at 180)
          ;; Distance to defender edge = 180 - 183 = -3 (tip past edge actually)
          ;; Let's use x=130: tip at 175, defender edge at 180, distance = 5px < 60px range
          attacker {:x 130 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (nil? (game/validate-placement game "p1" attacker)))))

  (testing "cannot attack own piece"
    ;; Attacker pointing at own piece should fail with trajectory error
    ;; Use large attacker positioned so it doesn't overlap
    (let [own-piece {:id "d1" :player-id "p1" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [own-piece]}
          attacker {:x 130 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (= "Attacking piece must be pointed at an opponent's piece"
             (game/validate-placement game "p1" attacker)))))

  (testing "cannot attack pointing piece (must target standing)"
    ;; Attacker pointing at enemy's attacking piece should fail
    ;; Enemy pointing up (angle = -PI/2) so its back doesn't face attacker
    (let [enemy-attacker {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :pointing :angle (- (/ Math/PI 2))}
          game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [enemy-attacker]}
          attacker {:x 130 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (= "Attacking piece must be pointed at an opponent's piece"
             (game/validate-placement game "p1" attacker))))))

(deftest in-front-of-test
  (testing "ray from tip intersects target at various angles"
    ;; Large attacker at (100, 100) pointing right (angle=0) at small defender at (200, 100)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 200 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Should hit defender directly ahead")))

  (testing "ray misses target that is off-angle"
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}  ;; pointing right
          defender {:x 100 :y 200 :size :small :orientation :standing :angle 0}] ;; below
      (is (not (game/in-front-of? attacker defender)) "Should miss defender below")))

  (testing "close-range attack - tip near target edge"
    ;; Large piece: tip-offset = 0.75 * 60 = 45px from center
    ;; At x=100, tip is at x=145
    ;; Small defender at x=180 has left edge at x=160 (180 - 20)
    ;; Ray should hit the defender
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 180 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Close-range attack should hit")))

  (testing "very close attack - tip almost touching target"
    ;; Tip at x=145, defender left edge at x=150 (170-20)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 170 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Very close attack should hit")))

  (testing "diagonal attack"
    ;; Attacker pointing at 45 degrees toward defender
    (let [angle (/ Math/PI 4)  ;; 45 degrees
          attacker {:x 100 :y 100 :size :large :orientation :pointing :angle angle}
          ;; Defender at 45 degrees, ~100px away
          defender {:x 170 :y 170 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Diagonal attack should hit")))

  (testing "up-left attack (user bug scenario)"
    ;; User reported: large red attacking piece pointing up-left at small teal defender
    ;; Up-left in screen coords = negative x, negative y = angle around -135 degrees
    (let [angle (* -0.75 Math/PI)  ;; -135 degrees = up-left
          ;; Attacker at center of play area
          attacker {:x 400 :y 300 :size :large :orientation :pointing :angle angle}
          ;; Defender up and to the left
          ;; For large piece: tip-offset = 45px in direction of angle
          ;; cos(-135°) ≈ -0.707, sin(-135°) ≈ -0.707
          ;; Tip at approximately (400 - 32, 300 - 32) = (368, 268)
          ;; Place defender so center is within 60px of tip
          defender {:x 320 :y 220 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Up-left attack should hit defender")
      (is (game/within-range? attacker defender) "Defender should be in range")))

  (testing "up-left attack - close range"
    ;; Same scenario but with pieces closer together
    (let [angle (* -0.75 Math/PI)  ;; -135 degrees = up-left
          attacker {:x 400 :y 300 :size :large :orientation :pointing :angle angle}
          ;; Place defender very close (tip almost touching)
          ;; Tip at (368, 268), place defender center at (340, 240)
          ;; Distance from tip to defender center ≈ sqrt((368-340)^2 + (268-240)^2) ≈ 40px
          defender {:x 340 :y 240 :size :small :orientation :standing :angle 0}]
      (is (game/in-front-of? attacker defender) "Close up-left attack should hit")
      (is (game/within-range? attacker defender) "Close defender should be in range"))))

(deftest within-range-test
  (testing "target edge within range"
    ;; Large attacker has range of 90px from tip (height = 2 * 0.75 * 60 = 90)
    ;; Tip at x=145 (100 + 45), target center at x=200
    ;; Target left edge at x=180 (200-20), distance to edge = 35px < 90px
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 200 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/within-range? attacker defender) "Should be in range")))

  (testing "target edge just outside range"
    ;; Tip at x=145, range = 90px, so max reach is x=235
    ;; For small defender (half-size=20), if center at x=260, left edge at x=240
    ;; Distance to edge = 240 - 145 = 95px > 90px (out of range)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 260 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (game/within-range? attacker defender)) "Should be out of range")))

  (testing "small piece has shorter range"
    ;; Small attacker: tip-offset = 0.75 * 40 = 30px, range = 60px (height = 2 * 0.75 * 40)
    ;; Tip at x=130, max reach is x=190
    ;; Target center at x=220, left edge at x=200
    ;; Distance to edge = 200 - 130 = 70px > 60px (out of range)
    (let [attacker {:x 100 :y 100 :size :small :orientation :pointing :angle 0}
          defender {:x 220 :y 100 :size :small :orientation :standing :angle 0}]
      (is (not (game/within-range? attacker defender)) "Small piece should be out of range")))

  (testing "target edge exactly at range still counts"
    ;; Large: tip-offset=45, range=90. Tip at x=145, max reach at x=235
    ;; Small defender (half=20) at x=255 has left edge at x=235 (exactly at range)
    (let [attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}
          defender {:x 255 :y 100 :size :small :orientation :standing :angle 0}]
      (is (game/within-range? attacker defender) "Edge at exact range should count"))))

(deftest line-of-sight-test
  "Test that attacks fail when line of sight is blocked"
  (testing "clear line of sight allows attack"
    (let [defender {:id "d1" :player-id "bob" :x 300 :y 100 :size :small :orientation :standing :angle 0}
          attacker {:id "a1" :player-id "alice" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          board [defender]]
      (is (game/clear-line-of-sight? attacker defender board))))

  (testing "piece in the way blocks line of sight"
    (let [defender {:id "d1" :player-id "bob" :x 300 :y 100 :size :small :orientation :standing :angle 0}
          blocker {:id "b1" :player-id "alice" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          attacker {:id "a1" :player-id "alice" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          board [defender blocker]]
      (is (not (game/clear-line-of-sight? attacker defender board)))))

  (testing "enemy piece in the way also blocks"
    (let [defender {:id "d1" :player-id "bob" :x 300 :y 100 :size :small :orientation :standing :angle 0}
          blocker {:id "b1" :player-id "bob" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          attacker {:id "a1" :player-id "alice" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          board [defender blocker]]
      (is (not (game/clear-line-of-sight? attacker defender board)))))

  (testing "piece to the side does not block"
    (let [defender {:id "d1" :player-id "bob" :x 300 :y 100 :size :small :orientation :standing :angle 0}
          bystander {:id "b1" :player-id "alice" :x 200 :y 200 :size :small :orientation :standing :angle 0}
          attacker {:id "a1" :player-id "alice" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          board [defender bystander]]
      (is (game/clear-line-of-sight? attacker defender board))))

  (testing "piece behind target does not block"
    (let [defender {:id "d1" :player-id "bob" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          behind {:id "b1" :player-id "bob" :x 300 :y 100 :size :small :orientation :standing :angle 0}
          attacker {:id "a1" :player-id "alice" :x 100 :y 100 :size :large :orientation :pointing :angle 0}
          board [defender behind]]
      (is (game/clear-line-of-sight? attacker defender board)))))

(deftest blocked-attack-validation-test
  "Test that validate-placement returns correct error for blocked attacks"
  (testing "attack blocked by piece returns specific error"
    ;; Large attacker at x=50, tip at x=95 (50 + 45)
    ;; Large range = 90px, so max reach is x=185
    ;; Blocker at x=120 (left edge at 100), defender at x=160 (left edge at 140)
    ;; Both in range, but blocker is hit first
    (let [defender {:id "d1" :player-id "bob" :x 160 :y 100 :size :small :orientation :standing :angle 0}
          blocker {:id "b1" :player-id "alice" :x 120 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender blocker]}
          attacker {:x 50 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (= "Another piece is blocking the line of attack"
             (game/validate-placement game "alice" attacker)))))

  (testing "valid attack with no blockers succeeds"
    (let [defender {:id "d1" :player-id "bob" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender]}
          attacker {:x 100 :y 100 :size :large :orientation :pointing :angle 0}]
      (is (nil? (game/validate-placement game "alice" attacker))))))

(deftest attack-placement-integration-test
  "Test full attack validation flow as would happen in actual gameplay"
  (testing "valid attack passes all checks"
    (let [;; Bob's defender is already on the board
          defender {:id "bob-d1" :player-id "bob" :x 300 :y 200
                    :size :small :orientation :standing :angle 0}
          ;; Alice also has a piece on board (required for attack to be enabled)
          alice-piece {:id "alice-d1" :player-id "alice" :x 500 :y 400
                       :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender alice-piece]}
          ;; Alice attacks Bob's defender from the right, pointing left
          ;; Angle = PI (pointing left)
          ;; Large attacker: tip-offset = 45, range = 90 (height)
          ;; If attacker center is at x=400, tip is at x=400-45=355
          ;; Defender center at x=300, distance = 355-300=55 < 90 (in range)
          attacker {:x 400 :y 200 :size :large :orientation :pointing :angle Math/PI}
          error (game/validate-placement game "alice" attacker)]
      (is (nil? error) (str "Attack should be valid, got error: " error))))

  (testing "attack fails when pieces overlap"
    (let [defender {:id "bob-d1" :player-id "bob" :x 300 :y 200
                    :size :small :orientation :standing :angle 0}
          alice-piece {:id "alice-d1" :player-id "alice" :x 500 :y 400
                       :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender alice-piece]}
          ;; Place attacker so close that pieces overlap
          ;; Large piece: half-width = 45 (tip side), half-base = 30
          ;; At x=340, large piece extends from x=295 to x=385 (pointing left)
          ;; Defender at x=300 with half-size=20 extends from x=280 to x=320
          ;; These would overlap
          attacker {:x 340 :y 200 :size :large :orientation :pointing :angle Math/PI}
          error (game/validate-placement game "alice" attacker)]
      (is (= "Piece would overlap with existing piece" error))))

  (testing "attack fails when target is out of range"
    (let [defender {:id "bob-d1" :player-id "bob" :x 200 :y 200
                    :size :small :orientation :standing :angle 0}
          alice-piece {:id "alice-d1" :player-id "alice" :x 500 :y 400
                       :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender alice-piece]}
          ;; Large attacker far from defender
          ;; Tip at x=455, defender at x=200, distance=255 >> 90
          attacker {:x 500 :y 200 :size :large :orientation :pointing :angle Math/PI}
          error (game/validate-placement game "alice" attacker)]
      (is (= "Target is out of range" error))))

  (testing "attack with acute angle relative to target"
    ;; User bug scenario: attack at an angle, not straight at target
    (let [defender {:id "bob-d1" :player-id "bob" :x 300 :y 200
                    :size :small :orientation :standing :angle 0}
          ;; Place Alice's own piece far from the attacker to avoid overlap
          alice-piece {:id "alice-d1" :player-id "alice" :x 700 :y 500
                       :size :small :orientation :standing :angle 0}
          game {:players {"alice" {:pieces {:small 5 :medium 5 :large 5}}}
                :board [defender alice-piece]}
          ;; Attack from below-right, pointing up-left at -135 degrees
          ;; For large piece: tip-offset = 45, range = 90
          ;; To be in range, tip must be within 90px of defender edge (center at 300, 200)
          ;; At angle -135deg, direction is (-0.707, -0.707)
          ;; Place attacker so tip is ~40px from defender:
          ;; tip = defender_center + 40 * (0.707, 0.707) = (300+28, 200+28) = (328, 228)
          ;; attacker_center = tip - tip_offset * direction
          ;;                 = (328, 228) - 45 * (-0.707, -0.707)
          ;;                 = (328+32, 228+32) = (360, 260)
          angle (* -0.75 Math/PI)  ;; -135 degrees
          attacker {:x 360 :y 260 :size :large :orientation :pointing :angle angle}
          error (game/validate-placement game "alice" attacker)]
      (is (nil? error) (str "Angled attack should be valid, got error: " error)))))

(deftest bounds-validation-test
  (testing "piece within play area is valid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          piece {:x 500 :y 375 :size :small :orientation :standing :angle 0}]
      (is (nil? (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside left edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Small piece is 40x40, so at x=15 the left edge would be at x=-5
          piece {:x 15 :y 375 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside top edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Small piece at y=15, top edge at y=-5
          piece {:x 500 :y 15 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside right edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Play area is now 1000px wide. At x=985, right edge at 985+20=1005 > 1000
          piece {:x 985 :y 375 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece partially outside bottom edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Play area is now 750px tall. At y=735, bottom edge at 735+20=755 > 750
          piece {:x 500 :y 735 :size :small :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "large piece near edge is invalid"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Large piece is 60x60, so at x=25 left edge would be at x=-5
          piece {:x 25 :y 375 :size :large :orientation :standing :angle 0}]
      (is (= "Piece must be placed within the play area"
             (game/validate-placement game "p1" piece)))))

  (testing "piece at max valid position (bottom-right corner)"
    (let [game {:players {"p1" {:pieces {:small 5 :medium 5 :large 5}}}
                :board []}
          ;; Small piece (40x40): max valid x = 1000-20=980, max valid y = 750-20=730
          piece {:x 980 :y 730 :size :small :orientation :standing :angle 0}]
      (is (nil? (game/validate-placement game "p1" piece))))))

;; Captured pieces tests

(deftest count-captured-by-size-test
  (testing "empty captured list"
    (is (= 0 (game/count-captured-by-size [] :small))))

  (testing "counts matching sizes"
    (let [captured [{:size :small :colour "#ff0000"}
                    {:size :medium :colour "#00ff00"}
                    {:size :small :colour "#0000ff"}
                    {:size :large :colour "#ff0000"}]]
      (is (= 2 (game/count-captured-by-size captured :small)))
      (is (= 1 (game/count-captured-by-size captured :medium)))
      (is (= 1 (game/count-captured-by-size captured :large)))))

  (testing "returns 0 for missing size"
    (let [captured [{:size :small :colour "#ff0000"}]]
      (is (= 0 (game/count-captured-by-size captured :large))))))

(deftest remove-first-captured-test
  (testing "removes first matching piece"
    (let [captured [{:size :small :colour "#ff0000"}
                    {:size :medium :colour "#00ff00"}
                    {:size :small :colour "#0000ff"}]
          result (game/remove-first-captured captured :small)]
      (is (= 2 (count result)))
      (is (= {:size :medium :colour "#00ff00"} (first result)))
      (is (= {:size :small :colour "#0000ff"} (second result)))))

  (testing "returns unchanged if size not found"
    (let [captured [{:size :small :colour "#ff0000"}]
          result (game/remove-first-captured captured :large)]
      (is (= captured result))))

  (testing "empty list returns empty"
    (is (= [] (game/remove-first-captured [] :small))))

  (testing "removes only first occurrence"
    (let [captured [{:size :large :colour "#111"}
                    {:size :small :colour "#222"}
                    {:size :small :colour "#333"}
                    {:size :small :colour "#444"}]
          result (game/remove-first-captured captured :small)]
      ;; Should remove the one with colour #222
      (is (= 3 (count result)))
      (is (= "#111" (:colour (first result))))
      (is (= "#333" (:colour (second result))))
      (is (= "#444" (:colour (nth result 2)))))))

;; Projection overlap tests (touching behavior)

(deftest projections-overlap-strict-test
  (testing "overlapping projections return true"
    (is (game/projections-overlap? [0 10] [5 15]))
    (is (game/projections-overlap? [5 15] [0 10])))

  (testing "touching projections return false (strict inequality)"
    ;; [0,10] and [10,20] touch at exactly 10 but don't overlap
    (is (not (game/projections-overlap? [0 10] [10 20])))
    (is (not (game/projections-overlap? [10 20] [0 10]))))

  (testing "separate projections return false"
    (is (not (game/projections-overlap? [0 10] [15 25])))
    (is (not (game/projections-overlap? [15 25] [0 10]))))

  (testing "contained projection returns true"
    (is (game/projections-overlap? [0 20] [5 15]))
    (is (game/projections-overlap? [5 15] [0 20]))))

;; Player piece tracking tests

(deftest pieces-placed-by-player-test
  (testing "counts all pieces by a player"
    (let [board [{:id "1" :player-id "alice" :size :small}
                 {:id "2" :player-id "bob" :size :medium}
                 {:id "3" :player-id "alice" :size :large}
                 {:id "4" :player-id "alice" :size :small}]]
      (is (= 3 (game/pieces-placed-by-player board "alice")))
      (is (= 1 (game/pieces-placed-by-player board "bob")))))

  (testing "returns 0 for unknown player"
    (let [board [{:id "1" :player-id "alice" :size :small}]]
      (is (= 0 (game/pieces-placed-by-player board "charlie"))))))

(deftest player-defenders-test
  (testing "returns only standing pieces"
    (let [board [{:id "1" :player-id "alice" :orientation :standing :size :small}
                 {:id "2" :player-id "alice" :orientation :pointing :size :medium}
                 {:id "3" :player-id "alice" :orientation :standing :size :large}]]
      (is (= 2 (count (game/player-defenders board "alice"))))))

  (testing "filters by player"
    (let [board [{:id "1" :player-id "alice" :orientation :standing :size :small}
                 {:id "2" :player-id "bob" :orientation :standing :size :medium}]]
      (is (= 1 (count (game/player-defenders board "alice"))))
      (is (= 1 (count (game/player-defenders board "bob")))))))

;; Constants verification tests

(deftest constants-test
  (testing "pips values are correct"
    (is (= 1 (:small game/pips)))
    (is (= 2 (:medium game/pips)))
    (is (= 3 (:large game/pips))))

  (testing "piece sizes maintain 3:2 ratio progression"
    (is (= 40 (:small game/piece-sizes)))
    (is (= 50 (:medium game/piece-sizes)))
    (is (= 60 (:large game/piece-sizes))))

  (testing "play area dimensions"
    (is (= 1000 game/play-area-width))
    (is (= 750 game/play-area-height)))

  (testing "icehouse requires 8 pieces minimum"
    (is (= 8 game/icehouse-min-pieces))))

;; =============================================================================
;; Game Recording Tests
;; =============================================================================

(deftest create-game-with-moves-test
  (testing "create-game initializes with game-id and empty moves"
    (let [players [{:id "p1" :name "Alice" :colour "#ff0000"}]
          game (game/create-game "room-1" players)]
      (is (string? (:game-id game)))
      (is (= [] (:moves game))))))

(deftest build-game-record-test
  (testing "build-game-record creates a complete record"
    (let [game {:game-id "test-game-id"
                :room-id "room-1"
                :players {"p1" {:name "Alice" :colour "#ff0000" :pieces {:small 2 :medium 3 :large 4}}
                          "p2" {:name "Bob" :colour "#0000ff" :pieces {:small 3 :medium 3 :large 3}}}
                :board [{:id "piece-1" :player-id "p1" :orientation :standing :size :large}
                        {:id "piece-2" :player-id "p2" :orientation :standing :size :medium}]
                :moves [{:type :place-piece :player-id "p1" :elapsed-ms 1000}
                        {:type :place-piece :player-id "p2" :elapsed-ms 2000}]
                :started-at 1000000
                :ends-at 1300000}
          record (game/build-game-record game :time-up)]
      (is (= 1 (:version record)))
      (is (= "test-game-id" (:game-id record)))
      (is (= "room-1" (:room-id record)))
      (is (= :time-up (:end-reason record)))
      (is (= 2 (count (:moves record))))
      (is (= 2 (count (:final-board record))))
      ;; Players should only have name and colour, not pieces
      (is (= #{"Alice" "Bob"} (set (map :name (vals (:players record))))))
      (is (nil? (get-in record [:players "p1" :pieces])))
      ;; Scores should be calculated
      (is (map? (:final-scores record)))
      (is (vector? (:icehouse-players record))))))

(deftest build-game-record-winner-test
  (testing "build-game-record determines winner from scores"
    (let [game {:game-id "test-game"
                :room-id "room-1"
                :players {"p1" {:name "Alice" :colour "#ff0000"}
                          "p2" {:name "Bob" :colour "#0000ff"}}
                :board [{:id "1" :player-id "p1" :orientation :standing :size :large}  ; 3 pts
                        {:id "2" :player-id "p2" :orientation :standing :size :small}] ; 1 pt
                :moves []
                :started-at 1000000
                :ends-at 1300000}
          record (game/build-game-record game :all-pieces-placed)]
      (is (= "p1" (:winner record)))
      (is (= :all-pieces-placed (:end-reason record))))))

(deftest refresh-all-targets-test
  (testing "targets are updated when a closer defender is added"
    (let [d1 {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          a1 {:id "a1" :player-id "p1" :x 100 :y 100 :size :small :orientation :pointing :angle 0} ;; Points right towards d1
          board [d1 a1]
          refreshed (game/refresh-all-targets board)
          a1-refreshed (first (filter #(= (:id %) "a1") refreshed))]
      (is (= "d1" (:target-id a1-refreshed)))

      ;; Add a closer defender d2 at x=150
      (let [d2 {:id "d2" :player-id "p2" :x 150 :y 100 :size :small :orientation :standing :angle 0}
            board-with-d2 [d1 d2 a1]
            refreshed-with-d2 (game/refresh-all-targets board-with-d2)
            a1-refreshed-with-d2 (first (filter #(= (:id %) "a1") refreshed-with-d2))]
        (is (= "d2" (:target-id a1-refreshed-with-d2))))))

  (testing "targets are cleared when a target is removed"
    (let [d1 {:id "d1" :player-id "p2" :x 200 :y 100 :size :small :orientation :standing :angle 0}
          a1 {:id "a1" :player-id "p1" :x 100 :y 100 :size :small :orientation :pointing :angle 0 :target-id "d1"}
          board [a1] ;; d1 is gone
          refreshed (game/refresh-all-targets board)
          a1-refreshed (first (filter #(= (:id %) "a1") refreshed))]
      (is (nil? (:target-id a1-refreshed))))))
