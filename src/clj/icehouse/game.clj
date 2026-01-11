(ns icehouse.game
  (:require [icehouse.messages :as msg]
             [icehouse.utils :as utils]
             [icehouse.storage :as storage]
             [icehouse.schema :as schema]
             [icehouse.geometry :as geo]
             [icehouse.game-logic :as logic]
             [icehouse.constants :as const]
             [malli.core :as m]))

(defonce games (atom {}))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-game-state
  "Validate game state against schema, returns nil if valid or error message"
  [game]
  (if (m/validate schema/GameState game)
    nil
    (str "Invalid game state: " (m/explain schema/GameState game))))

;; =============================================================================
;; Game Constants (aliases to shared constants)
;; =============================================================================

(def play-area-width const/play-area-width)
(def play-area-height const/play-area-height)
(def icehouse-min-pieces const/icehouse-min-pieces)
(def initial-piece-counts const/initial-piece-counts)
(def game-duration-min-ms const/game-duration-min-ms)
(def game-duration-max-ms const/game-duration-max-ms)


;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn player-id-from-channel
  "Extract player ID from a WebSocket channel"
  [channel]
  (str (hash channel)))

;; =============================================================================
;; Board Geometry (using shared geometry module)
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

(defn random-game-duration
  "Generate a random game duration between min and max"
  []
  (+ game-duration-min-ms
     (rand-int (- game-duration-max-ms game-duration-min-ms))))

(defn create-game
  "Create a new game with the given options.
   Options: {:icehouse-rule bool, :timer-enabled bool, :timer-duration :random|ms}"
  ([room-id players]
   (create-game room-id players {}))
  ([room-id players options]
   {:post [(m/validate schema/GameState %)]}
   (let [now (System/currentTimeMillis)
         timer-enabled (get options :timer-enabled true)
         timer-duration (get options :timer-duration :random)
         duration (cond
                    (not timer-enabled) nil
                    (= timer-duration :random) (random-game-duration)
                    (number? timer-duration) timer-duration
                    :else (random-game-duration))]
     {:game-id (str (java.util.UUID/randomUUID))
      :room-id room-id
      :players (into {} (map (fn [p] [(:id p) {:name (:name p)
                                               :colour (:colour p)
                                               :pieces initial-piece-counts
                                               :captured []}])
                             players))
      :board []
      :moves []
      :options options  ;; Store options for later use (e.g., icehouse rule)
      :started-at now
      :ends-at (when duration (+ now duration))})))

(defn record-move!
  "Append a move to the game's move history"
  [room-id move]
  (when-let [game (get @games room-id)]
    (let [elapsed (- (System/currentTimeMillis) (:started-at game))]
      (swap! games update-in [room-id :moves] conj
             (assoc move
                    :timestamp (System/currentTimeMillis)
                    :elapsed-ms elapsed)))))

(defn make-error
  "Create a structured error response with code, message, and rule explanation."
  [code message rule]
  {:code code
   :message message
   :rule rule})

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
  (when-not (within-play-area? piece)
    (make-error msg/err-out-of-bounds
                "Piece must be placed within the play area"
                "All pieces must be placed entirely within the rectangular play area boundaries.")))

(defn- check-overlap
  "Validates that the piece does not overlap with existing pieces on the board.
   Returns nil if valid, or an error map if overlap detected."
  [piece board]
  (when (intersects-any-piece? piece board)
    (make-error msg/err-overlap
                "Piece would overlap with existing piece"
                "Pieces cannot overlap. Each piece must have its own space on the board.")))

(defn- check-attack-trajectory
  "Validates that an attacking piece is aimed at a valid target.
   Returns nil if valid or not attacking, or an error map if no valid target."
  [piece board is-attacking?]
  (when (and is-attacking? (not (has-potential-target? piece board)))
    (make-error msg/err-no-target
                "Attacking piece must be pointed at an opponent's standing piece"
                "Attacking (pointing) pieces must be aimed at an opponent's standing (defending) piece. You cannot attack your own pieces or other attacking pieces.")))

(defn- check-attack-range
  "Validates that the target is within the attacking piece's range.
   Returns nil if valid or not attacking, or an error map if out of range."
  [piece board is-attacking?]
  (when (and is-attacking? (not (has-target-in-range? piece board)))
    (make-error msg/err-out-of-range
                "Target is out of range"
                (str "Attack range depends on piece size: small=60px, medium=75px, large=90px. "
                     "The target must be within this distance from the attacking piece's tip."))))

