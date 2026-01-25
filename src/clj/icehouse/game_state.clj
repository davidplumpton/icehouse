(ns icehouse.game-state
  "Game state creation and management.
   Contains functions for creating games, recording moves, and managing the games atom."
  (:require [icehouse.utils :as utils]
            [icehouse.schema :as schema]
            [icehouse.constants :as const]
            [malli.core :as m]))

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
;; Game Creation
;; =============================================================================

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

;; =============================================================================
;; Move Recording
;; =============================================================================

(defn record-move!
  "Append a move to the game's move history.
   Requires the games atom to be passed in."
  [games room-id move]
  (when-let [game (get @games room-id)]
    (let [elapsed (- (System/currentTimeMillis) (:started-at game))]
      (swap! games update-in [room-id :moves] conj
             (assoc move
                    :timestamp (System/currentTimeMillis)
                    :elapsed-ms elapsed)))))

;; =============================================================================
;; State Validation
;; =============================================================================

(defn validate-game-state
  "Validate game state against schema, returns nil if valid or error message"
  [game]
  (if (m/validate schema/GameState game)
    nil
    (str "Invalid game state: " (m/explain schema/GameState game))))
