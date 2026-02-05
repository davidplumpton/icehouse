(ns icehouse.game.validators
  "Placement and capture validation logic extracted from game handlers."
  (:require [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.geometry :as geo]
            [icehouse.game-logic :as logic]
            [icehouse.game-targeting :as targeting]
            [icehouse.game-rules :as rules]
            [icehouse.game.errors :refer [make-error]]
            [icehouse.constants :as const]))

(defn- check-piece-availability
  "Validates that the player has pieces of the requested size available.
   Returns nil if valid, or an error map if no pieces remain."
  [remaining size using-captured?]
  (when-not (pos? remaining)
    (if using-captured?
      (make-error msg/err-no-captured
                  "No captured pieces of that size remaining"
                  "You can only place captured pieces that you have captured from opponents.")
      (make-error msg/err-no-pieces
                  (str "No " (name size) " pieces remaining")
                  "Each player has a limited stash of pieces (5 small, 5 medium, 5 large). Once placed, pieces cannot be moved."))))

(defn- check-bounds
  "Validates that the piece is within the play area boundaries.
   Returns nil if valid, or an error map if out of bounds."
  [piece]
  (when-not (targeting/within-play-area? piece)
    (make-error msg/err-out-of-bounds
                "Piece must be placed within the play area"
                "All pieces must be placed entirely within the rectangular play area boundaries.")))

(defn- check-overlap
  "Validates that the piece does not overlap with existing pieces on the board.
   Returns nil if valid, or an error map if overlap detected."
  [piece board]
  (when (targeting/intersects-any-piece? piece board)
    (make-error msg/err-overlap
                "Piece would overlap with existing piece"
                "Pieces cannot overlap. Each piece must have its own space on the board.")))

(defn- check-attack-trajectory
  "Validates that an attacking piece is aimed at a valid target.
   Returns nil if valid or not attacking, or an error map if no valid target."
  [piece board is-attacking?]
  (when (and is-attacking? (not (targeting/has-potential-target? piece board)))
    (make-error msg/err-no-target
                "Attacking piece must be pointed at an opponent's standing piece"
                "Attacking (pointing) pieces must be aimed at an opponent's standing (defending) piece. You cannot attack your own pieces or other attacking pieces.")))

(defn- check-attack-range
  "Validates that the target is within the attacking piece's range.
   Returns nil if valid or not attacking, or an error map if out of range."
  [piece board is-attacking?]
  (when (and is-attacking? (not (targeting/has-target-in-range? piece board)))
    (let [ranges (into {} (for [size [:small :medium :large]]
                            [size (* 2 const/tip-offset-ratio (get const/piece-sizes size))]))]
      (make-error msg/err-out-of-range
                  "Target is out of range"
                  (str "Attack range depends on piece size: "
                       "small=" (int (:small ranges)) "px, "
                       "medium=" (int (:medium ranges)) "px, "
                       "large=" (int (:large ranges)) "px. "
                       "The target must be within this distance from the attacking piece's tip.")))))

(defn- check-line-of-sight
  "Validates that the attacking piece has a clear line of sight to its target.
   Returns nil if valid or not attacking, or an error map if blocked."
  [piece board is-attacking?]
  (when (and is-attacking? (targeting/has-blocked-target? piece board))
    (make-error msg/err-blocked
                "Another piece is blocking the line of attack"
                "Attacking pieces must have a clear line of sight to their target. Other pieces between the attacker and target block the attack.")))

(defn- check-first-two-defensive
  "Validates that the player's first two pieces are defensive (standing).
   Returns nil if valid, or an error map if trying to attack too early."
  [player-id board is-attacking? using-captured?]
  (when (and is-attacking? (not using-captured?))
    (let [placed-count (rules/pieces-placed-by-player board player-id)]
      (when (< placed-count 2)
        (make-error msg/err-first-defensive
                    "First two pieces must be defensive (standing up)"
                    "In Icehouse, your first two pieces must be placed as defenders. This ensures everyone has a base to defend before attacks begin.")))))