(defn- check-line-of-sight
  "Validates that the attacking piece has a clear line of sight to its target.
   Returns nil if valid or not attacking, or an error map if blocked."
  [piece board is-attacking?]
  (when (and is-attacking? (has-blocked-target? piece board))
    (make-error msg/err-blocked
                "Another piece is blocking the line of attack"
                "Attacking pieces must have a clear line of sight to their target. Other pieces between the attacker and target block the attack.")))

(defn- run-placement-validations
  "Runs all placement validations in order, returning the first error or nil if all pass.
   Uses short-circuit evaluation - stops at first validation failure."
  [remaining size using-captured? piece board is-attacking?]
  (or (check-piece-availability remaining size using-captured?)
      (check-bounds piece)
      (check-overlap piece board)
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
   (let [player (get-in game [:players player-id])
         size (:size piece)
         remaining (if using-captured?
                     (utils/count-captured-by-size (:captured player) size)
                     (get-in player [:pieces size] 0))
         board (:board game)
         is-attacking? (geo/pointing? piece)
         ;; Ensure piece has player-id and colour for validation
         ;; For captured pieces, get the original colour; otherwise use player's colour
         piece-colour (if using-captured?
                        (let [cap-piece (utils/get-captured-piece (:captured player) size)]
                          (or (:colour cap-piece) (:colour player)))
                        (:colour player))
         piece-with-owner (assoc piece
                                 :player-id player-id
                                 :colour (or (:colour piece) piece-colour))]
     (run-placement-validations remaining size using-captured? piece-with-owner board is-attacking?))))

(defn valid-placement? [game player-id piece]
  (nil? (validate-placement game player-id piece)))

;; Core game logic functions moved to icehouse.game-logic (shared .cljc)
;; Use logic/find-piece-by-id, logic/attackers-by-target, logic/attack-strength,
;; logic/calculate-attack-stats, logic/calculate-iced-pieces, logic/calculate-over-ice

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

(defn all-pieces-placed? [game]
  "Check if all players have placed all their pieces"
  (every? (fn [[_ player]]
            (every? zero? (vals (:pieces player))))
          (:players game)))

(defn time-up? [game]
  "Check if the game time has expired"
  (when-let [ends-at (:ends-at game)]
    (>= (System/currentTimeMillis) ends-at)))

(defn game-over? [game]
  "Game ends when all pieces are placed OR time runs out"
  (or (all-pieces-placed? game)
      (time-up? game)))

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

(defn construct-piece-for-placement
  "Construct the piece map for placement, handling auto-targeting and colour assignment"
  [game player-id msg]
  {:post [(m/validate schema/Piece %)]}
  (let [player-colour (get-in game [:players player-id :colour])
        using-captured? (boolean (:captured msg))
        piece-size (keyword (:size msg))
        ;; For captured pieces, use the original colour; for regular pieces, use player's colour
        piece-colour (if using-captured?
                       (let [captured (get-in game [:players player-id :captured])
                             cap-piece (utils/get-captured-piece captured piece-size)]
                         (or (:colour cap-piece) player-colour))
                       player-colour)
        base-piece {:id (str (java.util.UUID/randomUUID))
                    :player-id player-id
                    :colour piece-colour
                    :x (:x msg)
                    :y (:y msg)
                    :size piece-size
                    :orientation (keyword (:orientation msg))
                    :angle (:angle msg)
                    :target-id (:target-id msg)}]
    ;; Auto-assign target-id for attacking pieces if not provided
    (if (and (geo/pointing? base-piece)
             (nil? (:target-id base-piece))
             game)
      (if-let [target (find-closest-target base-piece (:board game))]
        (assoc base-piece :target-id (:id target))
        base-piece)
      base-piece)))

(defn apply-placement!
  "Update game state with placed piece and decrement stash/captured counts.
   Returns {:success true} on success, or {:success false :error <structured-error>} on failure."
  [room-id player-id piece using-captured?]
  (let [result (atom {:success true})]
    (swap! games (fn [games-map]
                   (if-let [game (get games-map room-id)]
                     (let [new-game (-> game
                                        (update :board conj piece)
                                        (update :board refresh-all-targets)
                                        (as-> g
                                          (if using-captured?
                                            (update-in g [:players player-id :captured]
                                                       utils/remove-first-captured (:size piece))
                                            (update-in g [:players player-id :pieces (:size piece)] dec))))]
                       (if-let [error (validate-game-state new-game)]
                         (do
                           (println "ERROR: Invalid game state after placement:" error)
                           (reset! result {:success false
                                           :error (make-error msg/err-internal-state
                                                              "Failed to update game state after placement"
                                                              "An internal validation error occurred. The move was not applied.")})
                           games-map)
                         (assoc games-map room-id new-game)))
                     (do
                       (reset! result {:success false
                                       :error (make-error msg/err-invalid-game
                                                          "Game not found"
                                                          "The game session no longer exists.")})
                       games-map))))
    @result))

