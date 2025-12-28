(ns icehouse.state
  (:require [reagent.core :as r]))

(defonce current-view (r/atom :lobby))

(defonce player-id (r/atom nil))

(defonce room-id (r/atom nil))

(defonce player-name (r/atom ""))

(defonce player-colour (r/atom "#ff6b6b"))

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

(def colours
  ["#ff6b6b"   ; red
   "#4ecdc4"   ; teal
   "#ffe66d"   ; yellow
   "#95e1d3"   ; mint
   "#f38181"   ; coral
   "#aa96da"   ; lavender
   "#fcbad3"   ; pink
   "#a8d8ea"]) ; sky blue
