(ns icehouse.game.canvas
  "Canvas Reagent component for the game board."
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.game.constants :as const]
            [icehouse.game.rendering :as render]
            [icehouse.game.placement :as placement]
            [icehouse.game.interactions :as interact]))

(defn game-canvas []
  (let [canvas-ref (r/atom nil)
        ;; Handler to clear stash-drag-pending on mouseup anywhere
        global-mouseup-handler (fn [_e]
                                 (reset! placement/stash-drag-pending nil))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        ;; Add global mouseup listener to clear stash-drag-pending
        (.addEventListener js/document "mouseup" global-mouseup-handler)
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                ui @state/ui-state
                player @state/current-player]
            (render/draw-with-preview ctx @state/game-state nil (:selected-piece ui)
                               (:colour player) (:hover-pos ui) (:id player) nil))))

      :component-will-unmount
      (fn [this]
        (.removeEventListener js/document "mouseup" global-mouseup-handler))

      :component-did-update
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                ui @state/ui-state
                player @state/current-player
                zoom-state (placement/calculate-zoom-state (:zoom-active ui) (:drag ui) (:hover-pos ui))]
            (render/draw-with-preview ctx
                               @state/game-state
                               (:drag ui)
                               (:selected-piece ui)
                               (:colour player)
                               (:hover-pos ui)
                               (:id player)
                               zoom-state))))

      :reagent-render
      (fn []
        ;; Deref state atoms so Reagent re-renders when they change
        (let [_ @state/game-state
              _ @state/ui-state
              _ @placement/stash-drag-pending]
          [:canvas
           {:ref #(reset! canvas-ref %)
            :width const/canvas-width
            :height const/canvas-height
            :style {:border "2px solid #4ecdc4" :cursor "crosshair"}
            :on-mouse-down interact/handle-canvas-mousedown
            :on-mouse-enter interact/handle-canvas-mouseenter
            :on-mouse-move interact/handle-canvas-mousemove
            :on-mouse-up interact/handle-canvas-mouseup
            :on-mouse-leave interact/handle-canvas-mouseleave}]))})))
