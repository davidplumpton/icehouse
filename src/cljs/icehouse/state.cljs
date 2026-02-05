(ns icehouse.state
  (:require [reagent.core :as r]
            [icehouse.schema :as schema]
            [icehouse.constants :as const]
            [malli.core :as m]))

;; =============================================================================
;; Player Identity
;; =============================================================================

;; Current player's identity and preferences
;; {:id nil :name "" :colour "#e53935"}
(defonce current-player (r/atom {:id nil
                                  :name ""
                                  :colour (first const/colours)}))  ;; Default to first colour

;; =============================================================================
;; Session State
;; =============================================================================

;; Current view: :lobby, :game
(defonce current-view (r/atom :lobby))

;; WebSocket connection status: :connected, :disconnected, :connecting
(defonce ws-status (r/atom :connecting))

;; Error message to display to the user (auto-clears after a few seconds)
(defonce error-message (r/atom nil))

;; List of players in current room
(defonce players (r/atom []))

;; Game options for the current room
;; {:icehouse-rule true, :timer-enabled true, :timer-duration :random}
(defonce game-options (r/atom nil))

;; =============================================================================
;; Game State
;; =============================================================================

;; Main game state from server
(defonce game-state (r/atom nil))

;; Validate game state from server
(defonce _game-state-validator
  (add-watch game-state :validator
             (fn [_ _ _ new-state]
               (when (and new-state (not (m/validate schema/GameState new-state)))
                 (.error js/console "Invalid game state from server:"
                         (clj->js (m/explain schema/GameState new-state)))))))

;; Game result when game ends
;; {:scores {"player-id" score} :icehouse-players ["id"] :over-ice {...}}
(defonce game-result (r/atom nil))

;; Set of player IDs who are currently icehoused (during gameplay)
;; Players in this set can only play captured pieces, not regular ones
(defonce icehoused-players (r/atom #{}))

;; Current time for game timer (updated every second)
(defonce current-time (r/atom (js/Date.now)))

;; Cached iced pieces calculation (set of piece IDs)
;; Updated automatically when board changes to avoid recalculating every frame
(defonce cached-iced-pieces (r/atom #{}))

;; Cached over-ice calculation (map of defender-id to over-ice info)
;; Updated automatically when board changes to avoid recalculating on every hover
(defonce cached-over-ice (r/atom {}))

;; =============================================================================
;; UI Interaction State
;; =============================================================================

;; UI state for piece placement and interaction
;; - :selected-piece - current piece being configured {:size :orientation :captured?}
;; - :drag - drag state for placing pieces at angles {:start-x :start-y :current-x :current-y :last-x :last-y :locked-angle}
;; - :hover-pos - current mouse position on canvas {:x :y} or nil
;; - :zoom-active - whether 4x zoom mode is active for fine placement
;; - :show-help - whether help overlay is visible
;; - :move-mode - toggle for position-adjust mode (like holding shift but persistent)
;; - :last-placement-time - timestamp of last piece placement for throttling
(defonce ui-state (r/atom {:selected-piece {:size :small :orientation :standing :captured? false}
                           :drag nil
                           :hover-pos nil
                           :zoom-active false
                           :show-help false
                           :move-mode false
                           :last-placement-time 0}))

;; Validate UI state changes
(defonce _ui-state-validator
  (add-watch ui-state :validator
             (fn [_ _ _ new-state]
               (try
                 (if (m/validate schema/UIState new-state)
                   nil
                   (.error js/console "Invalid UI state update:"
                           (clj->js (m/explain schema/UIState new-state))))
                 (catch js/Error e
                   (.error js/console "Schema validation error in UIState:" e))))))

;; =============================================================================
;; Replay State
;; =============================================================================

;; Replay state
;; {:record {...} :current-move 0 :playing? false :speed 1}
(defonce replay-state (r/atom nil))

;; Validate replay state changes
(defonce _replay-state-validator
  (add-watch replay-state :validator
             (fn [_ _ _ new-state]
               (when new-state
                 (try
                   (if (m/validate schema/ReplayState new-state)
                     nil
                     (.error js/console "Invalid replay state update:"
                             (clj->js (m/explain schema/ReplayState new-state))))
                   (catch js/Error e
                     (.error js/console "Schema validation error in ReplayState:" e)))))))

;; List of available saved game IDs
(defonce game-list (r/atom nil))

;; =============================================================================
;; State Transitions
;; =============================================================================
;; Named transition functions that batch related atom resets together.
;; Reagent defers rendering to the next animation frame, so all changes
;; within a synchronous transition are rendered in a single pass.

(defn start-game!
  "Transition state for starting a new game.
   Resets game result, icehoused players, piece selection, and switches to game view."
  [game-data]
  (reset! game-state game-data)
  (reset! game-result nil)
  (reset! icehoused-players #{})
  (swap! ui-state assoc :selected-piece {:size :small :orientation :standing :captured? false})
  (reset! current-view :game))

(defn start-replay!
  "Transition state for starting a game replay.
   Clears game list and result, switches to replay view, and initializes replay state."
  [record-data]
  (reset! game-list nil)
  (reset! game-result nil)
  (reset! current-view :replay)
  (reset! replay-state {:record record-data
                         :current-move 0
                         :playing? false
                         :speed 1}))

(defn show-game-list!
  "Transition state for showing the saved game list."
  [games]
  (reset! current-view :replay)
  (reset! game-list games))

(defn leave-game-to-lobby!
  "Transition state for leaving an active/completed game and returning to lobby."
  []
  (reset! game-result nil)
  (reset! game-state nil)
  (reset! current-view :lobby))

(defn leave-replay-to-lobby!
  "Transition state for leaving replay/list views and returning to lobby."
  []
  (reset! replay-state nil)
  (reset! game-list nil)
  (reset! current-view :lobby))

;; =============================================================================
;; Constants
;; =============================================================================

;; Traditional Looney Labs pyramid stash colours
;; Rainbow stash: red, yellow, green, blue, black
;; Xeno stash: purple, cyan, orange, white
(def colours const/colours)
