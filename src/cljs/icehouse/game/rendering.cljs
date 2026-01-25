(ns icehouse.game.rendering
  "Canvas drawing functions for the game board and pieces."
  (:require [icehouse.game.constants :as const]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.geometry :as geo]
            [icehouse.game-logic :as logic]
            [icehouse.utils :as utils]))

;; =============================================================================
;; Board Cache Management
;; =============================================================================

;; Track last board to avoid unnecessary recalculations
(defonce ^:private last-board (atom nil))

(defn- update-board-caches!
  "Update cached iced pieces and over-ice when the board changes"
  [board]
  (when (not= board @last-board)
    (reset! last-board board)
    (reset! state/cached-iced-pieces (logic/calculate-iced-pieces board))
    (reset! state/cached-over-ice (logic/calculate-over-ice board))))

;; Set up a watch to update board caches when game state changes
(defonce ^:private _board-cache-watch
  (add-watch state/game-state ::board-caches
             (fn [_ _ _ new-state]
               (when new-state
                 (update-board-caches! (:board new-state))))))

;; =============================================================================
;; Attack Target Detection
;; =============================================================================

(defn potential-target?
  "Check if target could be attacked (in trajectory, ignoring range)"
  [attacker target attacker-player-id]
  (and (not= (utils/normalize-player-id (:player-id target))
             (utils/normalize-player-id attacker-player-id))
       (geo/standing? target)
       (geo/in-front-of? attacker target)))

(defn- valid-target?
  "Check if target is a valid attack target (in trajectory AND in range)"
  [attacker target attacker-player-id]
  (and (potential-target? attacker target attacker-player-id)
       (geo/within-range? attacker target)))

