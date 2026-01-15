(ns icehouse.game
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.geometry :as geo]
            [icehouse.game-logic :as logic]
            [icehouse.constants :as const]
            [icehouse.websocket :as ws]))

;; Forward declarations for functions used before definition
(declare has-pieces-of-size?)

;; =============================================================================
;; Game Constants
;; =============================================================================

;; Canvas/play area dimensions
(def canvas-width 1000)
(def canvas-height 750)
(def grid-size 50)

;; Piece sizes for stash SVG rendering [width height]
(def stash-sizes {:small [24 36] :medium [30 45] :large [36 54]})

;; Default piece counts per player
(def default-pieces {:small 5 :medium 5 :large 5})

;; Rendering constants
(def preview-alpha 0.6)
(def zoom-scale 4)
(def min-line-width 0.5)

;; Game rules
(def attack-unlock-threshold 2)
(def timer-urgent-threshold-ms 30000)

;; Local state for stash drag (not in schema-validated ui-state)
;; Tracks when user starts dragging from their stash before entering canvas
(defonce stash-drag-pending (r/atom nil))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn start-placement-cooldown!
  "Start the placement cooldown indicator. Called after a piece is placed
   or when player tries to place too early."
  [remaining-ms total-throttle-ms]
  (let [now (js/Date.now)]
    (swap! state/ui-state assoc :throttle-warning
           {:throttle-ends-at (+ now remaining-ms)
            :throttle-duration-ms total-throttle-ms})))

(defn check-placement-throttle
  "Check if placement is allowed based on throttle timing.
   Returns map with :can-place? :throttle-ms :time-since-last"
  [last-placement-time]
  (let [throttle-sec (get-in @state/game-state [:options :placement-throttle] const/default-placement-throttle-sec)
        throttle-ms (* throttle-sec 1000)
        now (js/Date.now)
        time-since-last (- now last-placement-time)]
    {:can-place? (>= time-since-last throttle-ms)
     :throttle-ms throttle-ms
     :time-since-last time-since-last}))

(defn adjust-coords-for-zoom
  "Scale down mouse coordinates when zoom is active.
   Returns [adjusted-x adjusted-y]."
  [x y zoom-active]
  (if zoom-active
    [(/ x zoom-scale) (/ y zoom-scale)]
    [x y]))

(defn scale-coords-for-placement
  "Scale up coordinates for placement when zoom is active.
   Returns [scaled-x scaled-y] rounded to integers."
  [x y zoom-active]
  (if zoom-active
    [(js/Math.round (* x zoom-scale))
     (js/Math.round (* y zoom-scale))]
    [(js/Math.round x) (js/Math.round y)]))

(defn scale-to-world-coords
  "Scale coordinates from zoomed space to world space for rendering.
   Uses zoom-state scale if provided, otherwise returns original coords."
  [x y zoom-state]
  (let [scale (if zoom-state (:scale zoom-state) 1)]
    [(* x scale) (* y scale)]))

