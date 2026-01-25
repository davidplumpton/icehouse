(ns icehouse.constants
  "Shared constants for Icehouse game.
   Used by both backend (Clojure) and frontend (ClojureScript).")

;; =============================================================================
;; Piece Definitions
;; =============================================================================

(def pips
  "Points per piece size (pip values)"
  {:small 1 :medium 2 :large 3})

(def piece-sizes
  "Piece base width in pixels. Used for collision and rendering."
  {:small 40 :medium 50 :large 60})

(def default-piece-size
  "Fallback for unknown piece sizes"
  40)

(def tip-offset-ratio
  "Triangle tip extends this ratio * base-size from center"
  0.75)

(def initial-piece-counts
  "Starting pieces per player by size"
  {:small 5 :medium 5 :large 5})

(def max-pieces-per-size
  "Maximum number of pieces of a single size a player can have"
  5)

;; =============================================================================
;; Game Options Defaults
;; =============================================================================

(def default-placement-throttle-sec
  "Default seconds to wait between piece placements"
  2.0)

;; =============================================================================
;; Game Duration
;; =============================================================================

(def game-duration-min-ms
  "Minimum game duration in milliseconds (2 minutes)"
  (* 2 60 1000))

(def game-duration-max-ms
  "Maximum game duration in milliseconds (5 minutes)"
  (* 5 60 1000))

(def min-timer-duration-ms
  "Minimum allowed custom timer duration"
  1000)

;; =============================================================================
;; Play Area Dimensions
;; =============================================================================

(def play-area-width
  "Play area width in pixels"
  1000)

(def play-area-height
  "Play area height in pixels"
  750)

;; =============================================================================
;; Game Rules
;; =============================================================================

(def icehouse-min-pieces
  "Minimum pieces placed before icehouse rule can trigger"
  8)

(def parallel-threshold
  "Threshold for detecting parallel lines in ray casting"
  0.0001)

;; =============================================================================
;; UI & Visuals
;; =============================================================================

(def colours
  "Traditional Looney Labs pyramid stash colours"
  ["#e53935"   ; red (Rainbow)
   "#fdd835"   ; yellow (Rainbow)
   "#43a047"   ; green (Rainbow)
   "#1e88e5"   ; blue (Rainbow)
   "#7b1fa2"   ; purple (Xeno)
   "#00acc1"   ; cyan (Xeno)
   "#fb8c00"   ; orange (Xeno)
   "#212121"]) ; black (Rainbow)