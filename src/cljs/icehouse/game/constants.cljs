(ns icehouse.game.constants
  "Canvas and UI constants specific to the game view."
  (:require [icehouse.constants :as const]))

;; Canvas/play area dimensions
(def canvas-width const/play-area-width)
(def canvas-height const/play-area-height)
(def grid-size 50)

;; Piece sizes for stash SVG rendering [width height]
(def stash-sizes {:small [24 36] :medium [30 45] :large [36 54]})

;; Default piece counts per player
(def default-pieces const/initial-piece-counts)

;; Rendering constants
(def preview-alpha 0.6)
(def zoom-scale 4)
(def min-line-width 0.5)

;; Game rules
(def attack-unlock-threshold 2)
(def timer-urgent-threshold-ms 30000)
