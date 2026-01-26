(ns icehouse.game-handlers
  "WebSocket message handlers and validation functions.
   Contains all handler functions for game actions and move validation."
  (:require [clojure.set]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.storage :as storage]
            [icehouse.schema :as schema]
            [icehouse.geometry :as geo]
            [icehouse.game-logic :as logic]
            [icehouse.game-state :as state]
            [icehouse.game-targeting :as targeting]
            [icehouse.game-rules :as rules]
            [icehouse.constants :as const]
            [malli.core :as m]))

;; =============================================================================
;; Error Helpers
;; =============================================================================

(defn make-error
  "Create a structured error response with code, message, and rule explanation."
  [code message rule]
  {:code code
   :message message
   :rule rule})

(defn send-error!
  "Send a structured error to the client. Handles both old string errors and new structured errors."
  [channel error]
  (if (map? error)
    (utils/send-msg! channel {:type msg/error
                              :code (:code error)
                              :message (:message error)
                              :rule (:rule error)})
    (utils/send-msg! channel {:type msg/error :message (or error "Unknown error")})))

;; =============================================================================
;; Validation Functions
;; =============================================================================

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
   ;; First check if player is icehoused and trying to place regular pieces
   (or (check-player-icehoused game player-id using-captured?)
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

;; =============================================================================
;; Piece Construction
;; =============================================================================

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
      (if-let [target (targeting/find-closest-target base-piece (:board game))]
        (assoc base-piece :target-id (:id target))
        base-piece)
      base-piece)))

;; =============================================================================
;; State Mutation Functions
;; =============================================================================

