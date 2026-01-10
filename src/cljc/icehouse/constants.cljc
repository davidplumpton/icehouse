(ns icehouse.constants
  "Shared constants for Icehouse game.
   Used by both backend (Clojure) and frontend (ClojureScript).")

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

(def initial-piece-counts
  "Starting pieces per player by size"
  {:small 5 :medium 5 :large 5})
