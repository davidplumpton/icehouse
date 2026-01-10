(ns icehouse.game-logic
  "Shared game logic functions for Icehouse.
   Used by both backend (Clojure) and frontend (ClojureScript).
   Contains core game rules for attacking, icing, and capturing."
  (:require
   [icehouse.geometry :as geo]
   [icehouse.utils :as utils]))

;; =============================================================================
;; Board Query Functions
;; =============================================================================

(defn find-piece-by-id
  "Find a piece on the board by its ID"
  [board id]
  (first (filter (utils/by-id id) board)))

;; =============================================================================
;; Attack Calculations
;; =============================================================================

(defn attackers-by-target
  "Returns a map of target-id -> list of attackers targeting that piece"
  [board]
  (let [pointing-pieces (filter #(and (geo/pointing? %)
                                      (:target-id %))
                                board)]
    (group-by :target-id pointing-pieces)))

(defn attack-strength
  "Sum of pip values of all attackers targeting a piece"
  [attackers]
  (reduce + (map geo/piece-pips attackers)))

(defn calculate-attack-stats
  "Calculate attacker statistics for all targets on the board.
   Returns map of target-id -> {:defender piece :attackers [...] :attacker-pips sum}"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [stats target-id attackers]
       (if-let [defender (find-piece-by-id board target-id)]
         (assoc stats target-id {:defender defender
                                 :attackers attackers
                                 :attacker-pips (attack-strength attackers)})
         stats))
     {}
     attacks)))

;; =============================================================================
;; Icing Rules
;; =============================================================================

(defn calculate-iced-pieces
  "Returns set of piece IDs that are successfully iced.
   Per Icehouse rules: a defender is iced when total attacker pips > defender pips"
  [board]
  (let [stats (calculate-attack-stats board)]
    (reduce-kv
     (fn [iced target-id {:keys [defender attacker-pips]}]
       (if (> attacker-pips (geo/piece-pips defender))
         (conj iced target-id)
         iced))
     #{}
     stats)))

(defn calculate-over-ice
  "Returns a map of defender-id -> {:excess pips :attackers [...] :defender-owner player-id}
   for each over-iced defender. Excess = attacker-pips - (defender-pips + 1)"
  [board]
  (let [stats (calculate-attack-stats board)]
    (reduce-kv
     (fn [result target-id {:keys [defender attackers attacker-pips]}]
       (let [defender-pips (geo/piece-pips defender)
             ;; Minimum to ice is defender-pips + 1, excess is anything beyond that
             excess (- attacker-pips (+ defender-pips 1))]
         (if (and (> attacker-pips defender-pips) (pos? excess))
           (assoc result target-id {:excess excess
                                    :attackers attackers
                                    :defender-owner (utils/normalize-player-id (:player-id defender))})
           result)))
     {}
     stats)))

;; =============================================================================
;; Capture Rules
;; =============================================================================

(defn capturable-piece?
  "Check if a piece can be captured by the given player.
   Returns true if piece is an attacker in an over-iced situation where
   the player owns the defender and the attacker's pips <= excess."
  [piece player-id board]
  (when (and piece (geo/pointing? piece))
    (let [over-ice (calculate-over-ice board)
          target-id (:target-id piece)]
      (when-let [info (get over-ice target-id)]
        (and (= (utils/normalize-player-id (:defender-owner info))
                (utils/normalize-player-id player-id))
             (<= (geo/piece-pips piece) (:excess info)))))))
