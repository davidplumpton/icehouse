(ns icehouse.state
  (:require [reagent.core :as r]
            [icehouse.schema :as schema]
            [malli.core :as m]))

;; =============================================================================
;; Player Identity
;; =============================================================================

;; Current player's identity and preferences
;; {:id nil :name "" :colour "#e53935"}
(defonce current-player (r/atom {:id nil
                                  :name ""
                                  :colour "#e53935"}))  ;; Default to red

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

;; Current time for game timer (updated every second)
(defonce current-time (r/atom (js/Date.now)))

;; Cached iced pieces calculation (set of piece IDs)
;; Updated automatically when board changes to avoid recalculating every frame
(defonce cached-iced-pieces (r/atom #{}))

;; =============================================================================
;; UI Interaction State
;; =============================================================================

;; UI state for piece placement and interaction
;; - :selected-piece - current piece being configured {:size :orientation :captured?}
;; - :drag - drag state for placing pieces at angles {:start-x :start-y :current-x :current-y :last-x :last-y :locked-angle}
;; - :hover-pos - current mouse position on canvas {:x :y} or nil
;; - :zoom-active - whether 4x zoom mode is active for fine placement
;; - :show-help - whether help overlay is visible
(defonce ui-state (r/atom {:selected-piece {:size :small :orientation :standing :captured? false}
                           :drag nil
                           :hover-pos nil
                           :zoom-active false
                           :show-help false}))

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
;; Constants
;; =============================================================================

;; Traditional Looney Labs pyramid stash colours
;; Rainbow stash: red, yellow, green, blue, black
;; Xeno stash: purple, cyan, orange, white
(def colours
  ["#e53935"   ; red (Rainbow)
   "#fdd835"   ; yellow (Rainbow)
   "#43a047"   ; green (Rainbow)
   "#1e88e5"   ; blue (Rainbow)
   "#7b1fa2"   ; purple (Xeno)
   "#00acc1"   ; cyan (Xeno)
   "#fb8c00"   ; orange (Xeno)
   "#212121"]) ; black (Rainbow)