(defn transform-drag-for-zoom-toggle
  "Transform drag coordinates when toggling zoom on or off.
   When zooming in (currently-zoomed false), scales coords down.
   When zooming out (currently-zoomed true), scales coords up."
  [drag currently-zoomed]
  (let [{:keys [start-x start-y current-x current-y last-x last-y locked-angle from-stash?]} drag
        transform-fn (if currently-zoomed
                       #(* % zoom-scale)  ;; Zooming out: scale up
                       #(/ % zoom-scale))]  ;; Zooming in: scale down
    {:start-x (transform-fn start-x)
     :start-y (transform-fn start-y)
     :current-x (transform-fn current-x)
     :current-y (transform-fn current-y)
     :last-x (transform-fn last-x)
     :last-y (transform-fn last-y)
     :locked-angle locked-angle
     :from-stash? from-stash?}))

(defn set-line-width
  "Set line width, accounting for zoom scale if present. Maintains minimum width for visibility."
  [ctx width zoom-state]
  (set! (.-lineWidth ctx)
        (if zoom-state
          (let [{:keys [scale]} zoom-state
                scaled-width (/ width scale)]
            (max min-line-width scaled-width))
          width)))

(defn calculate-zoom-state
  "Calculate zoom state for rendering based on current drag and hover position.
   When dragging, zoom centers on the piece being placed.
   When not dragging, zoom centers on hover position or canvas center."
  [zoom-active drag hover-pos]
  (when zoom-active
    (let [;; Drag coordinates are stored in scaled space, so scale them back up for zoom center
          zoom-center-x (if drag
                          (* (:start-x drag) zoom-scale)
                          (if hover-pos
                            (:x hover-pos)
                            (/ canvas-width 2)))
          zoom-center-y (if drag
                          (* (:start-y drag) zoom-scale)
                          (if hover-pos
                            (:y hover-pos)
                            (/ canvas-height 2)))]
      {:center-x zoom-center-x
       :center-y zoom-center-y
       :scale zoom-scale})))

(defn try-start-drag!
  "Attempt to start a drag operation at the given coordinates.
   Returns true if drag started, false otherwise.
   Shows throttle warning if placement is blocked by cooldown."
  [adjusted-x adjusted-y size captured? from-stash?]
  (let [{:keys [last-placement-time]} @state/ui-state
        {:keys [can-place? throttle-ms time-since-last]} (check-placement-throttle last-placement-time)]
    (if (and can-place? (has-pieces-of-size? size captured?))
      (do
        (swap! state/ui-state assoc :drag {:start-x adjusted-x :start-y adjusted-y
                                           :current-x adjusted-x :current-y adjusted-y
                                           :last-x adjusted-x :last-y adjusted-y
                                           :locked-angle 0
                                           :from-stash? from-stash?})
        true)
      (do
        ;; Show warning if throttled (and they have pieces)
        (when (and (not can-place?) (has-pieces-of-size? size captured?))
          (start-placement-cooldown! (- throttle-ms time-since-last) throttle-ms))
        false))))

(defn update-drag-position!
  "Update drag state based on mouse movement.
   In position-adjust mode (shift or move-mode), piece follows cursor.
   In normal mode, updates angle based on start-to-current vector."
  [adjusted-x adjusted-y position-adjust?]
  (when-let [drag (:drag @state/ui-state)]
    (let [{:keys [start-x start-y last-x last-y]} drag
          dx (- adjusted-x last-x)
          dy (- adjusted-y last-y)
          ;; Read fresh from-stash? state to catch key presses during drag
          current-from-stash? (:from-stash? (:drag @state/ui-state))
          ;; Stash drags and position-adjust mode: piece follows cursor
          follow-cursor? (or position-adjust? current-from-stash?)]
      (if follow-cursor?
        ;; Position-adjust mode: move position by delta, keep locked angle
        (swap! state/ui-state assoc :drag
               (assoc drag
                      :start-x (+ start-x dx)
                      :start-y (+ start-y dy)
                      :current-x (+ start-x dx)
                      :current-y (+ start-y dy)
                      :last-x adjusted-x :last-y adjusted-y))
        ;; Normal: update current position for angle calculation, lock that angle
        (let [new-angle (geo/calculate-angle start-x start-y adjusted-x adjusted-y)]
          (swap! state/ui-state update :drag assoc
                 :current-x adjusted-x :current-y adjusted-y
                 :last-x adjusted-x :last-y adjusted-y
                 :locked-angle new-angle))))))

(defn complete-placement!
  "Complete a piece placement from the current drag state.
   Handles coordinate scaling for zoom and sends placement to server."
  [shift-held]
  (when-let [drag (:drag @state/ui-state)]
    (let [{:keys [start-x start-y current-x current-y locked-angle]} drag
          {:keys [selected-piece zoom-active move-mode]} @state/ui-state
          {:keys [size orientation captured?]} selected-piece
          position-adjust? (or shift-held move-mode)
          ;; Scale coordinates back up if zoom was active
          [final-x final-y] (scale-coords-for-placement start-x start-y zoom-active)
          ;; Use locked angle if available, otherwise calculate from position
          has-movement (or (not= start-x current-x) (not= start-y current-y))
          angle (if (and has-movement (not position-adjust?))
                  (geo/calculate-angle start-x start-y current-x current-y)
                  (or locked-angle 0))]
      (ws/place-piece! final-x final-y size orientation angle nil captured?)
      ;; Clear drag state but don't start cooldown - that happens when server confirms placement
      (swap! state/ui-state assoc :drag nil))))

;; =============================================================================
;; Attack Target Detection (for preview highlighting)
;; =============================================================================

(defn potential-target?
  "Check if target could be attacked (in trajectory, ignoring range)"
  [attacker target attacker-player-id]
  (and (not= (utils/normalize-player-id (:player-id target))
             (utils/normalize-player-id attacker-player-id))
       (geo/standing? target)
       (geo/in-front-of? attacker target)))

(defn valid-target?
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

;; Core game logic functions moved to icehouse.game-logic (shared .cljc)
;; Use logic/find-piece-by-id, logic/attackers-by-target, logic/attack-strength,
;; logic/calculate-iced-pieces, logic/calculate-over-ice, logic/capturable-piece?

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

(defn get-hovered-piece
  "Get the piece currently under the mouse cursor, if any"
  []
  (when-let [{:keys [x y]} (:hover-pos @state/ui-state)]
    (when-let [game @state/game-state]
      (find-piece-at x y (:board game)))))

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

(defn draw-board
  "Draw the game board. opts can include :iced-pieces to provide pre-computed iced pieces and :zoom-state for zoom rendering."
  ([ctx game hover-pos current-player-id]
   (draw-board ctx game hover-pos current-player-id nil))
  ([ctx game hover-pos current-player-id opts]
   (set! (.-fillStyle ctx) theme/board-background)
   (.fillRect ctx 0 0 canvas-width canvas-height)

   (set! (.-strokeStyle ctx) theme/grid-color)
   (set-line-width ctx 1 (:zoom-state opts))
   (doseq [x (range 0 canvas-width grid-size)]
     (.beginPath ctx)
     (.moveTo ctx x 0)
     (.lineTo ctx x canvas-height)
     (.stroke ctx))
   (doseq [y (range 0 canvas-height grid-size)]
     (.beginPath ctx)
     (.moveTo ctx 0 y)
     (.lineTo ctx canvas-width y)
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

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

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

(defn find-overlapping-pieces
  "Find all board pieces that overlap with a preview piece"
  [preview-piece board]
  (filter #(geo/pieces-intersect? preview-piece %) board))

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
  (set! (.-globalAlpha ctx) preview-alpha)
  (let [draw-colour (if has-overlap? "#ff3333" colour)]
    (draw-pyramid ctx x y size draw-colour orientation angle {:zoom-state zoom-state}))
  (.restore ctx))

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

(defn has-pieces-of-size? [size use-captured?]
  "Returns true if current player has pieces of the given size to place"
  (let [game @state/game-state
        player-id (utils/normalize-player-id (:id @state/current-player))
        player-data (get-in game [:players player-id])]
    (if use-captured?
      ;; Check captured pieces
      (let [captured (or (:captured player-data) [])]
        (pos? (utils/count-captured-by-size captured size)))
      ;; Check regular pieces
      (let [pieces (or (:pieces player-data) {})]
        (pos? (get pieces size 0))))))

(defn available-captured-sizes []
  "Returns a vector of distinct sizes of captured pieces in order they appear"
  (let [game @state/game-state
        player-id (utils/normalize-player-id (:id @state/current-player))
        player-data (get-in game [:players player-id])
        captured (or (:captured player-data) [])]
    (vec (distinct (map #(keyword (:size %)) captured)))))

(defn- handle-canvas-mousedown
  "Handles mousedown on canvas - starts a new drag from clicked position.
   Clears any pending stash drag since we're clicking directly on canvas."
  [e]
  (.preventDefault e)
  (let [{:keys [x y]} (get-canvas-coords e)
        {:keys [selected-piece zoom-active]} @state/ui-state
        {:keys [size captured?]} selected-piece
        [adjusted-x adjusted-y] (adjust-coords-for-zoom x y zoom-active)]
    (reset! stash-drag-pending nil)
    (try-start-drag! adjusted-x adjusted-y size captured? false)))

(defn- handle-canvas-mouseenter
  "Handles mouseenter on canvas - starts drag if user dragged from stash.
   Only triggers if mouse button is held (buttons > 0)."
  [e]
  (when (and @stash-drag-pending (pos? (.-buttons e)))
    (let [{:keys [x y]} (get-canvas-coords e)
          {:keys [size captured?]} @stash-drag-pending
          {:keys [zoom-active]} @state/ui-state
          [adjusted-x adjusted-y] (adjust-coords-for-zoom x y zoom-active)]
      (try-start-drag! adjusted-x adjusted-y size captured? true)
      (reset! stash-drag-pending nil))))

(defn- handle-canvas-mousemove
  "Handles mousemove on canvas - updates hover position and drag state.
   Also handles stash drag continuation if mouse was already on canvas when stash clicked."
  [e]
  (let [{:keys [x y]} (get-canvas-coords e)
        shift-held (.-shiftKey e)
        {:keys [zoom-active move-mode]} @state/ui-state
        [adjusted-x adjusted-y] (adjust-coords-for-zoom x y zoom-active)
        position-adjust? (or shift-held move-mode)]
    ;; Always update hover position for capture detection (use original unscaled coords)
    (swap! state/ui-state assoc :hover-pos {:x x :y y})
    ;; Check for pending stash drag (mouse was already on canvas when stash clicked)
    (when (and @stash-drag-pending (pos? (.-buttons e)))
      (let [{:keys [size captured?]} @stash-drag-pending]
        (try-start-drag! adjusted-x adjusted-y size captured? true)
        (reset! stash-drag-pending nil)))
    ;; Update drag state if dragging
    (update-drag-position! adjusted-x adjusted-y position-adjust?)))

(defn- handle-canvas-mouseup
  "Handles mouseup on canvas - completes the piece placement."
  [e]
  (complete-placement! (.-shiftKey e)))

(defn- handle-canvas-mouseleave
  "Handles mouseleave on canvas - clears hover position and drag state."
  [_e]
  (swap! state/ui-state assoc :hover-pos nil :drag nil))

(defn game-canvas []
  (let [canvas-ref (r/atom nil)
        ;; Handler to clear stash-drag-pending on mouseup anywhere
        global-mouseup-handler (fn [_e]
                                 (reset! stash-drag-pending nil))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        ;; Add global mouseup listener to clear stash-drag-pending
        (.addEventListener js/document "mouseup" global-mouseup-handler)
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                ui @state/ui-state
                player @state/current-player]
            (draw-with-preview ctx @state/game-state nil (:selected-piece ui)
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
                zoom-state (calculate-zoom-state (:zoom-active ui) (:drag ui) (:hover-pos ui))]
            (draw-with-preview ctx
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
              _ @stash-drag-pending]
          [:canvas
           {:ref #(reset! canvas-ref %)
            :width canvas-width
            :height canvas-height
            :style {:border "2px solid #4ecdc4" :cursor "crosshair"}
            :on-mouse-down handle-canvas-mousedown
            :on-mouse-enter handle-canvas-mouseenter
            :on-mouse-move handle-canvas-mousemove
            :on-mouse-up handle-canvas-mouseup
            :on-mouse-leave handle-canvas-mouseleave}]))})))

(defn can-attack? []
  "Returns true if attacking is allowed (after first few moves)"
  (let [game @state/game-state
        board-count (count (:board game))]
    (>= board-count attack-unlock-threshold)))

(defn has-captured-pieces? []
  "Returns true if current player has any captured pieces"
  (let [game @state/game-state
        player-id (utils/normalize-player-id (:id @state/current-player))
        player-data (get-in game [:players player-id])
        captured (or (:captured player-data) [])]
    (pos? (count captured))))

(defn try-capture-hovered-piece!
  "Attempt to capture the piece under the cursor"
  []
  (when-let [hovered (get-hovered-piece)]
    (when-let [game @state/game-state]
      (let [player-id (utils/normalize-player-id (:id @state/current-player))
            board (:board game)
            over-ice @state/cached-over-ice]
        (when (logic/capturable-piece? hovered player-id board over-ice)
          (ws/capture-piece! (:id hovered)))))))

;; Keyboard handlers for dispatch map pattern
(defn- select-regular-piece
  "Handler for selecting regular pieces (keys 1-3).
   Does nothing if the player is icehoused."
  [size]
  (fn []
    (let [my-id (:id @state/current-player)
          is-icehoused? (and my-id (contains? @state/icehoused-players my-id))]
      (when-not is-icehoused?
        (swap! state/ui-state update :selected-piece assoc :size size :captured? false)))))

(defn- select-captured-piece
  "Handler for selecting captured pieces (keys 4-6)."
  [index]
  (fn []
    (when-let [size (nth (available-captured-sizes) index nil)]
      (swap! state/ui-state update :selected-piece assoc :size size :captured? true))))

(defn- handle-attack-mode
  "Handler for 'A' key - set attack orientation."
  []
  (when (can-attack?)
    (swap! state/ui-state update :selected-piece assoc :orientation :pointing)))

(defn- handle-defend-mode
  "Handler for 'D' key - set defense orientation."
  []
  (swap! state/ui-state update :selected-piece assoc :orientation :standing))

(defn- handle-capture-key
  "Handler for 'C' key - capture hovered piece or toggle captured selection."
  []
  (if (get-hovered-piece)
    (try-capture-hovered-piece!)
    (when (has-captured-pieces?)
      (swap! state/ui-state update-in [:selected-piece :captured?] not))))

(defn- handle-escape
  "Handler for Escape key - clear all modes."
  []
  (swap! state/ui-state assoc :drag nil :show-help false :zoom-active false :move-mode false))

(defn- handle-help-toggle
  "Handler for '?' key - toggle help overlay."
  []
  (swap! state/ui-state update :show-help not))

(defn- handle-move-mode
  "Handler for 'M' key - toggle move mode or switch from stash drag to rotation."
  []
  (let [drag (:drag @state/ui-state)]
    (if (and drag (:from-stash? drag))
      (do
        (swap! state/ui-state update :drag assoc :from-stash? false)
        (swap! state/ui-state assoc :move-mode false))
      (swap! state/ui-state update :move-mode not))))

(defn- handle-shift
  "Handler for Shift key - switch from stash drag to rotation mode."
  []
  (let [drag (:drag @state/ui-state)]
    (when (and drag (:from-stash? drag))
      (swap! state/ui-state update :drag assoc :from-stash? false))))

(defn- handle-zoom-toggle
  "Handler for 'Z' key - toggle zoom mode."
  []
  (let [drag (:drag @state/ui-state)
        currently-zoomed (:zoom-active @state/ui-state)]
    (if drag
      (swap! state/ui-state assoc
             :zoom-active (not currently-zoomed)
             :drag (transform-drag-for-zoom-toggle drag currently-zoomed))
      (swap! state/ui-state update :zoom-active not))))

(def ^:private keyboard-handlers
  "Dispatch map for keyboard handlers."
  {"1" (select-regular-piece :small)
   "2" (select-regular-piece :medium)
   "3" (select-regular-piece :large)
   "4" (select-captured-piece 0)
   "5" (select-captured-piece 1)
   "6" (select-captured-piece 2)
   "a" handle-attack-mode
   "A" handle-attack-mode
   "d" handle-defend-mode
   "D" handle-defend-mode
   "c" handle-capture-key
   "C" handle-capture-key
   "Escape" handle-escape
   "?" handle-help-toggle
   "m" handle-move-mode
   "M" handle-move-mode
   "Shift" handle-shift
   "z" handle-zoom-toggle
   "Z" handle-zoom-toggle})

(defn handle-keydown [e]
  (when-let [handler (get keyboard-handlers (.-key e))]
    (handler)))

(defn piece-selector []
  (let [ui @state/ui-state
        {:keys [size orientation captured?]} (:selected-piece ui)
        my-id (:id @state/current-player)
        is-icehoused? (and my-id (contains? @state/icehoused-players my-id))
        attack-allowed (can-attack?)
        has-captured (has-captured-pieces?)
        zoom? (:zoom-active ui)
        move-mode? (:move-mode ui)
        has-size? (has-pieces-of-size? size captured?)]
    [:div.piece-selector
     [:div.hotkey-display
      [:span.current-size {:style (when-not has-size? {:color theme/red})}
       (if captured?
         (case size :small "Small (4)" :medium "Medium (5)" :large "Large (6)" "Small (4)")
         (case size :small "Small (1)" :medium "Medium (2)" :large "Large (3)" "Small (1)"))
       (when-not has-size? " [NONE]")
       (when (and (not captured?) is-icehoused?) " [LOCKED]")]
      [:span.separator " | "]
      [:span.current-mode
       (if (geo/standing? (:selected-piece ui)) "Defend (D)" "Attack (A)")]
      (when captured?
        [:span.captured-indicator {:style {:color theme/gold :margin-left "0.5rem"}}
         "[Captured]"])
      (when is-icehoused?
        [:span.icehoused-indicator {:style {:color theme/red :margin-left "0.5rem"}}
         "[ICEHOUSE]"])
      (when zoom?
        [:span.zoom-indicator {:style {:color "#00ff00" :margin-left "0.5rem"}}
         (str "[ZOOM " zoom-scale "x]")])
      (when move-mode?
        [:span.move-mode-indicator {:style {:color "#ff9800" :margin-left "0.5rem"}}
         "[MOVE]"])]
     [:div.hotkey-hint
      (cond
        ;; Icehoused players can only use captured pieces
        is-icehoused?
        (if has-captured
          "4/5/6 captured pieces only, A/D mode, Z zoom | ? help (ICEHOUSED)"
          "No captured pieces! Capture opponents to get pieces | ? help (ICEHOUSED)")

        (and (not attack-allowed) has-captured)
        "1/2/3 stash, 4/5/6 captured, D defend, Z zoom, M move | ? help"

        (not attack-allowed)
        "1/2/3 size, D defend, Z zoom, M move | ? help (attack unlocks after 2 moves)"

        has-captured
        "1/2/3 stash, 4/5/6 captured, A/D mode, Z zoom, M move | ? help"

        :else
        "1/2/3 size, A/D mode, Z zoom, M move | ? help")]]))

(defn draw-stash-pyramid [size colour & [{:keys [captured? count]}]]
  "Returns SVG element for a pyramid in the stash, optionally with count inside"
  (let [[width height] (get stash-sizes size [24 36])]
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

(defn- compute-stash-state
  "Computes selection state and hotkey mappings for a player's stash.
   Returns map with :selection, :captured-by-size, and :size-to-hotkey."
  [is-me captured]
  (let [selection (when is-me (:selected-piece @state/ui-state))
        captured-by-size (group-by #(keyword (:size %)) captured)
        available-sizes (when is-me (vec (distinct (map #(keyword (:size %)) captured))))
        size-to-hotkey (when is-me (into {} (map-indexed (fn [idx sz] [sz (+ 4 idx)]) available-sizes)))]
    {:selection selection
     :captured-by-size captured-by-size
     :size-to-hotkey size-to-hotkey}))

(defn- make-stash-drag-handler
  "Creates a handler function for starting a drag from the stash."
  [is-me]
  (when is-me
    (fn [piece-size is-captured?]
      (swap! state/ui-state update :selected-piece assoc
             :size piece-size
             :captured? is-captured?)
      (reset! stash-drag-pending {:size piece-size :captured? is-captured?}))))

(defn piece-size-row [size label pieces colour & [{:keys [captured? selected? on-start-drag]}]]
  (let [piece-count (get pieces size 0)]
    (when (pos? piece-count)
      [:div.piece-row {:style (merge (when selected?
                                       {:background "rgba(255, 255, 255, 0.15)"
                                        :border-radius "4px"
                                        :box-shadow "0 0 8px rgba(255, 255, 255, 0.3)"})
                                     (when on-start-drag
                                       {:cursor "grab"}))
                       :on-mouse-down (when on-start-drag
                                        (fn [e]
                                          (.preventDefault e)
                                          ;; Ensure captured? is boolean, not nil
                                          (on-start-drag size (boolean captured?))))}
       [:span.size-label label]
       [draw-stash-pyramid size colour {:captured? captured? :count piece-count}]])))

(defn- captured-pieces-section
  "Renders the captured pieces section of a player's stash."
  [{:keys [is-me selection captured-by-size size-to-hotkey start-stash-drag]}]
  (let [{:keys [size captured?]} selection]
    [:div.captured-pieces
     [:div.captured-header {:style {:color theme/gold :font-size "0.8em" :margin-top "0.5rem"}}
      "Captured:"]
     (for [sz [:large :medium :small]
           :let [caps (get captured-by-size sz)
                 hotkey (get size-to-hotkey sz)]
           :when (seq caps)]
       ^{:key (str "cap-row-" (name sz))}
       [:div.captured-row {:style (merge {:display "flex" :align-items "center" :gap "4px"}
                                         (when (and is-me captured? (= size sz))
                                           {:background "rgba(255, 215, 0, 0.2)"
                                            :border-radius "4px"
                                            :box-shadow "0 0 8px rgba(255, 215, 0, 0.4)"})
                                         (when is-me
                                           {:cursor "grab"}))
                           :on-mouse-down (when is-me
                                            (fn [e]
                                              (.preventDefault e)
                                              (start-stash-drag sz true)))}
        (when hotkey
          [:span.captured-hotkey {:style {:color theme/gold :font-weight "bold" :min-width "1em"}}
           (str hotkey)])
        (for [[idx cap-piece] (map-indexed vector caps)]
          ^{:key (str "cap-" (name sz) "-" idx)}
          [draw-stash-pyramid sz (:colour cap-piece) {:captured? true}])])]))

(defn player-stash
  "Renders a single player's stash of unplayed pieces.
   opts can include :read-only? true for replay mode (no interaction)."
  ([player-id player-data] (player-stash player-id player-data nil))
  ([player-id player-data opts]
   (let [read-only? (:read-only? opts)
         pieces (or (:pieces player-data) default-pieces)
         captured (or (:captured player-data) [])
         colour (or (:colour player-data) "#888")
         player-name (or (:name player-data) "Player")
         is-me (and (not read-only?) (= (name player-id) (:id @state/current-player)))
         has-captured? (pos? (count captured))
         ;; Check if this player is icehoused
         is-icehoused? (contains? @state/icehoused-players (name player-id))
         {:keys [selection captured-by-size size-to-hotkey]} (compute-stash-state is-me captured)
         {:keys [size captured?]} selection
         ;; Disable regular piece drag if player is icehoused
         start-stash-drag (if is-icehoused?
                            (fn [_ _] nil)  ;; No-op for icehoused players
                            (make-stash-drag-handler is-me))]
     [:div.player-stash {:class (str (when is-me "is-me")
                                     (when is-icehoused? " icehoused"))}
      [:div.stash-header {:style {:color colour}}
       player-name
       (when is-me " (you)")
       (when is-icehoused?
         [:span {:style {:color theme/red :margin-left "0.5rem" :font-size "0.8em"}}
          "(Icehouse!)"])]
      ;; Regular pieces - greyed out if icehoused
      [:div.stash-pieces {:style (when is-icehoused?
                                   {:opacity 0.4
                                    :pointer-events "none"})}
       [piece-size-row :small "1" pieces colour
        {:selected? (and is-me (not is-icehoused?) (not captured?) (= size :small))
         :on-start-drag (when-not is-icehoused? start-stash-drag)}]
       [piece-size-row :medium "2" pieces colour
        {:selected? (and is-me (not is-icehoused?) (not captured?) (= size :medium))
         :on-start-drag (when-not is-icehoused? start-stash-drag)}]
       [piece-size-row :large "3" pieces colour
        {:selected? (and is-me (not is-icehoused?) (not captured?) (= size :large))
         :on-start-drag (when-not is-icehoused? start-stash-drag)}]]
      ;; Captured pieces - always available, highlighted more if icehoused
      (when has-captured?
        [:div {:style (when (and is-me is-icehoused?)
                        {:background "rgba(255, 215, 0, 0.1)"
                         :border-radius "4px"
                         :padding "4px"
                         :margin-top "4px"})}
         [captured-pieces-section {:is-me is-me
                                   :selection selection
                                   :captured-by-size captured-by-size
                                   :size-to-hotkey size-to-hotkey
                                   :start-stash-drag (make-stash-drag-handler is-me)}]])])))

(defn stash-panel
  "Renders stash panels for players on left or right side.
   Current player always appears first (top-left position).
   opts can include :game-state for replay mode (uses provided state instead of atom)
   and :read-only? to disable interaction."
  ([position] (stash-panel position nil))
  ([position opts]
   (let [game (or (:game-state opts) @state/game-state)
         read-only? (:read-only? opts)
         players-map (:players game)
         my-id (when-not read-only? (:id @state/current-player))
         ;; Sort players, put current player first if in interactive mode
         sorted-players (vec (sort (keys players-map)))
         player-list (if (and my-id (some #(= (name %) my-id) sorted-players))
                       (let [sorted-others (vec (remove #(= (name %) my-id) sorted-players))]
                         (into [(keyword my-id)] sorted-others))
                       sorted-players)
         ;; Left gets players at indices 0, 2; Right gets 1, 3
         indices (if (= position :left) [0 2] [1 3])
         panel-players (keep #(when-let [pid (get player-list %)]
                                [pid (get players-map pid)])
                             indices)]
     [:div.stash-panel {:class (name position)}
      (for [[pid pdata] panel-players]
        ^{:key pid}
        [player-stash pid pdata (when read-only? {:read-only? true})])])))

(defn error-display []
  (when-let [error @state/error-message]
    [:div.error-message
     {:style {:background "#ff6b6b"
              :color "#fff"
              :padding "0.5rem 1rem"
              :border-radius "4px"
              :margin-bottom "0.5rem"
              :font-weight "bold"}}
     error]))

(defn icehouse-banner []
  "Displays a prominent banner when the current player is icehoused."
  (let [my-id (:id @state/current-player)
        is-icehoused? (and my-id (contains? @state/icehoused-players my-id))]
    (when is-icehoused?
      [:div.icehouse-banner
       {:style {:background "linear-gradient(135deg, #1a237e 0%, #311b92 100%)"
                :color "#fff"
                :padding "0.75rem 1rem"
                :border-radius "4px"
                :margin-bottom "0.5rem"
                :text-align "center"
                :border "2px solid #5c6bc0"
                :box-shadow "0 2px 8px rgba(0, 0, 0, 0.3)"}}
       [:span {:style {:font-weight "bold" :font-size "1.1em"}}
        "You're in the Icehouse!"]
       [:span {:style {:margin-left "1rem" :opacity 0.9}}
        "Only captured pieces can be played. Capture opponents to continue!"]])))

(defn placement-cooldown-indicator []
  "Subtle circular cooldown indicator with smooth animation"
  (let [animation-frame (atom nil)
        local-progress (r/atom 1)
        start-animation
        (fn start-animation []
          (letfn [(animate []
                    (if-let [warning (:throttle-warning @state/ui-state)]
                      (let [now (js/Date.now)
                            {:keys [throttle-ends-at throttle-duration-ms]} warning
                            remaining-ms (- throttle-ends-at now)]
                        (if (pos? remaining-ms)
                          (do
                            (reset! local-progress (/ remaining-ms throttle-duration-ms))
                            (reset! animation-frame (js/requestAnimationFrame animate)))
                          ;; Cooldown complete - clear state and reset frame
                          (do
                            (reset! local-progress 0)
                            (reset! animation-frame nil)
                            (swap! state/ui-state dissoc :throttle-warning))))
                      ;; No warning - reset frame
                      (reset! animation-frame nil)))]
            (animate)))]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when (:throttle-warning @state/ui-state)
          (start-animation)))

      :component-did-update
      (fn [this old-argv]
        ;; Restart animation when warning appears and no animation running
        (when (and (:throttle-warning @state/ui-state)
                   (nil? @animation-frame))
          (start-animation)))

      :component-will-unmount
      (fn [this]
        (when @animation-frame
          (js/cancelAnimationFrame @animation-frame)))

      :reagent-render
      (fn []
        (when (:throttle-warning @state/ui-state)
          (let [progress @local-progress
                ;; Small, subtle circle
                size 28
                stroke-width 3
                radius (/ (- size stroke-width) 2)
                circumference (* 2 js/Math.PI radius)
                ;; Progress goes from 1 (full wait) to 0 (ready)
                dash-offset (* circumference (- 1 progress))]
            [:div.cooldown-indicator
             {:style {:display "inline-flex"
                      :align-items "center"
                      :justify-content "center"
                      :opacity 0.6}}
             [:svg {:width size :height size
                    :style {:transform "rotate(-90deg)"}}
              ;; Background circle
              [:circle {:cx (/ size 2) :cy (/ size 2) :r radius
                        :fill "none"
                        :stroke "rgba(255,255,255,0.2)"
                        :stroke-width stroke-width}]
              ;; Progress circle - depletes as cooldown progresses
              [:circle {:cx (/ size 2) :cy (/ size 2) :r radius
                        :fill "none"
                        :stroke "#4ecdc4"
                        :stroke-width stroke-width
                        :stroke-linecap "round"
                        :stroke-dasharray circumference
                        :stroke-dashoffset dash-offset}]]])))})))

(defn game-timer []
  "Display remaining game time"
  (let [game @state/game-state
        current @state/current-time]
    (when-let [ends-at (:ends-at game)]
      (let [remaining (max 0 (- ends-at current))
            urgent? (< remaining timer-urgent-threshold-ms)]
        [:div.game-timer
         {:style {:font-family "monospace"
                  :font-size "1.2rem"
                  :padding "0.25rem 0.5rem"
                  :background (if urgent? theme/red theme/button-inactive)
                  :color "#fff"
                  :border-radius "4px"
                  :display "inline-block"
                  :animation (when urgent? "pulse 1s infinite")}}
         (utils/format-time remaining)]))))

(defn finish-button []
  "Button for players to signal they want to end the game"
  (let [game @state/game-state
        player-id (:id @state/current-player)
        finished-set (set (or (:finished game) []))
        players-map (:players game)
        player-count (count players-map)
        finished-count (count finished-set)
        i-finished? (contains? finished-set player-id)]
    [:div.finish-controls
     {:style {:display "flex"
              :align-items "center"
              :gap "0.5rem"}}
     [:button.finish-btn
      {:style {:padding "0.5rem 1rem"
               :font-size "1rem"
               :font-weight "bold"
               :cursor (if i-finished? "default" "pointer")
               :background (if i-finished? theme/green "#4a4a5e")
               :color "#fff"
               :border "2px solid #6a6a7e"
               :border-radius "6px"
               :opacity (if i-finished? 0.8 1)}
       :disabled i-finished?
       :on-click #(ws/finish!)}
      (if i-finished? "✓ Finished" "End Game")]
     (when (pos? finished-count)
       [:span.finish-status
        {:style {:font-size "0.8rem"
                 :color "#aaa"}}
        (str finished-count "/" player-count)])]))

(defn- scores-table
  "Renders the scores table for the game results overlay."
  [{:keys [sorted-scores players-map icehouse-players max-score]}]
  [:table {:style {:width "100%" :border-collapse "collapse" :margin "1rem 0"}}
   [:thead
    [:tr
     [:th {:style {:text-align "left" :padding "0.5rem"}} "Player"]
     [:th {:style {:text-align "right" :padding "0.5rem"}} "Score"]]]
   [:tbody
    (for [[player-id score] sorted-scores]
      (let [player-data (get players-map (keyword player-id))
            player-name (or (:name player-data) player-id)
            player-colour (or (:colour player-data) "#888")
            in-icehouse? (contains? icehouse-players player-id)
            is-winner? (= score max-score)]
        ^{:key player-id}
        [:tr {:style {:background (when is-winner? "#e8f5e9")}}
         [:td {:style {:text-align "left" :padding "0.5rem"}}
          [:span {:style {:color player-colour :font-weight "bold"}}
           player-name]
          (when in-icehouse?
            [:span {:style {:color theme/red :margin-left "0.5rem" :font-size "0.8em"}}
             "(Icehouse!)"])]
         [:td {:style {:text-align "right" :padding "0.5rem" :font-size "1.2em" :color "#333"}}
          (str score)]]))]])

(defn- results-action-buttons
  "Renders the action buttons for the game results overlay."
  [{:keys [game-id]}]
  [:div {:style {:display "flex" :flex-direction "column" :gap "0.5rem" :margin-top "1rem"}}
   [:button
    {:style {:padding "0.5rem 2rem"
             :font-size "1rem"
             :cursor "pointer"
             :background theme/gold
             :color "#000"
             :border "none"
             :border-radius "4px"}
     :on-click #(ws/load-game! game-id)}
    "Watch Replay"]
   [:button
    {:style {:padding "0.5rem 2rem"
             :font-size "1rem"
             :cursor "pointer"
             :background theme/green
             :color "#fff"
             :border "none"
             :border-radius "4px"}
     :on-click #(do
                  (reset! state/game-result nil)
                  (reset! state/game-state nil)
                  (reset! state/current-view :lobby))}
    "Back to Lobby"]
   [:button
    {:style {:padding "0.5rem 1rem"
             :font-size "0.8rem"
             :cursor "pointer"
             :background "transparent"
             :color "#666"
             :border "none"
             :text-decoration "underline"}
     :on-click #(ws/list-games!)}
    "Watch All Replays"]])

(defn game-results-overlay []
  "Display final scores when game ends"
  (when-let [result @state/game-result]
    (let [scores (:scores result)
          icehouse-players (set (:icehouse-players result))
          players-map (:players @state/game-state)
          sorted-scores (sort-by (fn [[_ score]] (- score)) scores)
          max-score (apply max (vals scores))]
      [:div.game-results-overlay
       {:style {:position "fixed"
                :top 0 :left 0 :right 0 :bottom 0
                :background "rgba(0,0,0,0.8)"
                :display "flex"
                :align-items "center"
                :justify-content "center"
                :z-index 1000}}
       [:div.game-results
        {:style {:background "#fff"
                 :padding "2rem"
                 :border-radius "8px"
                 :min-width "300px"
                 :text-align "center"}}
        [:h2 {:style {:margin-top 0}} "Game Over!"]
        [scores-table {:sorted-scores sorted-scores
                       :players-map players-map
                       :icehouse-players icehouse-players
                       :max-score max-score}]
        [results-action-buttons {:game-id (:game-id result)}]]])))

(defn help-overlay []
  "Display help overlay with hotkey descriptions"
  (when (:show-help @state/ui-state)
    [:div.help-overlay
     {:style {:position "fixed"
              :top 0 :left 0 :right 0 :bottom 0
              :background "rgba(0,0,0,0.85)"
              :display "flex"
              :justify-content "center"
              :align-items "center"
              :z-index 1000}
      :on-click #(swap! state/ui-state assoc :show-help false)}
     [:div.help-content
      {:style {:background theme/board-background
               :padding "2rem"
               :border-radius "8px"
               :max-width "500px"
               :color "white"}
       :on-click #(.stopPropagation %)}
      [:h2 {:style {:margin-top 0 :text-align "center"}} "Keyboard Controls"]
      [:table {:style {:width "100%" :border-collapse "collapse"}}
       [:tbody
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "1 / 2 / 3"]
         [:td {:style {:padding "0.5rem"}} "Select piece size (Small / Medium / Large)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "4 / 5 / 6"]
         [:td {:style {:padding "0.5rem"}} "Select captured piece (Small / Medium / Large)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "D"]
         [:td {:style {:padding "0.5rem"}} "Defend mode (standing piece)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "A"]
         [:td {:style {:padding "0.5rem"}} "Attack mode (pointing piece)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "C"]
         [:td {:style {:padding "0.5rem"}} "Capture piece / Toggle captured mode"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "Z"]
         [:td {:style {:padding "0.5rem"}} (str "Toggle " zoom-scale "x zoom for fine placement")]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "M"]
         [:td {:style {:padding "0.5rem"}} "Toggle move mode (adjust position, keep angle)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "Shift"]
         [:td {:style {:padding "0.5rem"}} "Hold while dragging for move mode"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "Escape"]
         [:td {:style {:padding "0.5rem"}} "Cancel placement / Close help"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "?"]
         [:td {:style {:padding "0.5rem"}} "Toggle this help"]]]]
      [:h3 {:style {:margin-top "1.5rem"}} "Gameplay Tips"]
      [:ul {:style {:padding-left "1.5rem" :line-height "1.6"}}
       [:li "Click and drag to place a piece with rotation"]
       [:li "Attack mode unlocks after placing 2 pieces"]
       [:li "Attackers must point at an opponent's defender within range"]
       [:li "Over-ice: When attack pips exceed defense, capture excess attackers"]]
      [:div {:style {:text-align "center" :margin-top "1.5rem" :color "#888"}}
       "Click anywhere or press Escape to close"]]]))

(defn game-view []
  (let [timer-interval (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (.addEventListener js/document "keydown" handle-keydown)
        ;; Start timer update interval
        (reset! timer-interval
                (js/setInterval #(reset! state/current-time (js/Date.now)) 1000)))

      :component-will-unmount
      (fn [this]
        (.removeEventListener js/document "keydown" handle-keydown)
        ;; Clear timer interval
        (when @timer-interval
          (js/clearInterval @timer-interval)))

      :reagent-render
      (fn []
        [:div.game
         [:div.game-header
          {:style {:display "flex"
                   :justify-content "space-between"
                   :align-items "center"
                   :margin-bottom "0.5rem"}}
          [:h2 {:style {:margin 0}} "Icehouse"]
          [:div {:style {:display "flex" :align-items "center" :gap "1rem"}}
           [placement-cooldown-indicator]
           [finish-button]
           [game-timer]]]
         [error-display]
         [icehouse-banner]
         [game-results-overlay]
         [help-overlay]
         [piece-selector]
         [:div.game-area
          [stash-panel :left]
          [game-canvas]
          [stash-panel :right]]])})))

