(ns icehouse.game
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.websocket :as ws]))

;; =============================================================================
;; Game Constants (should match backend)
;; =============================================================================

;; Canvas/play area dimensions
(def canvas-width 1000)
(def canvas-height 750)
(def grid-size 50)

;; Piece sizes for canvas rendering (base width in pixels)
;; Sized so small height = large base, medium is halfway between
(def piece-sizes {:small 40 :medium 50 :large 60})
(def default-piece-size 40)  ;; Fallback for unknown piece sizes

;; Piece sizes for stash SVG rendering [width height]
;; 3:2 height:base ratio, small height = large base
(def stash-sizes {:small [24 36] :medium [30 45] :large [36 54]})

;; Default piece counts per player
(def default-pieces {:small 5 :medium 5 :large 5})

;; Points per piece size (pip values)
(def pips {:small 1 :medium 2 :large 3})

(defn piece-pips
  "Get pip value for a piece (1 for small, 2 for medium, 3 for large).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (get pips (keyword (:size piece)) 0)
    0))

;; Geometry constants
(def tip-offset-ratio 0.75)        ;; Triangle tip extends 0.75 * base-size from center

;; Rendering constants
(def preview-alpha 0.6)            ;; Transparency for piece preview
(def direction-line-alpha 0.5)     ;; Transparency for direction indicator line
(def range-indicator-alpha 0.7)    ;; Transparency for attack range indicator
(def zoom-scale 4)                 ;; Scale factor for zoom mode
(def min-line-width 0.5)           ;; Minimum line width for visibility when zoomed

;; Game rules
(def attack-unlock-threshold 2)    ;; Number of pieces before attacking is allowed
(def timer-urgent-threshold-ms 30000) ;; Timer turns red in last 30 seconds

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn set-line-width
  "Set line width, accounting for zoom scale if present. Maintains minimum width for visibility."
  [ctx width zoom-state]
  (set! (.-lineWidth ctx)
        (if zoom-state
          (let [{:keys [scale]} zoom-state
                scaled-width (/ width scale)]
            ;; Ensure minimum width for visibility when zoomed
            (max min-line-width scaled-width))
          width)))

(defn calculate-angle
  "Calculate angle in radians from point (x1,y1) to (x2,y2)"
  [x1 y1 x2 y2]
  (js/Math.atan2 (- y2 y1) (- x2 x1)))