(defn handle-post-placement!
  "Handle side effects after placement: recording, broadcasting, game over check"
  [clients room-id player-id piece using-captured?]
  ;; Record the move for replay
  (record-move! room-id {:type :place-piece
                         :player-id player-id
                         :piece piece
                         :using-captured? using-captured?})
  (let [updated-game (get @games room-id)]
    (utils/broadcast-room! clients room-id
                           {:type msg/piece-placed
                            :piece piece
                            :game updated-game})
    (when (game-over? updated-game)
      (let [board (:board updated-game)
            options (get updated-game :options {})
            over-ice (logic/calculate-over-ice board)
            icehouse-players (calculate-icehouse-players board options)
            end-reason (if (all-pieces-placed? updated-game)
                         :all-pieces-placed
                         :time-up)
            record (build-game-record updated-game end-reason)]
        ;; Save the game record
        (storage/save-game-record! record)
        (utils/broadcast-room! clients room-id
                               {:type msg/game-over
                                :game-id (:game-id updated-game)
                                :scores (calculate-scores updated-game)
                                :over-ice over-ice
                                :icehouse-players (vec icehouse-players)})))))

(defn send-error!
  "Send a structured error to the client. Handles both old string errors and new structured errors."
  [channel error]
  (if (map? error)
    (utils/send-msg! channel {:type msg/error
                              :code (:code error)
                              :message (:message error)
                              :rule (:rule error)})
    (utils/send-msg! channel {:type msg/error :message (or error "Unknown error")})))

(defn handle-place-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        using-captured? (boolean (:captured msg))
        piece (when game (construct-piece-for-placement game player-id msg))
        validation-error (when piece (validate-placement game player-id piece using-captured?))]
    (if (and piece (nil? validation-error))
      (let [placement-result (apply-placement! room-id player-id piece using-captured?)]
        (if (:success placement-result)
          ;; Placement succeeded - proceed with side effects
          (let [updated-game (get @games room-id)
                final-piece (logic/find-piece-by-id (:board updated-game) (:id piece))]
            (if final-piece
              (handle-post-placement! clients room-id player-id final-piece using-captured?)
              ;; This shouldn't happen if apply-placement! succeeded, but handle it anyway
              (send-error! channel (make-error msg/err-internal-state
                                               "Internal error: piece not found after placement"
                                               "An unexpected error occurred. Please try again."))))
          ;; Placement failed - send the error from apply-placement!
          (send-error! channel (:error placement-result))))
      ;; Validation failed - send the validation error
      (send-error! channel (or validation-error (make-error msg/err-invalid-game
                                                             "Invalid game state"
                                                             "The game is not in a valid state for this action."))))))

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

(defn apply-capture!
  "Update game state to capture a piece.
   Returns {:success true} on success, or {:success false :error <structured-error>} on failure."
  [room-id player-id piece-id piece-size original-colour]
  (let [result (atom {:success true})]
    (swap! games (fn [games-map]
                   (if-let [g (get games-map room-id)]
                     (let [new-g (-> g
                                     (update :board (fn [board]
                                                      (-> (remove (utils/by-id piece-id) board)
                                                          vec
                                                          refresh-all-targets)))
                                     (update-in [:players player-id :captured]
                                                conj {:size piece-size :colour original-colour}))]
                       (if-let [err (validate-game-state new-g)]
                         (do
                           (println "ERROR: Invalid game state after capture:" err)
                           (reset! result {:success false
                                           :error (make-error msg/err-internal-state
                                                              "Failed to update game state after capture"
                                                              "An internal validation error occurred. The capture was not applied.")})
                           games-map)
                         (assoc games-map room-id new-g)))
                     (do
                       (reset! result {:success false
                                       :error (make-error msg/err-invalid-game
                                                          "Game not found"
                                                          "The game session no longer exists.")})
                       games-map))))
    @result))

(defn handle-capture-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        piece-id (:piece-id msg)
        validation-error (when game (validate-capture game player-id piece-id))]
    (if (and game (nil? validation-error))
      (let [piece (logic/find-piece-by-id (:board game) piece-id)
            piece-size (:size piece)
            original-owner (:player-id piece)
            original-colour (get-in game [:players original-owner :colour])
            capture-result (apply-capture! room-id player-id piece-id piece-size original-colour)]
        (if (:success capture-result)
          ;; Capture succeeded - proceed with side effects
          (do
            (record-move! room-id {:type :capture-piece
                                   :player-id player-id
                                   :piece-id piece-id
                                   :captured-piece {:size piece-size :colour original-colour}})
            (let [updated-game (get @games room-id)]
              (utils/broadcast-room! clients room-id
                                     {:type msg/piece-captured
                                      :piece-id piece-id
                                      :captured-by player-id
                                      :game updated-game})))
          ;; Capture failed - send the error
          (send-error! channel (:error capture-result))))
      ;; Validation failed - send the validation error
      (send-error! channel (or validation-error (make-error msg/err-invalid-game
                                                             "Invalid capture"
                                                             "The capture could not be completed."))))))