(defn find-targets-for-attack
  "Find all potential targets and categorize them as valid (in range) or invalid (out of range)"
  [attacker player-id board]
  (let [potential (filter #(potential-target? attacker % player-id) board)]
    {:valid (filter #(geo/within-range? attacker %) potential)
     :invalid (remove #(geo/within-range? attacker %) potential)}))

(defn find-closest-target
  "Find the closest valid target for an attacking piece"
  [attacker player-id board]
  (let [{:keys [valid]} (find-targets-for-attack attacker player-id board)]
    (when (seq valid)
      (->> valid
           (map (fn [t] {:target t
                         :dist (geo/distance (geo/piece-center attacker) (geo/piece-center t))}))
           (sort-by :dist)
           first
           :target))))

(defn find-piece-at
  "Find the piece at the given x,y position, or nil if none"
  [x y board]
  (first (filter (fn [piece]
                   (let [verts (geo/piece-vertices piece)]
                     (geo/point-in-polygon? [x y] verts)))
                 (reverse board))))

;; =============================================================================
;; Drawing Utilities
;; =============================================================================

(defn set-line-width
  "Set line width, accounting for zoom scale if present. Maintains minimum width for visibility."
  [ctx width zoom-state]
  (set! (.-lineWidth ctx)
        (if zoom-state
          (let [{:keys [scale]} zoom-state
                scaled-width (/ width scale)]
            (max const/min-line-width scaled-width))
          width)))

(defn lighten-color
  "Lighten a hex color by blending it towards white"
  [hex-color]
  (let [;; Parse hex color
        r (js/parseInt (.substring hex-color 1 3) 16)
        g (js/parseInt (.substring hex-color 3 5) 16)
        b (js/parseInt (.substring hex-color 5 7) 16)
        ;; Blend towards white (increase brightness by 50%)
        blend-factor 0.5
        new-r (js/Math.round (+ r (* (- 255 r) blend-factor)))
        new-g (js/Math.round (+ g (* (- 255 g) blend-factor)))
        new-b (js/Math.round (+ b (* (- 255 b) blend-factor)))
        ;; Convert back to hex
        r-hex (.padStart (.toString new-r 16) 2 "0")
        g-hex (.padStart (.toString new-g 16) 2 "0")
        b-hex (.padStart (.toString new-b 16) 2 "0")]
    (str "#" r-hex g-hex b-hex)))

;; =============================================================================
;; Zoom Transformations
;; =============================================================================

(defn apply-zoom
  "Apply zoom transformation to the context"
  [ctx zoom-state]
  (when zoom-state
    (let [{:keys [center-x center-y scale]} zoom-state]
      (.save ctx)
      ;; Translate to zoom center, scale, translate back
      (.translate ctx center-x center-y)
      (.scale ctx scale scale)
      (.translate ctx (- center-x) (- center-y)))))

(defn restore-zoom
  "Restore context after zoom transformation"
  [ctx zoom-state]
  (when zoom-state
    (.restore ctx)))

;; =============================================================================
;; Piece Drawing
;; =============================================================================

(defn draw-pyramid [ctx x y size colour orientation angle & [{:keys [iced? zoom-state]}]]
  (let [size-kw (keyword size)
        orient-kw (keyword orientation)
        base-size (get geo/piece-sizes size-kw geo/default-piece-size)
        half-size (/ base-size 2)
        rotation (or angle 0)
        final-colour (if iced? (lighten-color colour) colour)]
    (.save ctx)
    (.translate ctx x y)
    (.rotate ctx rotation)

    (set! (.-fillStyle ctx) final-colour)
    (set! (.-strokeStyle ctx) "#000")
    (set-line-width ctx 2 zoom-state)

    (if (= orient-kw :standing)
      ;; Standing/defensive: pyramid viewed from above (square with X)
      (do
        ;; Draw filled square
        (.beginPath ctx)
        (.rect ctx (- half-size) (- half-size) base-size base-size)
        (.fill ctx)
        (.stroke ctx)
        ;; Draw diagonal cross lines
        (.beginPath ctx)
        (.moveTo ctx (- half-size) (- half-size))
        (.lineTo ctx half-size half-size)
        (.moveTo ctx half-size (- half-size))
        (.lineTo ctx (- half-size) half-size)
        (.stroke ctx))
      ;; Pointing/attacking: side view triangle pointing right (3:2 length:base ratio like stash)
      (let [half-width (* base-size geo/tip-offset-ratio)]
        (.beginPath ctx)
        (.moveTo ctx half-width 0)                    ; tip pointing right
        (.lineTo ctx (- half-width) (- half-size))    ; top-left corner
        (.lineTo ctx (- half-width) half-size)        ; bottom-left corner
        (.closePath ctx)
        (.fill ctx)
        (.stroke ctx)))

    (.restore ctx)))

;; =============================================================================
;; Highlight Drawing
;; =============================================================================

(defn draw-capture-highlight
  "Draw a highlight around a capturable piece"
  [ctx piece zoom-state]
  (let [verts (geo/piece-vertices piece)]
    (.save ctx)
    (set! (.-strokeStyle ctx) theme/gold)
    (set-line-width ctx 4 zoom-state)
    (set! (.-shadowColor ctx) theme/gold)
    (set! (.-shadowBlur ctx) 10)
    (.beginPath ctx)
    (let [[x0 y0] (first verts)]
      (.moveTo ctx x0 y0))
    (doseq [[x y] (rest verts)]
      (.lineTo ctx x y))
    (.closePath ctx)
    (.stroke ctx)
    (.restore ctx)))

(defn draw-target-highlight
  "Draw a highlight around a target piece - green for valid, red for out of range"
  [ctx piece valid? zoom-state]
  (let [verts (geo/piece-vertices piece)
        color (if valid? "rgba(0,255,0,0.5)" "rgba(255,0,0,0.5)")]
    (.save ctx)
    (set! (.-strokeStyle ctx) color)
    (set-line-width ctx 4 zoom-state)
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (.beginPath ctx)
    (let [[fx fy] (first verts)]
      (.moveTo ctx fx fy))
    (doseq [[x y] (rest verts)]
      (.lineTo ctx x y))
    (.closePath ctx)
    (.stroke ctx)
    (.restore ctx)))

(defn draw-overlap-highlight
  "Draw a red highlight around a piece to indicate overlap/conflict"
  [ctx piece zoom-state]
  (let [verts (geo/piece-vertices piece)]
    (.save ctx)
    (set! (.-strokeStyle ctx) "rgba(255,50,50,0.8)")
    (set! (.-fillStyle ctx) "rgba(255,50,50,0.3)")
    (set-line-width ctx 3 zoom-state)
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (.beginPath ctx)
    (let [[fx fy] (first verts)]
      (.moveTo ctx fx fy))
    (doseq [[x y] (rest verts)]
      (.lineTo ctx x y))
    (.closePath ctx)
    (.fill ctx)
    (.stroke ctx)
    (.restore ctx)))

;; =============================================================================
;; Board Drawing
;; =============================================================================

(defn draw-board
  "Draw the game board. opts can include :iced-pieces to provide pre-computed iced pieces and :zoom-state for zoom rendering."
  ([ctx game hover-pos current-player-id]
   (draw-board ctx game hover-pos current-player-id nil))
  ([ctx game hover-pos current-player-id opts]
   (set! (.-fillStyle ctx) theme/board-background)
   (.fillRect ctx 0 0 const/canvas-width const/canvas-height)

   (set! (.-strokeStyle ctx) theme/grid-color)
   (set-line-width ctx 1 (:zoom-state opts))
   (doseq [x (range 0 const/canvas-width const/grid-size)]
     (.beginPath ctx)
     (.moveTo ctx x 0)
     (.lineTo ctx x const/canvas-height)
     (.stroke ctx))
   (doseq [y (range 0 const/canvas-height const/grid-size)]
     (.beginPath ctx)
     (.moveTo ctx 0 y)
     (.lineTo ctx const/canvas-width y)
     (.stroke ctx))

   (when game
     (let [board (:board game)
           hovered-piece (when hover-pos
                           (find-piece-at (:x hover-pos) (:y hover-pos) board))
           ;; Use provided iced-pieces, or cached value, or calculate
           iced-pieces (or (:iced-pieces opts)
                           @state/cached-iced-pieces)
           over-ice @state/cached-over-ice
           zoom-state (:zoom-state opts)]
       ;; Draw all pieces
       (doseq [piece board]
         (let [player-id (:player-id piece)
               player-data (get-in game [:players player-id])
               colour (or (:colour piece) (:colour player-data) "#888")
               is-iced? (contains? iced-pieces (:id piece))]
           (draw-pyramid ctx
                         (:x piece)
                         (:y piece)
                         (:size piece)
                         colour
                         (:orientation piece)
                         (:angle piece)
                         {:iced? is-iced? :zoom-state zoom-state})))
       ;; Draw capture highlight if hovering over a capturable piece
       (when (and hovered-piece
                  (logic/capturable-piece? hovered-piece current-player-id board over-ice))
         (draw-capture-highlight ctx hovered-piece zoom-state))))))

;; =============================================================================
;; Preview Drawing
;; =============================================================================

(defn find-overlapping-pieces
  "Find all board pieces that overlap with a preview piece"
  [preview-piece board]
  (filter #(geo/pieces-intersect? preview-piece %) board))

(defn draw-attack-preview
  "Draw attack range indicator and target highlights for an attacking piece being placed"
  [ctx game x y angle size player-id zoom-state]
  (let [base-size (get geo/piece-sizes size geo/default-piece-size)
        tip-offset (* base-size geo/tip-offset-ratio)
        tip-x (+ x (* (js/Math.cos angle) tip-offset))
        tip-y (+ y (* (js/Math.sin angle) tip-offset))
        ;; Attack range extends piece height from tip (height = 2 * geo/tip-offset-ratio * base-size)
        piece-height (* 2 geo/tip-offset-ratio base-size)
        range-end-x (+ tip-x (* (js/Math.cos angle) piece-height))
        range-end-y (+ tip-y (* (js/Math.sin angle) piece-height))
        ;; Create preview attacker to find targets (use world coords)
        preview-attacker {:x x :y y :size size :orientation :pointing :angle angle}
        board (:board game)
        ;; Only highlight the closest target (per Icehouse rules)
        closest-target (find-closest-target preview-attacker player-id board)]
    ;; Highlight closest target (green)
    (when closest-target
      (draw-target-highlight ctx closest-target true zoom-state))
    ;; Draw range line from tip
    (set! (.-strokeStyle ctx) "rgba(255,100,100,0.7)")
    (set-line-width ctx 3 zoom-state)
    (set! (.-lineCap ctx) "round")
    (.beginPath ctx)
    (.moveTo ctx tip-x tip-y)
    (.lineTo ctx range-end-x range-end-y)
    (.stroke ctx)
    ;; Draw range end marker
    (.beginPath ctx)
    (.arc ctx range-end-x range-end-y 5 0 (* 2 js/Math.PI))
    (.stroke ctx)))

(defn- get-preview-colour
  "Get the colour for a preview piece, handling captured pieces."
  [game player-id size captured? default-colour]
  (if captured?
    (let [player-data (get-in game [:players (keyword player-id)])
          captured-pieces (or (:captured player-data) [])
          cap-piece (utils/get-captured-piece captured-pieces size)]
      (or (:colour cap-piece) default-colour))
    default-colour))

(defn- calculate-preview-angle
  "Calculate the angle for the preview piece based on drag state."
  [drag-state]
  (let [{:keys [start-x start-y current-x current-y locked-angle]} drag-state
        in-shift-mode? (and (= start-x current-x) (= start-y current-y))]
    (if in-shift-mode?
      (or locked-angle 0)
      (if (and current-x current-y)
        (geo/calculate-angle start-x start-y current-x current-y)
        0))))

(defn- draw-direction-line
  "Draw the direction indicator line from start to current position."
  [ctx draw-start-x draw-start-y draw-current-x draw-current-y zoom-state]
  (set! (.-strokeStyle ctx) "rgba(255,255,255,0.5)")
  (set-line-width ctx 2 zoom-state)
  (.beginPath ctx)
  (.moveTo ctx draw-start-x draw-start-y)
  (.lineTo ctx draw-current-x draw-current-y)
  (.stroke ctx))

(defn- draw-preview-piece
  "Draw the preview piece with appropriate colour based on overlap state."
  [ctx x y size colour orientation angle has-overlap? zoom-state]
  (.save ctx)
  (set! (.-globalAlpha ctx) const/preview-alpha)
  (let [draw-colour (if has-overlap? "#ff3333" colour)]
    (draw-pyramid ctx x y size draw-colour orientation angle {:zoom-state zoom-state}))
  (.restore ctx))

(defn scale-to-world-coords
  "Scale coordinates from zoomed space to world space for rendering.
   Uses zoom-state scale if provided, otherwise returns original coords."
  [x y zoom-state]
  (let [scale (if zoom-state (:scale zoom-state) 1)]
    [(* x scale) (* y scale)]))

(defn draw-drag-preview
  "Draw the preview of the piece being dragged/placed"
  [ctx game drag-state selected-piece player-colour player-id zoom-state]
  (let [{:keys [start-x start-y current-x current-y]} drag-state
        {:keys [size orientation captured?]} selected-piece
        preview-colour (get-preview-colour game player-id size captured? player-colour)
        [draw-start-x draw-start-y] (scale-to-world-coords start-x start-y zoom-state)
        [draw-current-x draw-current-y] (scale-to-world-coords current-x current-y zoom-state)
        in-shift-mode? (and (= start-x current-x) (= start-y current-y))
        angle (calculate-preview-angle drag-state)
        preview-piece {:x draw-start-x :y draw-start-y :size size :orientation orientation :angle angle}
        overlapping (find-overlapping-pieces preview-piece (:board game))]
    ;; Draw direction line (only in normal mode)
    (when (and current-x current-y (not in-shift-mode?))
      (draw-direction-line ctx draw-start-x draw-start-y draw-current-x draw-current-y zoom-state))
    ;; Draw attack preview for attacking pieces
    (when (and (geo/pointing? selected-piece) current-x current-y)
      (draw-attack-preview ctx game draw-start-x draw-start-y angle size player-id zoom-state))
    ;; Highlight overlapping pieces
    (doseq [piece overlapping]
      (draw-overlap-highlight ctx piece zoom-state))
    ;; Draw the preview piece
    (draw-preview-piece ctx draw-start-x draw-start-y size preview-colour
                        orientation angle (seq overlapping) zoom-state)))

(defn draw-with-preview [ctx game drag-state selected-piece player-colour hover-pos player-id zoom-state]
  "Draw the board and optionally a preview of the piece being placed"
  (apply-zoom ctx zoom-state)
  (draw-board ctx game hover-pos player-id {:zoom-state zoom-state})
  ;; Draw preview if dragging
  (when drag-state
    (draw-drag-preview ctx game drag-state selected-piece player-colour player-id zoom-state))
  (restore-zoom ctx zoom-state))

;; =============================================================================
;; Stash SVG Drawing
;; =============================================================================

(defn draw-stash-pyramid [size colour & [{:keys [captured? count]}]]
  "Returns SVG element for a pyramid in the stash, optionally with count inside"
  (let [[width height] (get const/stash-sizes size [24 36])]
    [:svg {:width width :height height :style {:display "inline-block" :margin "1px"}}
     [:polygon {:points (str (/ width 2) ",0 0," height " " width "," height)
                :fill colour
                :stroke (if captured? theme/gold "#000")
                :stroke-width (if captured? "2" "1")}]
     (when count
       [:text {:x (/ width 2)
               :y (* height 0.7)
               :text-anchor "middle"
               :dominant-baseline "middle"
               :font-size (case size :large "18" :medium "14" :small "12")
               :font-weight "bold"
               :fill "#000"}
        count])]))