(defn rotate-point
  "Rotate point [x y] around origin by angle (radians)"
  [[x y] angle]
  (let [cos-a (js/Math.cos angle)
        sin-a (js/Math.sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn piece-vertices
  "Get vertices of a piece in world coordinates.
   Returns nil if piece is nil or missing required coordinates."
  [{:keys [x y size orientation angle] :as piece}]
  (when (and piece x y)
    (let [base-size (get piece-sizes (keyword size) default-piece-size)
          half (/ base-size 2)
          ;; Standing pieces don't rotate - they're viewed from above and look the same at any angle
          ;; Only pointing pieces use the angle for their attack direction
          effective-angle (if (utils/standing? piece) 0 (or angle 0))
          local-verts (if (utils/standing? piece)
                        ;; Standing: square (axis-aligned, no rotation)
                        [[(- half) (- half)]
                         [half (- half)]
                         [half half]
                         [(- half) half]]
                        ;; Pointing: triangle
                        (let [half-width (* base-size tip-offset-ratio)]
                          [[half-width 0]
                           [(- half-width) (- half)]
                           [(- half-width) half]]))]
      (mapv (fn [[lx ly]]
              (let [[rx ry] (rotate-point [lx ly] effective-angle)]
                [(+ x rx) (+ y ry)]))
            local-verts))))

(defn point-in-polygon?
  "Check if point [px py] is inside polygon (ray casting algorithm)"
  [[px py] vertices]
  (let [n (count vertices)]
    (loop [i 0
           inside false]
      (if (>= i n)
        inside
        (let [j (mod (dec (+ i n)) n)
              [xi yi] (nth vertices i)
              [xj yj] (nth vertices j)
              intersect (and (not= (> yi py) (> yj py))
                             (< px (+ (/ (* (- xj xi) (- py yi))
                                         (- yj yi))
                                      xi)))]
          (recur (inc i) (if intersect (not inside) inside)))))))

;; =============================================================================
;; Attack Target Detection (for preview highlighting)
;; =============================================================================

(def parallel-threshold 0.0001)

(defn attacker-tip
  "Get the tip position of a pointing piece.
   Returns nil if piece is nil or missing required coordinates."
  [{:keys [x y size angle] :as piece}]
  (when (and piece x y)
    (let [base-size (get piece-sizes (keyword size) default-piece-size)
          tip-offset (* base-size tip-offset-ratio)
          angle (or angle 0)]
      [(+ x (* (js/Math.cos angle) tip-offset))
       (+ y (* (js/Math.sin angle) tip-offset))])))

(defn attack-direction
  "Get the unit direction vector for an attack"
  [{:keys [angle]}]
  (let [angle (or angle 0)]
    [(js/Math.cos angle) (js/Math.sin angle)]))

(defn ray-segment-intersection?
  "Check if ray from origin in direction intersects line segment [a b]"
  [[ox oy] [dx dy] [[ax ay] [bx by]]]
  (let [v1x (- ox ax)
        v1y (- oy ay)
        v2x (- bx ax)
        v2y (- by ay)
        v3x (- dy)
        v3y dx
        dot (+ (* v2x v3x) (* v2y v3y))]
    (when (> (js/Math.abs dot) parallel-threshold)
      (let [t1 (/ (- (* v2x v1y) (* v2y v1x)) dot)
            t2 (/ (+ (* v1x v3x) (* v1y v3y)) dot)]
        (and (>= t1 0) (>= t2 0) (<= t2 1))))))

(defn ray-intersects-polygon?
  "Check if ray intersects any edge of polygon"
  [origin direction vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (some #(ray-segment-intersection? origin direction %) edges)))

(defn in-front-of?
  "Check if attacker's attack ray intersects the target piece"
  [attacker target]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)]
    (ray-intersects-polygon? tip dir target-verts)))

(defn point-to-segment-distance
  "Calculate minimum distance from point p to line segment [a b]"
  [[px py] [[ax ay] [bx by]]]
  (let [abx (- bx ax)
        aby (- by ay)
        apx (- px ax)
        apy (- py ay)
        ab-len-sq (+ (* abx abx) (* aby aby))
        t (if (zero? ab-len-sq)
            0
            (max 0 (min 1 (/ (+ (* apx abx) (* apy aby)) ab-len-sq))))
        cx (+ ax (* t abx))
        cy (+ ay (* t aby))]
    (js/Math.sqrt (+ (* (- px cx) (- px cx))
                     (* (- py cy) (- py cy))))))

(defn point-to-polygon-distance
  "Calculate minimum distance from point to polygon (nearest edge)"
  [point vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (apply min (map #(point-to-segment-distance point %) edges))))

(defn attack-range
  "Get attack range for a piece (its height/length, not base width).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (let [base-size (get piece-sizes (keyword (:size piece)) default-piece-size)]
      ;; Height = 2 * tip-offset-ratio * base-size
      (* 2 tip-offset-ratio base-size))
    0))

(defn within-range?
  "Check if target is within attack range of attacker"
  [attacker target]
  (let [tip (attacker-tip attacker)
        target-verts (piece-vertices target)
        dist (point-to-polygon-distance tip target-verts)
        rng (attack-range attacker)]
    (<= dist rng)))

(defn potential-target?
  "Check if target could be attacked (in trajectory, ignoring range)"
  [attacker target attacker-player-id]
  (and (not= (utils/normalize-player-id (:player-id target))
             (utils/normalize-player-id attacker-player-id))
       (utils/standing? target)
       (in-front-of? attacker target)))

(defn valid-target?
  "Check if target is a valid attack target (in trajectory AND in range)"
  [attacker target attacker-player-id]
  (and (potential-target? attacker target attacker-player-id)
       (within-range? attacker target)))

(defn find-targets-for-attack
  "Find all potential targets and categorize them as valid (in range) or invalid (out of range)"
  [attacker player-id board]
  (let [potential (filter #(potential-target? attacker % player-id) board)]
    {:valid (filter #(within-range? attacker %) potential)
     :invalid (remove #(within-range? attacker %) potential)}))

(defn distance
  "Calculate distance between two points"
  [[x1 y1] [x2 y2]]
  (js/Math.sqrt (+ (* (- x2 x1) (- x2 x1)) (* (- y2 y1) (- y2 y1)))))

(defn piece-center
  "Get the center point of a piece"
  [piece]
  [(:x piece) (:y piece)])

(defn find-closest-target
  "Find the closest valid target for an attacking piece"
  [attacker player-id board]
  (let [{:keys [valid]} (find-targets-for-attack attacker player-id board)]
    (when (seq valid)
      (->> valid
           (map (fn [t] {:target t
                         :dist (distance (piece-center attacker) (piece-center t))}))
           (sort-by :dist)
           first
           :target))))

(defn find-piece-at
  "Find the piece at the given x,y position, or nil if none"
  [x y board]
  (first (filter (fn [piece]
                   (let [verts (piece-vertices piece)]
                     (point-in-polygon? [x y] verts)))
                 (reverse board))))  ;; Check most recently placed first

(defn find-piece-by-id
  "Find a piece by its ID"
  [board id]
  (first (filter (utils/by-id id) board)))

(defn attackers-by-target
  "Returns a map of target-id -> list of attackers targeting that piece"
  [board]
  (let [pointing-pieces (filter #(and (utils/pointing? %)
                                      (:target-id %))
                                board)]
    (group-by :target-id pointing-pieces)))

(defn attack-strength
  "Sum of pip values of all attackers"
  [attackers]
  (reduce + (map piece-pips attackers)))

(defn calculate-iced-pieces
  "Returns set of piece IDs that are successfully iced.
   Per Icehouse rules: a defender is iced when total attacker pips > defender pips"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [iced target-id attackers]
       (let [defender (find-piece-by-id board target-id)
             defender-pips (piece-pips defender)
             attacker-pips (attack-strength attackers)]
         (if (and defender (> attacker-pips defender-pips))
           (conj iced target-id)
           iced)))
     #{}
     attacks)))

;; Track last board to avoid unnecessary recalculations
(defonce ^:private last-board (atom nil))

(defn- update-cached-iced-pieces!
  "Update the cached iced pieces when the board changes"
  [board]
  (when (not= board @last-board)
    (reset! last-board board)
    (reset! state/cached-iced-pieces (calculate-iced-pieces board))))

;; Set up a watch to update cached iced pieces when game state changes
(defonce ^:private _iced-cache-watch
  (add-watch state/game-state ::iced-pieces-cache
             (fn [_ _ _ new-state]
               (when new-state
                 (update-cached-iced-pieces! (:board new-state))))))

(defn calculate-over-ice
  "Returns a map of defender-id -> {:excess pips :attackers [...] :defender-owner player-id}
   for each over-iced defender"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [result target-id attackers]
       (let [defender (find-piece-by-id board target-id)
             defender-pips (piece-pips defender)
             attacker-pips (attack-strength attackers)
             excess (- attacker-pips (+ defender-pips 1))]
         (if (and defender (> attacker-pips defender-pips) (pos? excess))
           (assoc result target-id {:excess excess
                                    :attackers attackers
                                    :defender-owner (:player-id defender)})
           result)))
     {}
     attacks)))

(defn capturable-piece?
  "Check if a piece can be captured by the current player.
   Returns true if piece is an attacker in an over-iced situation where
   the current player owns the defender and the attacker's pips <= excess."
  [piece player-id board]
  (when (and piece (utils/pointing? piece))
    (let [over-ice (calculate-over-ice board)
          target-id (:target-id piece)]
      (when-let [info (get over-ice target-id)]
        (and (= (utils/normalize-player-id (:defender-owner info))
                (utils/normalize-player-id player-id))
             (<= (piece-pips piece) (:excess info)))))))

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
        base-size (get piece-sizes size-kw default-piece-size)
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
      (let [half-width (* base-size tip-offset-ratio)]
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
  (let [verts (piece-vertices piece)]
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
                  (capturable-piece? hovered-piece current-player-id board))
         (draw-capture-highlight ctx hovered-piece zoom-state))))))

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

(defn draw-target-highlight
  "Draw a highlight around a target piece - green for valid, red for out of range"
  [ctx piece valid? zoom-state]
  (let [verts (piece-vertices piece)
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
  (let [base-size (get piece-sizes size default-piece-size)
        tip-offset (* base-size tip-offset-ratio)
        tip-x (+ x (* (js/Math.cos angle) tip-offset))
        tip-y (+ y (* (js/Math.sin angle) tip-offset))
        ;; Attack range extends piece height from tip (height = 2 * tip-offset-ratio * base-size)
        piece-height (* 2 tip-offset-ratio base-size)
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

(defn draw-drag-preview
  "Draw the preview of the piece being dragged/placed"
  [ctx game drag-state selected-piece player-colour player-id zoom-state]
  (let [{:keys [start-x start-y current-x current-y locked-angle]} drag-state
        {:keys [size orientation captured?]} selected-piece
        ;; For captured pieces, use the captured piece's original colour
        preview-colour (if captured?
                         (let [player-data (get-in game [:players (keyword player-id)])
                               captured-pieces (or (:captured player-data) [])
                               cap-piece (utils/get-captured-piece captured-pieces size)]
                           (or (:colour cap-piece) player-colour))
                         player-colour)
        ;; When zoom is active, drag coords are in scaled space - convert to world coords for drawing
        zoom-scale (if zoom-state (:scale zoom-state) 1)
        draw-start-x (* start-x zoom-scale)
        draw-start-y (* start-y zoom-scale)
        draw-current-x (* current-x zoom-scale)
        draw-current-y (* current-y zoom-scale)
        ;; In shift mode (position adjustment), start equals current, so use locked-angle
        ;; In normal mode, calculate angle from start to current
        in-shift-mode? (and (= start-x current-x) (= start-y current-y))
        angle (if in-shift-mode?
                (or locked-angle 0)
                (if (and current-x current-y)
                  (calculate-angle start-x start-y current-x current-y)
                  0))
        is-attacking? (utils/pointing? selected-piece)]
    ;; Draw a line showing the direction (only in normal mode)
    (when (and current-x current-y (not in-shift-mode?))
      (set! (.-strokeStyle ctx) "rgba(255,255,255,0.5)")
      (set-line-width ctx 2 zoom-state)
      (.beginPath ctx)
      (.moveTo ctx draw-start-x draw-start-y)
      (.lineTo ctx draw-current-x draw-current-y)
      (.stroke ctx))
    ;; Draw attack range indicator and target highlights for attacking pieces
    (when (and is-attacking? current-x current-y)
      (draw-attack-preview ctx game draw-start-x draw-start-y angle size player-id zoom-state))
    ;; Draw preview piece with transparency
    (.save ctx)
    (set! (.-globalAlpha ctx) preview-alpha)
    (draw-pyramid ctx draw-start-x draw-start-y size preview-colour orientation angle {:zoom-state zoom-state})
    (.restore ctx)))

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

(defn game-canvas []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                ui @state/ui-state
                player @state/current-player]
            (draw-with-preview ctx @state/game-state nil (:selected-piece ui)
                               (:colour player) (:hover-pos ui) (:id player) nil))))

      :component-did-update
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                ui @state/ui-state
                player @state/current-player
                zoom? (:zoom-active ui)
                drag (:drag ui)
                ;; When dragging, zoom center should be on the piece being placed
                ;; When not dragging, zoom center should be on hover position
                ;; Drag coordinates are stored in scaled space, so scale them back up for zoom center
                zoom-center-x (if drag
                                (* (:start-x drag) (if zoom? zoom-scale 1))
                                (if-let [hover (:hover-pos ui)]
                                  (:x hover)
                                  (/ canvas-width 2)))
                zoom-center-y (if drag
                                (* (:start-y drag) (if zoom? zoom-scale 1))
                                (if-let [hover (:hover-pos ui)]
                                  (:y hover)
                                  (/ canvas-height 2)))
                ;; Create zoom state map if zoom is active
                zoom-state (when zoom?
                             {:center-x zoom-center-x
                              :center-y zoom-center-y
                              :scale zoom-scale})]
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
              _ @state/ui-state]
          [:canvas
           {:ref #(reset! canvas-ref %)
            :width canvas-width
            :height canvas-height
            :style {:border "2px solid #4ecdc4" :cursor "crosshair"}
            :on-mouse-down
            (fn [e]
              (.preventDefault e)
              (let [{:keys [x y]} (get-canvas-coords e)
                    {:keys [size captured?]} (:selected-piece @state/ui-state)
                    zoom-active (:zoom-active @state/ui-state)
                    actual-zoom-scale (if zoom-active zoom-scale 1)
                    ;; Scale down position when zoomed to account for canvas scaling
                    adjusted-x (if zoom-active (/ x actual-zoom-scale) x)
                    adjusted-y (if zoom-active (/ y actual-zoom-scale) y)]
                ;; Only start drag if player has pieces of this size
                (when (has-pieces-of-size? size captured?)
                  (swap! state/ui-state assoc :drag {:start-x adjusted-x :start-y adjusted-y
                                                     :current-x adjusted-x :current-y adjusted-y
                                                     :last-x adjusted-x :last-y adjusted-y
                                                     :locked-angle 0}))))
            :on-mouse-move
            (fn [e]
              (let [{:keys [x y]} (get-canvas-coords e)
                    shift-held (.-shiftKey e)
                    zoom-active (:zoom-active @state/ui-state)
                    actual-zoom-scale (if zoom-active zoom-scale 1)
                    ;; Scale down movement when zoomed to account for canvas scaling
                    adjusted-x (if zoom-active (/ x actual-zoom-scale) x)
                    adjusted-y (if zoom-active (/ y actual-zoom-scale) y)]
                ;; Always update hover position for capture detection (use original unscaled coords)
                (swap! state/ui-state assoc :hover-pos {:x x :y y})
                ;; Update drag state if dragging
                (when-let [drag (:drag @state/ui-state)]
                  (let [{:keys [start-x start-y last-x last-y]} drag
                        dx (- adjusted-x last-x)
                        dy (- adjusted-y last-y)]
                    (if shift-held
                      ;; Shift held: move position by delta, keep locked angle
                      (swap! state/ui-state assoc :drag
                             (assoc drag
                                    :start-x (+ start-x dx)
                                    :start-y (+ start-y dy)
                                    :current-x (+ start-x dx)
                                    :current-y (+ start-y dy)
                                    :last-x adjusted-x :last-y adjusted-y))
                      ;; Normal: update current position for angle calculation, lock that angle
                      (let [new-angle (calculate-angle start-x start-y adjusted-x adjusted-y)]
                        (swap! state/ui-state update :drag assoc
                               :current-x adjusted-x :current-y adjusted-y
                               :last-x adjusted-x :last-y adjusted-y
                               :locked-angle new-angle)))))))
            :on-mouse-up
            (fn [e]
              (when-let [drag (:drag @state/ui-state)]
                (let [{:keys [start-x start-y current-x current-y locked-angle]} drag
                      {:keys [size orientation captured?]} (:selected-piece @state/ui-state)
                      shift-held (.-shiftKey e)
                      zoom-active (:zoom-active @state/ui-state)
                      actual-zoom-scale (if zoom-active zoom-scale 1)
                      ;; Scale coordinates back up if zoom was active (they were scaled down on mouse-down/move)
                      ;; Round coordinates to integers (schema expects :int)
                      final-x (js/Math.round (* start-x actual-zoom-scale))
                      final-y (js/Math.round (* start-y actual-zoom-scale))
                      ;; Use locked angle if available, otherwise calculate from position
                      ;; locked-angle is set during dragging and preserved through zoom transforms
                      ;; Only recalculate if there's actual distance between start and current
                      has-movement (or (not= start-x current-x) (not= start-y current-y))
                      angle (if (and has-movement (not shift-held))
                              (calculate-angle start-x start-y current-x current-y)
                              (or locked-angle 0))]
                  (ws/place-piece! final-x final-y size orientation angle nil captured?)
                  (swap! state/ui-state assoc :drag nil))))
            :on-mouse-leave
            (fn [e]
              (swap! state/ui-state assoc :hover-pos nil :drag nil))}]))})))

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
            board (:board game)]
        (when (capturable-piece? hovered player-id board)
          (ws/capture-piece! (:id hovered)))))))