(defn all-players-finished?
  "Check if all players have pressed finish"
  [game]
  (let [player-ids (set (keys (:players game)))
        finished (set (or (:finished game) []))]
    (and (seq player-ids)
         (= player-ids finished))))

(defn end-game!
  "End the game, calculate scores, save record, and broadcast game-over"
  [clients room-id end-reason]
  (when-let [game (get @games room-id)]
    (let [board (:board game)
          options (get game :options {})
          over-ice (logic/calculate-over-ice board)
          icehouse-players (calculate-icehouse-players board options)
          record (build-game-record game end-reason)]
      (storage/save-game-record! record)
      (utils/broadcast-room! clients room-id
                             {:type msg/game-over
                              :game-id (:game-id game)
                              :scores (calculate-scores game)
                              :over-ice over-ice
                              :icehouse-players (vec icehouse-players)}))))

(defn apply-finish!
  "Update game state to mark a player as finished.
   Returns {:success true :already-finished? bool} on success,
   or {:success false :error <structured-error>} on failure."
  [room-id player-id]
  (let [result (atom {:success true :already-finished? false})]
    (swap! games (fn [games-map]
                   (if-let [g (get games-map room-id)]
                     (let [current-finished (or (:finished g) [])
                           already-finished? (some #{player-id} current-finished)]
                       (if already-finished?
                         (do
                           (reset! result {:success true :already-finished? true})
                           games-map)
                         (let [new-g (update g :finished (fnil conj []) player-id)]
                           (if-let [err (validate-game-state new-g)]
                             (do
                               (println "ERROR: Invalid game state after finish:" err)
                               (reset! result {:success false
                                               :error (make-error msg/err-internal-state
                                                                  "Failed to update game state"
                                                                  "An internal validation error occurred.")})
                               games-map)
                             (assoc games-map room-id new-g)))))
                     (do
                       (reset! result {:success false
                                       :error (make-error msg/err-invalid-game
                                                          "Game not found"
                                                          "The game session no longer exists.")})
                       games-map))))
    @result))

(defn handle-finish
  "Handle a player pressing the finish button"
  [clients channel _msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)]
    (if game
      (let [finish-result (apply-finish! room-id player-id)]
        (if (:success finish-result)
          ;; Finish succeeded - broadcast and check for game end
          (let [updated-game (get @games room-id)]
            (utils/broadcast-room! clients room-id
                                   {:type msg/player-finished
                                    :player-id player-id
                                    :game updated-game})
            (when (all-players-finished? updated-game)
              (end-game! clients room-id :all-players-finished)))
          ;; Finish failed - send the error
          (send-error! channel (:error finish-result))))
      ;; No game found
      (send-error! channel (make-error msg/err-invalid-game
                                        "No active game"
                                        "You must be in an active game to finish.")))))

(defn start-game!
  "Start a new game with the given players and options.
   Returns {:success true :game <game-state>} on success,
   or {:success false :error <structured-error>} on failure."
  ([room-id players]
   (start-game! room-id players {}))
  ([room-id players options]
   (let [new-game (create-game room-id players options)]
     (if-let [error (validate-game-state new-game)]
       (do
         (println "ERROR: Failed to start game due to invalid initial state:" error)
         {:success false
          :error (make-error msg/err-internal-state
                             "Failed to create game"
                             "An internal validation error prevented the game from starting.")})
       (do
         (swap! games assoc room-id new-game)
         {:success true :game new-game})))))

;; =============================================================================
;; Replay Handlers
;; =============================================================================

(defn handle-list-games
  "Send list of saved game record IDs to client"
  [channel]
  (utils/send-msg! channel
                   {:type msg/game-list
                    :games (storage/list-game-records)}))

(defn handle-load-game
  "Load and send a game record to client"
  [channel msg]
  (if-let [record (storage/load-game-record (:game-id msg))]
    (utils/send-msg! channel
                     {:type msg/game-record
                      :record record})
    (utils/send-msg! channel
                     {:type msg/error
                      :message "Game not found"})))

;; =============================================================================
;; Move Validation Handlers (for AI agents and pre-flight checks)
;; =============================================================================

