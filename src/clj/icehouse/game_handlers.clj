(ns icehouse.game-handlers
  "WebSocket game handlers. This facade keeps public API stable while
   delegating validation/mutation/query logic to focused namespaces."
  (:require [clojure.set]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.storage :as storage]
            [icehouse.game-logic :as logic]
            [icehouse.game-state :as state]
            [icehouse.game-rules :as rules]
            [icehouse.game.errors :as errors]
            [icehouse.game.validators :as validators]
            [icehouse.game.mutations :as mutations]
            [icehouse.game.query :as query]
            [icehouse.game.replay-handlers :as replay]))

;; =============================================================================
;; Re-exported helpers
;; =============================================================================

(def make-error errors/make-error)
(def send-error! errors/send-error!)

(def validate-placement validators/validate-placement)
(def valid-placement? validators/valid-placement?)
(def validate-capture validators/validate-capture)

(def construct-piece-for-placement mutations/construct-piece-for-placement)
(def apply-placement! mutations/apply-placement!)
(def apply-capture! mutations/apply-capture!)
(def apply-finish! mutations/apply-finish!)

(def generate-sample-positions query/generate-sample-positions)
(def find-legal-placements query/find-legal-placements)

;; =============================================================================
;; Post-Action Side Effects
;; =============================================================================

(defn end-game!
  "End the game, calculate scores, save record, and broadcast game-over.
   If end-reason is not provided, it will be determined from game state."
  ([games clients room-id]
   (when-let [game (get @games room-id)]
     (let [end-reason (cond
                        (rules/all-players-finished? game) :all-players-finished
                        (rules/all-active-players-finished? game) :all-pieces-placed
                        (rules/time-up? game) :time-up
                        :else :unknown)]
       (end-game! games clients room-id end-reason))))
  ([games clients room-id end-reason]
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
                               :icehouse-players (vec icehouse-players)})))))

(defn handle-post-placement!
  "Handle side effects after placement: recording, broadcasting, icehouse detection, game over check.
   prev-icehouse-players is the set of players who were already icehoused before this placement."
  [games clients room-id player-id piece using-captured? prev-icehouse-players]
  (state/record-move! games room-id {:type :place-piece
                                     :player-id player-id
                                     :piece piece
                                     :using-captured? using-captured?})
  (let [updated-game (get @games room-id)
        board (:board updated-game)
        options (get updated-game :options {})
        current-icehouse-players (rules/calculate-icehouse-players board options)
        newly-icehoused (clojure.set/difference current-icehouse-players prev-icehouse-players)]
    (utils/broadcast-room! clients room-id
                           {:type msg/piece-placed
                            :piece piece
                            :game updated-game})
    (doseq [icehoused-player newly-icehoused]
      (utils/broadcast-room! clients room-id
                             {:type msg/player-icehoused
                              :player-id icehoused-player
                              :game updated-game}))
    (when (and (rules/game-over? updated-game)
               (not (rules/all-players-finished? updated-game)))
      (end-game! games clients room-id))))

;; =============================================================================
;; WebSocket Handlers
;; =============================================================================

(defn handle-place-piece
  "Handle a place-piece message from a client."
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                       "Not in a room"
                                       "You must be in a game room to place pieces."))
      (let [player-id (state/player-id-from-channel @clients channel)
            using-captured? (boolean (:captured msg))
            prev-icehouse-players (when game
                                    (rules/calculate-icehouse-players (:board game) (get game :options {})))
            piece (when game (construct-piece-for-placement game player-id msg))
            validation-error (when piece (validate-placement game player-id piece using-captured?))]
        (if (and piece (nil? validation-error))
          (let [placement-result (apply-placement! games room-id player-id piece using-captured?)]
            (if (:success placement-result)
              (let [updated-game (get @games room-id)
                    final-piece (logic/find-piece-by-id (:board updated-game) (:id piece))]
                (if final-piece
                  (handle-post-placement! games clients room-id player-id final-piece using-captured?
                                          (or prev-icehouse-players #{}))
                  (send-error! channel (make-error msg/err-internal-state
                                                   "Internal error: piece not found after placement"
                                                   "An unexpected error occurred. Please try again."))))
              (send-error! channel (:error placement-result))))
          (send-error! channel (or validation-error (make-error msg/err-invalid-game
                                                                "Invalid game state"
                                                                "The game is not in a valid state for this action."))))))))

(defn handle-capture-piece
  "Handle a capture-piece message from a client."
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                       "Not in a room"
                                       "You must be in a game room to capture pieces."))
      (let [player-id (state/player-id-from-channel @clients channel)
            piece-id (:piece-id msg)
            validation-error (when game (validate-capture game player-id piece-id))]
        (if (and game (nil? validation-error))
          (let [piece (logic/find-piece-by-id (:board game) piece-id)
                piece-size (:size piece)
                original-owner (:player-id piece)
                original-colour (get-in game [:players original-owner :colour])
                capture-result (apply-capture! games room-id player-id piece-id piece-size original-colour)]
            (if (:success capture-result)
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
              (send-error! channel (:error capture-result))))
          (send-error! channel (or validation-error (make-error msg/err-invalid-game
                                                                "Invalid capture"
                                                                "The capture could not be completed."))))))))

(defn handle-finish
  "Handle a player pressing the finish button."
  [games clients channel _msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (send-error! channel (make-error msg/err-invalid-game
                                       "Not in a room"
                                       "You must be in a game room to finish."))
      (let [player-id (state/player-id-from-channel @clients channel)
            current-game game]
        (if current-game
          (let [finish-result (apply-finish! games room-id player-id)]
            (if (:success finish-result)
              (let [updated-game (get @games room-id)]
                (utils/broadcast-room! clients room-id
                                       {:type msg/player-finished
                                        :player-id player-id
                                        :game updated-game})
                (when (rules/all-players-finished? updated-game)
                  (end-game! games clients room-id)))
              (send-error! channel (:error finish-result))))
          (send-error! channel (make-error msg/err-invalid-game
                                           "No active game"
                                           "You must be in an active game to finish.")))))))

(defn start-game!
  "Start a new game with the given players and options.
   Returns {:success true :game <game-state>} on success,
   or {:success false :error <structured-error>} on failure."
  ([games room-id players]
   (mutations/start-game! games room-id players))
  ([games room-id players options]
   (mutations/start-game! games room-id players options)))

;; =============================================================================
;; Replay + Query handlers
;; =============================================================================

(defn handle-list-games
  "Send list of saved game record IDs to client."
  [channel]
  (replay/handle-list-games channel))

(defn handle-load-game
  "Load and send a game record to client."
  [channel msg]
  (replay/handle-load-game channel msg))

(defn handle-validate-move
  "Validate a move without executing it."
  [games clients channel msg]
  (query/handle-validate-move games clients channel msg))

(defn handle-query-legal-moves
  "Query legal move positions for a given piece type."
  [games clients channel msg]
  (query/handle-query-legal-moves games clients channel msg))