(defn- check-player-icehoused
  "Validates that an icehoused player is not trying to place regular pieces.
   Returns nil if valid (player not icehoused or using captured), or an error map."
  [game player-id using-captured?]
  (when-not using-captured?
    (let [options (get game :options {})
          icehouse-players (rules/calculate-icehouse-players (:board game) options)]
      (when (contains? icehouse-players player-id)
        (make-error msg/err-player-icehoused
                    "You are in the Icehouse - can only play captured pieces"
                    "When all your defenders are iced, you cannot place regular pieces. You can still capture opponent pieces and play captured pieces.")))))

(defn- run-placement-validations
  "Runs all placement validations in order, returning the first error or nil if all pass.
   Uses short-circuit evaluation - stops at first validation failure."
  [remaining size using-captured? piece board is-attacking?]
  (or (check-piece-availability remaining size using-captured?)
      (check-bounds piece)
      (check-overlap piece board)
      (check-first-two-defensive (:player-id piece) board is-attacking? using-captured?)
      (check-attack-trajectory piece board is-attacking?)
      (check-attack-range piece board is-attacking?)
      (check-line-of-sight piece board is-attacking?)))

(defn validate-placement
  "Validate piece placement, returns nil if valid or structured error map if invalid.
   Error map contains :code (for programmatic handling), :message, and :rule (game rule explanation).
   If using-captured? is true, checks captured pieces instead of regular pieces."
  ([game player-id piece]
   (validate-placement game player-id piece false))
  ([game player-id piece using-captured?]
   (or (check-player-icehoused game player-id using-captured?)
       (let [player (get-in game [:players player-id])
             size (:size piece)
             remaining (if using-captured?
                         (utils/count-captured-by-size (:captured player) size)
                         (get-in player [:pieces size] 0))
             board (:board game)
             is-attacking? (geo/pointing? piece)
             piece-colour (if using-captured?
                            (let [cap-piece (utils/get-captured-piece (:captured player) size)]
                              (or (:colour cap-piece) (:colour player)))
                            (:colour player))
             piece-with-owner (assoc piece
                                     :player-id player-id
                                     :colour (or (:colour piece) piece-colour))]
         (run-placement-validations remaining size using-captured? piece-with-owner board is-attacking?)))))

(defn valid-placement?
  "Returns true if the placement is valid, false otherwise."
  [game player-id piece]
  (nil? (validate-placement game player-id piece)))

(defn validate-capture
  "Validate that a piece can be captured by the player.
   Returns nil if valid, or structured error map if invalid."
  [game player-id piece-id]
  (let [board (:board game)
        piece (logic/find-piece-by-id board piece-id)
        over-ice (logic/calculate-over-ice board)]
    (cond
      (nil? piece)
      (make-error msg/err-piece-not-found
                  "Piece not found"
                  "The piece you tried to capture no longer exists on the board.")

      (not (geo/pointing? piece))
      (make-error msg/err-not-attacking
                  "Can only capture attacking pieces"
                  "Only attacking (pointing) pieces can be captured. Standing (defending) pieces cannot be captured.")

      (not (:target-id piece))
      (make-error msg/err-no-target-assigned
                  "Piece has no target"
                  "This attacking piece has no valid target assigned, so it cannot be captured.")

      (nil? (get over-ice (:target-id piece)))
      (make-error msg/err-not-over-iced
                  "Target is not over-iced"
                  "A defender must be 'over-iced' (total attacking pips > defender pips) before its attackers can be captured. Current attack strength is insufficient.")

      (not= (utils/normalize-player-id (:defender-owner (get over-ice (:target-id piece))))
            (utils/normalize-player-id player-id))
      (make-error msg/err-not-your-defender
                  "You can only capture attackers targeting your own pieces"
                  "You can only capture attacking pieces that are targeting YOUR defenders. You cannot capture pieces attacking other players.")

      (> (geo/piece-pips piece) (:excess (get over-ice (:target-id piece))))
      (make-error msg/err-exceeds-excess
                  (str "Attacker's pip value (" (geo/piece-pips piece) ") exceeds capture limit ("
                       (:excess (get over-ice (:target-id piece))) ")")
                  "The captured piece's pip value cannot exceed the 'excess' over-ice amount. Excess = total attacking pips - defender pips.")

      :else nil)))
