(ns icehouse.game-rules
  "Game rules, icehouse detection, and scoring.
   Contains functions for determining game state, calculating scores, and checking win conditions."
  (:require [icehouse.game-logic :as logic]
            [icehouse.geometry :as geo]
            [icehouse.utils :as utils]
            [icehouse.schema :as schema]
            [icehouse.constants :as const]
            [malli.core :as m]))

(def icehouse-min-pieces const/icehouse-min-pieces)

;; =============================================================================
;; Capturing Logic
;; =============================================================================

(defn capturable-attackers
  "Given over-ice info for a defender, returns attackers that could be captured.
   An attacker can be captured if its pip value <= remaining excess."
  [over-ice-info]
  (let [{:keys [excess attackers]} over-ice-info]
    ;; Sort by pip value ascending so smaller pieces can be captured first
    (->> attackers
         (map (fn [a] {:attacker a :pips (geo/piece-pips a)}))
         (filter #(<= (:pips %) excess))
         (sort-by :pips))))

;; =============================================================================
;; Player Piece Tracking
;; =============================================================================

(defn pieces-placed-by-player
  "Count pieces placed by a player"
  [board player-id]
  (count (filter (utils/by-player-id player-id) board)))

(defn player-defenders
  "Get all standing (defender) pieces for a player"
  [board player-id]
  (filter #(and ((utils/by-player-id player-id) %)
                (geo/standing? %))
          board))

;; =============================================================================
;; Icehouse Detection
;; =============================================================================

(defn in-icehouse?
  "Check if a player is 'in the Icehouse' - all defenders iced after playing 8+ pieces.
   Per official rules, this is an instant loss with zero score."
  [board iced-set player-id]
  (let [pieces-placed (pieces-placed-by-player board player-id)
        defenders (player-defenders board player-id)]
    (and (>= pieces-placed icehouse-min-pieces)            ;; Must have placed at least 8 pieces
         (seq defenders)                                    ;; Must have at least one defender
         (every? #(contains? iced-set (:id %)) defenders)))) ;; All defenders are iced

(defn calculate-icehouse-players
  "Returns set of player-ids who are 'in the Icehouse'.
   If icehouse-rule option is false, returns empty set."
  ([board]
   (calculate-icehouse-players board {}))
  ([board options]
   (if (false? (get options :icehouse-rule))
     #{}  ;; Icehouse rule disabled
     (let [iced (logic/calculate-iced-pieces board)
           player-ids (distinct (map #(utils/normalize-player-id (:player-id %)) board))]
       (set (filter #(in-icehouse? board iced %) player-ids))))))

(defn successful-attackers
  "Returns set of piece IDs that are attacking pieces which successfully ice a defender."
  [board]
  (let [stats (logic/calculate-attack-stats board)
        iced (logic/calculate-iced-pieces board)]
    (reduce-kv
     (fn [successful target-id {:keys [attackers]}]
       (if (contains? iced target-id)
         ;; All attackers on this target contributed to a successful ice
         (into successful (map :id attackers))
         successful))
     #{}
     stats)))

;; =============================================================================
;; Scoring
;; =============================================================================

(defn- piece-scores?
  "Returns true if a piece should score points.
   A piece scores if it's either a successful attacker or an un-iced defender."
  [piece successful-attacks iced]
  (or (contains? successful-attacks (:id piece))
      (and (geo/standing? piece)
           (not (contains? iced (:id piece))))))

(defn- calculate-piece-score
  "Calculate the score contribution for a single piece.
   Returns the points to add for this piece (0 if it doesn't score)."
  [piece player-id icehouse-players successful-attacks iced]
  (cond
    ;; Players in the Icehouse get zero for all pieces
    (contains? icehouse-players player-id)
    0

    ;; Successful attacks and un-iced defenders score points
    (piece-scores? piece successful-attacks iced)
    (geo/piece-pips piece)

    ;; Other pieces don't score
    :else
    0))

(defn- init-player-scores
  "Initialize all players with 0 score."
  [game]
  (let [all-players (keys (:players game))]
    (if (seq all-players)
      (zipmap (map utils/normalize-player-id all-players) (repeat 0))
      {})))

(defn calculate-scores
  "Calculate final scores for all players.
   Players score points for:
   - Un-iced standing (defending) pieces
   - Attacking pieces that successfully ice a defender"
  [game]
  {:post [(m/validate schema/Scores %)]}
  (let [board (:board game)
        options (get game :options {})
        iced (logic/calculate-iced-pieces board)
        successful-attacks (successful-attackers board)
        icehouse-players (calculate-icehouse-players board options)]
    (reduce
     (fn [scores piece]
       (let [player-id (utils/normalize-player-id (:player-id piece))
             points (calculate-piece-score piece player-id icehouse-players
                                           successful-attacks iced)]
         (update scores player-id (fnil + 0) points)))
     (init-player-scores game)
     board)))

;; =============================================================================
;; Game State Checks
;; =============================================================================

(defn player-pieces-placed?
  "Check if a specific player has placed all their regular pieces"
  [game player-id]
  (let [player (get-in game [:players player-id])]
    (every? zero? (vals (:pieces player)))))

(defn all-pieces-placed?
  "Check if all players have placed all their pieces"
  [game]
  (every? (fn [[_ player]]
            (every? zero? (vals (:pieces player))))
          (:players game)))

(defn all-active-players-finished?
  "Check if all non-icehoused players have placed all their regular pieces.
   Icehoused players are excluded since they can't place regular pieces."
  [game]
  (let [options (get game :options {})
        icehouse-players (calculate-icehouse-players (:board game) options)
        active-player-ids (remove #(contains? icehouse-players %) (keys (:players game)))]
    (every? #(player-pieces-placed? game %) active-player-ids)))

(defn time-up?
  "Check if the game time has expired"
  [game]
  (when-let [ends-at (:ends-at game)]
    (>= (System/currentTimeMillis) ends-at)))

(defn any-player-in-icehouse?
  "Check if any player has been put in the icehouse (all defenders iced after 8+ pieces)"
  [game]
  (let [options (get game :options {})]
    (seq (calculate-icehouse-players (:board game) options))))

(defn game-over?
  "Game ends when all active (non-icehoused) players have placed their pieces, or time runs out.
   Icehoused players can continue capturing but can't place regular pieces."
  [game]
  (or (all-active-players-finished? game)
      (time-up? game)))

(defn all-players-finished?
  "Check if all players have pressed finish"
  [game]
  (let [player-ids (set (keys (:players game)))
        finished (set (or (:finished game) []))]
    (and (seq player-ids)
         (= player-ids finished))))

;; =============================================================================
;; Game Recording
;; =============================================================================

(defn build-game-record
  "Build a complete game record from current game state for persistence"
  [game end-reason]
  {:post [(m/validate schema/GameRecord %)]}
  (let [now (System/currentTimeMillis)
        options (get game :options {})
        scores (calculate-scores game)
        icehouse-players (calculate-icehouse-players (:board game) options)
        winner (when (seq scores)
                 (key (apply max-key val scores)))]
    {:version 1
     :game-id (:game-id game)
     :room-id (:room-id game)
     :players (into {}
                    (map (fn [[pid pdata]]
                           [pid (select-keys pdata [:name :colour])])
                         (:players game)))
     :started-at (:started-at game)
     :ended-at now
     :duration-ms (- now (:started-at game))
     :end-reason end-reason
     :moves (:moves game)
     :final-board (:board game)
     :final-scores scores
     :icehouse-players (vec icehouse-players)
     :winner winner}))