(defn handle-keydown [e]
  (let [key (.-key e)]
    (case key
      "1" (swap! state/ui-state update :selected-piece assoc :size :small :captured? false)
      "2" (swap! state/ui-state update :selected-piece assoc :size :medium :captured? false)
      "3" (swap! state/ui-state update :selected-piece assoc :size :large :captured? false)
      ;; 4, 5, 6 select captured pieces dynamically based on what's available
      "4" (when-let [size (first (available-captured-sizes))]
            (swap! state/ui-state update :selected-piece assoc :size size :captured? true))
      "5" (when-let [size (second (available-captured-sizes))]
            (swap! state/ui-state update :selected-piece assoc :size size :captured? true))
      "6" (when-let [size (nth (available-captured-sizes) 2 nil)]
            (swap! state/ui-state update :selected-piece assoc :size size :captured? true))
      ("a" "A") (when (can-attack?)
                  (swap! state/ui-state update :selected-piece assoc :orientation :pointing))
      ("d" "D") (swap! state/ui-state update :selected-piece assoc :orientation :standing)
      ("c" "C") (if (get-hovered-piece)
                  ;; If hovering over a piece, try to capture it
                  (try-capture-hovered-piece!)
                  ;; Otherwise, toggle captured piece selection
                  (when (has-captured-pieces?)
                    (swap! state/ui-state update-in [:selected-piece :captured?] not)))
      "Escape" (swap! state/ui-state assoc :drag nil :show-help false :zoom-active false)
      "?" (swap! state/ui-state update :show-help not)
      ("z" "Z") (let [drag (:drag @state/ui-state)
                      currently-zoomed (:zoom-active @state/ui-state)]
                  (if drag
                    ;; Transform drag coordinates when toggling zoom mid-drag
                    (let [{:keys [start-x start-y current-x current-y last-x last-y locked-angle]} drag
                          transform-fn (if currently-zoomed
                                         ;; Zooming out: scale coordinates up
                                         #(* % zoom-scale)
                                         ;; Zooming in: scale coordinates down
                                         #(/ % zoom-scale))]
                      (swap! state/ui-state assoc
                             :zoom-active (not currently-zoomed)
                             :drag {:start-x (transform-fn start-x)
                                    :start-y (transform-fn start-y)
                                    :current-x (transform-fn current-x)
                                    :current-y (transform-fn current-y)
                                    :last-x (transform-fn last-x)
                                    :last-y (transform-fn last-y)
                                    :locked-angle locked-angle}))
                    ;; Not dragging: simple toggle
                    (swap! state/ui-state update :zoom-active not)))
      nil)))

(defn piece-selector []
  (let [ui @state/ui-state
        {:keys [size orientation captured?]} (:selected-piece ui)
        attack-allowed (can-attack?)
        has-captured (has-captured-pieces?)
        zoom? (:zoom-active ui)
        has-size? (has-pieces-of-size? size captured?)]
    [:div.piece-selector
     [:div.hotkey-display
      [:span.current-size {:style (when-not has-size? {:color theme/red})}
       (if captured?
         (case size :small "Small (4)" :medium "Medium (5)" :large "Large (6)" "Small (4)")
         (case size :small "Small (1)" :medium "Medium (2)" :large "Large (3)" "Small (1)"))
       (when-not has-size? " [NONE]")]
      [:span.separator " | "]
      [:span.current-mode
       (if (utils/standing? (:selected-piece ui)) "Defend (D)" "Attack (A)")]
      (when captured?
        [:span.captured-indicator {:style {:color theme/gold :margin-left "0.5rem"}}
         "[Captured]"])
      (when zoom?
        [:span.zoom-indicator {:style {:color "#00ff00" :margin-left "0.5rem"}}
         (str "[ZOOM " zoom-scale "x]")])]
     [:div.hotkey-hint
      (cond
        (and (not attack-allowed) has-captured)
        "1/2/3 stash, 4/5/6 captured, D defend, Z zoom, Shift+drag | ? help"

        (not attack-allowed)
        "1/2/3 size, D defend, Z zoom, Shift+drag | ? help (attack unlocks after 2 moves)"

        has-captured
        "1/2/3 stash, 4/5/6 captured, A/D mode, Z zoom, Shift+drag | ? help"

        :else
        "1/2/3 size, A/D mode, Z zoom, Shift+drag | ? help")]]))

(defn draw-stash-pyramid [size colour & [{:keys [captured?]}]]
  "Returns SVG element for a pyramid in the stash"
  (let [[width height] (get stash-sizes size [24 36])]
    [:svg {:width width :height height :style {:display "inline-block" :margin "1px"}}
     [:polygon {:points (str (/ width 2) ",0 0," height " " width "," height)
                :fill colour
                :stroke (if captured? theme/gold "#000")
                :stroke-width (if captured? "2" "1")}]]))

(defn piece-size-row [size label pieces colour & [{:keys [captured? selected?]}]]
  [:div.piece-row {:style (when selected?
                            {:background "rgba(255, 255, 255, 0.15)"
                             :border-radius "4px"
                             :box-shadow "0 0 8px rgba(255, 255, 255, 0.3)"})}
   [:span.size-label label]
   (for [i (range (get pieces size 0))]
     ^{:key (str (name size) "-" i)}
     [draw-stash-pyramid size colour {:captured? captured?}])])

(defn player-stash [player-id player-data]
  "Renders a single player's stash of unplayed pieces"
  (let [pieces (or (:pieces player-data) default-pieces)
        captured (or (:captured player-data) [])  ;; Now a list of {:size :colour}
        colour (or (:colour player-data) "#888")
        player-name (or (:name player-data) "Player")
        is-me (= (name player-id) (:id @state/current-player))
        has-captured? (pos? (count captured))
        ;; Get selection state for highlighting
        {:keys [size captured?]} (when is-me (:selected-piece @state/ui-state))
        ;; Group captured pieces by size for selection highlighting
        captured-by-size (when is-me (group-by #(keyword (:size %)) captured))]
    [:div.player-stash {:class (when is-me "is-me")}
     [:div.stash-header {:style {:color colour}}
      player-name
      (when is-me " (you)")]
     [:div.stash-pieces
      [piece-size-row :large "L" pieces colour
       {:selected? (and is-me (not captured?) (= size :large))}]
      [piece-size-row :medium "M" pieces colour
       {:selected? (and is-me (not captured?) (= size :medium))}]
      [piece-size-row :small "S" pieces colour
       {:selected? (and is-me (not captured?) (= size :small))}]]
     (when has-captured?
       [:div.captured-pieces
        [:div.captured-header {:style {:color theme/gold :font-size "0.8em" :margin-top "0.5rem"}}
         "Captured:"]
        ;; Group captured pieces by size and render with selection indicator
        (for [sz [:large :medium :small]
              :let [caps (get captured-by-size sz)]
              :when (seq caps)]
          ^{:key (str "cap-row-" (name sz))}
          [:div.captured-row {:style (when (and is-me captured? (= size sz))
                                       {:background "rgba(255, 215, 0, 0.2)"
                                        :border-radius "4px"
                                        :box-shadow "0 0 8px rgba(255, 215, 0, 0.4)"})}
           (for [[idx cap-piece] (map-indexed vector caps)]
             ^{:key (str "cap-" (name sz) "-" idx)}
             [draw-stash-pyramid sz (:colour cap-piece) {:captured? true}])])])]))

(defn stash-panel [position]
  "Renders stash panels for players on left or right side"
  (let [game @state/game-state
        players-map (:players game)
        player-list (vec (sort (keys players-map)))
        ;; Left gets players at indices 0, 2; Right gets 1, 3
        indices (if (= position :left) [0 2] [1 3])
        panel-players (keep #(when-let [pid (get player-list %)]
                               [pid (get players-map pid)])
                            indices)]
    [:div.stash-panel {:class (name position)}
     (for [[pid pdata] panel-players]
       ^{:key pid}
       [player-stash pid pdata])]))

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
        finished-set (or (:finished game) #{})
        players-map (:players game)
        player-count (count players-map)
        finished-count (count finished-set)
        i-finished? (contains? finished-set player-id)]
    [:div.finish-controls
     {:style {:display "flex"
              :align-items "center"
              :gap "0.5rem"}}
     [:button.finish-btn
      {:style {:padding "0.25rem 0.75rem"
               :font-size "0.9rem"
               :cursor (if i-finished? "default" "pointer")
               :background (if i-finished? theme/green theme/button-inactive)
               :color "#fff"
               :border "none"
               :border-radius "4px"
               :opacity (if i-finished? 0.8 1)}
       :disabled i-finished?
       :on-click #(when-not i-finished? (ws/finish!))}
      (if i-finished? "Finished" "Finish")]
     (when (pos? finished-count)
       [:span.finish-status
        {:style {:font-size "0.8rem"
                 :color "#aaa"}}
        (str finished-count "/" player-count)])]))

(defn game-results-overlay []
  "Display final scores when game ends"
  (when-let [result @state/game-result]
    (let [scores (:scores result)
          icehouse-players (set (:icehouse-players result))
          game @state/game-state
          players-map (:players game)
          ;; Sort by score descending
          sorted-scores (sort-by (fn [[_ score]] (- score)) scores)]
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
                  is-winner? (= score (apply max (vals scores)))]
              ^{:key player-id}
              [:tr {:style {:background (when is-winner? "#e8f5e9")}}
               [:td {:style {:text-align "left" :padding "0.5rem"}}
                [:span {:style {:color player-colour :font-weight "bold"}}
                 player-name]
                (when in-icehouse?
                  [:span {:style {:color theme/red :margin-left "0.5rem" :font-size "0.8em"}}
                   "(Icehouse!)"])]
               [:td {:style {:text-align "right" :padding "0.5rem" :font-size "1.2em"}}
                score]]))]]
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
         "Back to Lobby"]]])))

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
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "Shift + Drag"]
         [:td {:style {:padding "0.5rem"}} "Adjust position without changing angle"]]
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
           [finish-button]
           [game-timer]]]
         [error-display]
         [game-results-overlay]
         [help-overlay]
         [piece-selector]
         [:div.game-area
          [stash-panel :left]
          [game-canvas]
          [stash-panel :right]]])})))