(defn apply-placement!
  "Update game state with placed piece and decrement stash/captured counts.
   Returns {:success true} on success, or {:success false :error <structured-error>} on failure."
  [games room-id player-id piece using-captured?]
  (let [result (atom {:success true})]
    (swap! games (fn [games-map]
                   (if-let [game (get games-map room-id)]
                     (let [new-game (-> game
                                        (update :board conj piece)
                                        (update :board targeting/refresh-all-targets)
                                        (as-> g
                                          (if using-captured?
                                            (update-in g [:players player-id :captured]
                                                       utils/remove-first-captured (:size piece))
                                            (update-in g [:players player-id :pieces (:size piece)] dec))))]
                       (if-let [error (state/validate-game-state new-game)]
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

(defn apply-capture!
  "Update game state to capture a piece.
   Returns {:success true} on success, or {:success false :error <structured-error>} on failure."
  [games room-id player-id piece-id piece-size original-colour]
  (let [result (atom {:success true})]
    (swap! games (fn [games-map]
                   (if-let [g (get games-map room-id)]
                     (let [new-g (-> g
                                     (update :board (fn [board]
                                                      (-> (remove (utils/by-id piece-id) board)
                                                          vec
                                                          targeting/refresh-all-targets)))
                                     (update-in [:players player-id :captured]
                                                conj {:size piece-size :colour original-colour}))]
                       (if-let [err (state/validate-game-state new-g)]
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

(defn apply-finish!
  "Update game state to mark a player as finished.
   Returns {:success true :already-finished? bool} on success,
   or {:success false :error <structured-error>} on failure."
  [games room-id player-id]
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
                           (if-let [err (state/validate-game-state new-g)]
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

;; =============================================================================
;; Post-Action Side Effects
;; =============================================================================

(defn handle-post-placement!
  "Handle side effects after placement: recording, broadcasting, icehouse detection, game over check.
   prev-icehouse-players is the set of players who were already icehoused before this placement."
  [games clients room-id player-id piece using-captured? prev-icehouse-players]
  ;; Record the move for replay
  (state/record-move! games room-id {:type :place-piece
                                     :player-id player-id
                                     :piece piece
                                     :using-captured? using-captured?})
  (let [updated-game (get @games room-id)
        board (:board updated-game)
        options (get updated-game :options {})
        current-icehouse-players (rules/calculate-icehouse-players board options)
        newly-icehoused (clojure.set/difference current-icehouse-players prev-icehouse-players)]
    ;; Broadcast the piece placement
    (utils/broadcast-room! clients room-id
                           {:type msg/piece-placed
                            :piece piece
                            :game updated-game})
    ;; Broadcast newly icehoused players
    (doseq [icehoused-player newly-icehoused]
      (utils/broadcast-room! clients room-id
                             {:type msg/player-icehoused
                              :player-id icehoused-player
                              :game updated-game}))
    ;; Check for game over
    (when (rules/game-over? updated-game)
      (let [over-ice (logic/calculate-over-ice board)
            end-reason (cond
                         (rules/all-active-players-finished? updated-game) :all-pieces-placed
                         (rules/time-up? updated-game) :time-up
                         :else :unknown)
            record (rules/build-game-record updated-game end-reason)]
        ;; Save the game record
        (storage/save-game-record! record)
        (utils/broadcast-room! clients room-id
                               {:type msg/game-over
                                :game-id (:game-id updated-game)
                                :scores (rules/calculate-scores updated-game)
                                :over-ice over-ice
                                :icehouse-players (vec current-icehouse-players)})))))

(defn end-game!
  "End the game, calculate scores, save record, and broadcast game-over"
  [games clients room-id end-reason]
  (when-let [game (get @games room-id)]
    (let [board (:board game)
          options (get game :options {})
          over-ice (logic/calculate-over-ice board)
          icehouse-players (rules/calculate-icehouse-players board options)
          record (rules/build-game-record game end-reason)]
      (storage/save-game-record! record)
      (utils/broadcast-room! clients room-id
                             {:type msg/game-over
                              :game-id (:game-id game)
                              :scores (rules/calculate-scores game)
                              :over-ice over-ice
                              :icehouse-players (vec icehouse-players)}))))

;; =============================================================================
;; WebSocket Handlers
;; =============================================================================

(defn handle-place-piece
  "Handle a place-piece message from a client"
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                        "Not in a room"
                                        "You must be in a game room to place pieces."))
      (let [player-id (state/player-id-from-channel channel)
            using-captured? (boolean (:captured msg))
            ;; Track icehouse players BEFORE placement to detect new ones
            prev-icehouse-players (when game
                                    (rules/calculate-icehouse-players (:board game) (get game :options {})))
            piece (when game (construct-piece-for-placement game player-id msg))
            validation-error (when piece (validate-placement game player-id piece using-captured?))]
        (if (and piece (nil? validation-error))
          (let [placement-result (apply-placement! games room-id player-id piece using-captured?)]
            (if (:success placement-result)
              ;; Placement succeeded - proceed with side effects
              (let [updated-game (get @games room-id)
                    final-piece (logic/find-piece-by-id (:board updated-game) (:id piece))]
                (if final-piece
                  (handle-post-placement! games clients room-id player-id final-piece using-captured?
                                          (or prev-icehouse-players #{}))
                  ;; This shouldn't happen if apply-placement! succeeded, but handle it anyway
                  (send-error! channel (make-error msg/err-internal-state
                                                   "Internal error: piece not found after placement"
                                                   "An unexpected error occurred. Please try again."))))
              ;; Placement failed - send the error from apply-placement!
              (send-error! channel (:error placement-result))))
          ;; Validation failed - send the validation error
          (send-error! channel (or validation-error (make-error msg/err-invalid-game
                                                                 "Invalid game state"
                                                                 "The game is not in a valid state for this action."))))))))

(defn handle-capture-piece
  "Handle a capture-piece message from a client"
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                        "Not in a room"
                                        "You must be in a game room to capture pieces."))
      (let [player-id (state/player-id-from-channel channel)
            piece-id (:piece-id msg)
            validation-error (when game (validate-capture game player-id piece-id))]
        (if (and game (nil? validation-error))
          (let [piece (logic/find-piece-by-id (:board game) piece-id)
                piece-size (:size piece)
                original-owner (:player-id piece)
                original-colour (get-in game [:players original-owner :colour])
                capture-result (apply-capture! games room-id player-id piece-id piece-size original-colour)]
            (if (:success capture-result)
              ;; Capture succeeded - proceed with side effects
              (do
                (state/record-move! games room-id {:type :capture-piece
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
                                                                 "The capture could not be completed."))))))))

(defn handle-finish
  "Handle a player pressing the finish button"
  [games clients channel _msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                        "Not in a room"
                                        "You must be in a game room to finish."))
      (let [player-id (state/player-id-from-channel channel)
            current-game game]
        (if current-game
          (let [finish-result (apply-finish! games room-id player-id)]
            (if (:success finish-result)
              ;; Finish succeeded - broadcast and check for game end
              (let [updated-game (get @games room-id)]
                (utils/broadcast-room! clients room-id
                                       {:type msg/player-finished
                                        :player-id player-id
                                        :game updated-game})
                (when (rules/all-players-finished? updated-game)
                  (end-game! games clients room-id :all-players-finished)))
              ;; Finish failed - send the error
              (send-error! channel (:error finish-result))))
          ;; No game found
          (send-error! channel (make-error msg/err-invalid-game
                                            "No active game"
                                            "You must be in an active game to finish.")))))))

(defn start-game!
  "Start a new game with the given players and options.
   Returns {:success true :game <game-state>} on success,
   or {:success false :error <structured-error>} on failure."
  ([games room-id players]
   (start-game! games room-id players {}))
  ([games room-id players options]
   (let [new-game (state/create-game room-id players options)]
     (if-let [error (state/validate-game-state new-game)]
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
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (utils/send-msg! channel {:type msg/validation-result
                                :valid false
                                :error (make-error msg/err-invalid-game
                                                   "Not in a room"
                                                   "You must be in a game room to validate moves.")})
      (let [player-id (state/player-id-from-channel channel)
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
                                                         "Valid actions are 'place' or 'capture'.")})))))))

;; =============================================================================
;; Legal Moves Query
;; =============================================================================

(defn generate-sample-positions
  "Generate sample positions across the play area for legal move checks.
   Returns a grid of positions spaced by step-size pixels."
  [step-size]
  (for [x (range 0 (inc const/play-area-width) step-size)
        y (range 0 (inc const/play-area-height) step-size)]
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
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (utils/send-msg! channel {:type msg/legal-moves
                                :valid-positions []
                                :error (make-error msg/err-invalid-game
                                                   "Not in a room"
                                                   "You must be in a game room to query legal moves.")})
      (let [player-id (state/player-id-from-channel channel)
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
                                          :angle-step angle-step})))))))))
