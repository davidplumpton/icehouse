(ns icehouse.game-targeting
  "Board geometry and target finding functions.
   Handles intersection detection, attack trajectories, and target validation."
  (:require [icehouse.geometry :as geo]
            [icehouse.utils :as utils]
            [icehouse.constants :as const]))

(def play-area-width const/play-area-width)
(def play-area-height const/play-area-height)

;; =============================================================================
;; Board Geometry
;; =============================================================================

(defn intersects-any-piece?
  "Check if a piece intersects any piece on the board"
  [piece board]
  (some #(geo/pieces-intersect? piece %) board))

(defn within-play-area?
  "Check if all vertices of a piece are within the play area bounds.
   Returns true if piece has no position (for backwards compatibility with tests)."
  [piece]
  (if (and (:x piece) (:y piece))
    (let [vertices (geo/piece-vertices piece)]
      (every? (fn [[x y]]
                (and (>= x 0) (<= x play-area-width)
                     (>= y 0) (<= y play-area-height)))
              vertices))
    true))

;; =============================================================================
;; Target Detection
;; =============================================================================

(defn potential-target?
  "Check if target could be attacked based on trajectory only (ignoring range).
   Returns true if the target is a different-coloured standing piece in the attack path.
   Uses colour-based validation so captured pieces attack based on their original colour.
   Falls back to player-id comparison if colours aren't set."
  [attacker target]
  (let [is-opponent (if (and (:colour attacker) (:colour target))
                      (not= (:colour target) (:colour attacker))
                      (not= (utils/normalize-player-id (:player-id target))
                            (utils/normalize-player-id (:player-id attacker))))
        is-standing (geo/standing? target)]
    (if (and is-opponent is-standing)
      (geo/in-front-of? attacker target)
      false)))

(defn valid-target?
  "Check if target is a valid attack target for the attacker.
   Per Icehouse rules, can only target standing (defending) opponent pieces.
   Also checks that no other pieces are blocking the line of sight."
  [attacker target board]
  (and (potential-target? attacker target)
       (geo/within-range? attacker target)
       (geo/clear-line-of-sight? attacker target board)))

(defn find-potential-targets
  "Find all targets in the attack trajectory (ignoring range)"
  [attacker board]
  (filter #(potential-target? attacker %) board))

(defn has-potential-target?
  "Check if an attacking piece has at least one target in its trajectory"
  [piece board]
  (seq (find-potential-targets piece board)))

(defn find-valid-targets
  "Find all valid targets for an attacking piece"
  [attacker board]
  (filter #(valid-target? attacker % board) board))

(defn has-valid-target?
  "Check if an attacking piece has at least one valid target"
  [piece board]
  (seq (find-valid-targets piece board)))

(defn find-targets-in-range
  "Find all targets that are in trajectory and range (ignoring line of sight)"
  [attacker board]
  (filter #(and (potential-target? attacker %)
                (geo/within-range? attacker %))
          board))

(defn has-target-in-range?
  "Check if an attacking piece has at least one target in range (ignoring line of sight)"
  [piece board]
  (seq (find-targets-in-range piece board)))

(defn has-blocked-target?
  "Check if an attacking piece has a target in range that is blocked by another piece"
  [piece board]
  (let [in-range (find-targets-in-range piece board)]
    (and (seq in-range)
         (not-any? #(geo/clear-line-of-sight? piece % board) in-range))))

(defn find-closest-target
  "Find the closest valid target for an attacking piece"
  [piece board]
  (let [targets (find-valid-targets piece board)]
    (when (seq targets)
      (->> targets
           (map (fn [t] {:target t
                         :dist (geo/distance (geo/piece-center piece) (geo/piece-center t))}))
           (sort-by :dist)
           first
           :target))))

(defn refresh-all-targets
  "Recalculate target-id for all pointing pieces on the board"
  [board]
  (mapv (fn [piece]
          (if (geo/pointing? piece)
            (let [target (find-closest-target piece board)]
              (assoc piece :target-id (:id target)))
            piece))
        board))
