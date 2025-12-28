(ns icehouse.state
  (:require [reagent.core :as r]))

(defonce current-view (r/atom :lobby))

(defonce player-id (r/atom nil))

(defonce room-id (r/atom nil))

(defonce player-name (r/atom ""))

(defonce player-colour (r/atom "#e53935"))  ;; Default to red

(defonce players (r/atom []))

(defonce game-state (r/atom nil))

(defonce selected-piece (r/atom {:size :small :orientation :standing :captured? false}))

;; Drag state for placing pieces at angles
;; {:x :y :dragging?} - tracks the starting position of a drag operation
(defonce drag-state (r/atom nil))

;; Error message to display to the user (auto-clears after a few seconds)
(defonce error-message (r/atom nil))

;; WebSocket connection status: :connected, :disconnected, :connecting
(defonce ws-status (r/atom :connecting))

;; Current mouse position on canvas for hover detection
;; {:x :y} or nil when mouse is not over canvas
(defonce hover-pos (r/atom nil))

;; Game result when game ends
;; {:scores {"player-id" score} :icehouse-players ["id"] :over-ice {...}}
(defonce game-result (r/atom nil))

;; Current time for game timer (updated every second)
(defonce current-time (r/atom (js/Date.now)))

;; Replay state
;; {:record {...} :current-move 0 :playing? false :speed 1}
(defonce replay-state (r/atom nil))

;; List of available saved game IDs
(defonce game-list (r/atom nil))

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
