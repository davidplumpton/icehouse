(ns icehouse.game
  "Game module - re-exports from focused submodules for backward compatibility."
  (:require [icehouse.game.constants :as const]
            [icehouse.game.rendering :as render]
            [icehouse.game.placement :as placement]
            [icehouse.game.interactions :as interact]
            [icehouse.game.canvas :as canvas]
            [icehouse.game.view :as view]))

;; =============================================================================
;; Public API - Re-exports for backward compatibility
;; =============================================================================

;; Constants (used by replay.cljs)
(def canvas-width const/canvas-width)
(def canvas-height const/canvas-height)

;; Rendering (used by replay.cljs)
(def draw-board render/draw-board)

;; View components (used by core.cljs)
(def game-view view/game-view)
(def stash-panel view/stash-panel)