(defn handle-validate-move
  "Validate a move without executing it. Returns validation result with detailed error info if invalid.
   Supports both placement and capture validation based on :action field.
   Request format for placement: {:type 'validate-move' :action 'place' :x :y :size :orientation :angle :captured}
   Request format for capture: {:type 'validate-move' :action 'capture' :piece-id '...'}"
  [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        action (keyword (or (:action msg) "place"))]
    (if-not game
      (utils/send-msg! channel {:type msg/validation-result
                                :valid false
                                :error (make-error msg/err-invalid-game
                                                   "No active game"
                                                   "You must be in an active game to validate moves.")})
      (case action
        :place
        (let [using-captured? (boolean (:captured msg))
              piece (construct-piece-for-placement game player-id msg)
              error (validate-placement game player-id piece using-captured?)]
          (utils/send-msg! channel {:type msg/validation-result
                                    :valid (nil? error)
                                    :action "place"
                                    :error error
                                    :piece-preview (when (nil? error)
                                                     {:id (:id piece)
                                                      :target-id (:target-id piece)})}))
        :capture
        (let [error (validate-capture game player-id (:piece-id msg))]
          (utils/send-msg! channel {:type msg/validation-result
                                    :valid (nil? error)
                                    :action "capture"
                                    :error error}))
        ;; Unknown action
        (utils/send-msg! channel {:type msg/validation-result
                                  :valid false
                                  :error (make-error msg/err-invalid-message
                                                     (str "Unknown action: " action)
                                                     "Valid actions are 'place' or 'capture'.")})))))

(defn generate-sample-positions
  "Generate sample positions across the play area for legal move checks.
   Returns a grid of positions spaced by step-size pixels."
  [step-size]
  (for [x (range 0 (inc play-area-width) step-size)
        y (range 0 (inc play-area-height) step-size)]
    [x y]))

(defn find-legal-placements
  "Find legal placement positions for a piece of given size and orientation.
   For standing pieces, samples a grid of positions.
   For attacking pieces, also varies the angle to find valid attack positions.
   Returns a list of valid placement specs."
  [game player-id size orientation using-captured? sample-step angle-step]
  (let [positions (generate-sample-positions sample-step)
        angles (if (= orientation :standing)
                 [0] ;; Standing pieces don't need angle variation
                 (range 0 360 angle-step))]
    (for [[x y] positions
          angle angles
          :let [piece {:x x :y y :size size :orientation orientation :angle angle}
                error (validate-placement game player-id piece using-captured?)]
          :when (nil? error)]
      {:x x :y y :angle angle})))

(defn handle-query-legal-moves
  "Query legal move positions for a given piece type.
   Returns sample valid positions where the piece could be placed.
   Request format: {:type 'query-legal-moves' :size 'small'|'medium'|'large'
                    :orientation 'standing'|'pointing' :captured true|false
                    :sample-step 50 :angle-step 15}
   sample-step controls position grid granularity (default 50px).
   angle-step controls angle variation for attacks (default 15 degrees)."
  [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        size (keyword (or (:size msg) "small"))
        orientation (keyword (or (:orientation msg) "standing"))
        using-captured? (boolean (:captured msg))
        sample-step (or (:sample-step msg) 50)
        angle-step (or (:angle-step msg) 15)]
    (if-not game
      (utils/send-msg! channel {:type msg/legal-moves
                                :valid-positions []
                                :error (make-error msg/err-invalid-game
                                                   "No active game"
                                                   "You must be in an active game to query legal moves.")})
      (let [player (get-in game [:players player-id])
            remaining (if using-captured?
                        (utils/count-captured-by-size (:captured player) size)
                        (get-in player [:pieces size] 0))]
        (if (not (pos? remaining))
          (utils/send-msg! channel {:type msg/legal-moves
                                    :valid-positions []
                                    :error (if using-captured?
                                             (make-error msg/err-no-captured
                                                         "No captured pieces of that size"
                                                         "You have no captured pieces of this size to place.")
                                             (make-error msg/err-no-pieces
                                                         (str "No " (name size) " pieces remaining")
                                                         "You have placed all pieces of this size."))})
          (let [legal-positions (find-legal-placements game player-id size orientation
                                                        using-captured? sample-step angle-step)]
            (utils/send-msg! channel {:type msg/legal-moves
                                      :size (name size)
                                      :orientation (name orientation)
                                      :captured using-captured?
                                      :valid-positions (vec (take 100 legal-positions)) ;; Limit to 100 samples
                                      :total-found (count legal-positions)
                                      :sample-step sample-step
                                      :angle-step angle-step})))))))
