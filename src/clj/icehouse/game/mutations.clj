(ns icehouse.game.mutations
  "State mutation helpers extracted from game handlers."
  (:require [taoensso.timbre :as log]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.schema :as schema]
            [icehouse.geometry :as geo]
            [icehouse.game-state :as state]
            [icehouse.game-targeting :as targeting]
            [icehouse.game.errors :refer [make-error]]
            [malli.core :as m]))

(defn construct-piece-for-placement
  "Construct the piece map for placement, handling auto-targeting and colour assignment."
  [game player-id msg]
  {:post [(m/validate schema/Piece %)]}
  (let [player-colour (get-in game [:players player-id :colour])
        using-captured? (boolean (:captured msg))
        piece-size (keyword (:size msg))
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
    (if (and (geo/pointing? base-piece)
             (nil? (:target-id base-piece))
             game)
      (if-let [target (targeting/find-closest-target base-piece (:board game))]
        (assoc base-piece :target-id (:id target))
        base-piece)
      base-piece)))

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
                           (log/error "Invalid game state after placement" {:error error})
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
                           (log/error "Invalid game state after capture" {:error err})
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
                               (log/error "Invalid game state after finish" {:error err})
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
         (log/error "Failed to start game due to invalid initial state" {:error error})
         {:success false
          :error (make-error msg/err-internal-state
                             "Failed to create game"
                             "An internal validation error prevented the game from starting.")})
       (do
         (swap! games assoc room-id new-game)
         {:success true :game new-game})))))
