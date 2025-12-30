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

;; Game rules
(def attack-unlock-threshold 2)    ;; Number of pieces before attacking is allowed

;; =============================================================================
;; Utility Functions
;; =============================================================================

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
          angle (or angle 0)
          local-verts (if (= (keyword orientation) :standing)
                        [[(- half) (- half)]
                         [half (- half)]
                         [half half]
                         [(- half) half]]
                        (let [half-width (* base-size tip-offset-ratio)]
                          [[half-width 0]
                           [(- half-width) (- half)]
                           [(- half-width) half]]))]
      (mapv (fn [[lx ly]]
              (let [[rx ry] (rotate-point [lx ly] angle)]
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
  (and (not= (:player-id target) attacker-player-id)
       (= (keyword (:orientation target)) :standing)
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
  (first (filter #(= (:id %) id) board)))

(defn attackers-by-target
  "Returns a map of target-id -> list of attackers targeting that piece"
  [board]
  (let [pointing-pieces (filter #(and (= (keyword (:orientation %)) :pointing)
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
  (when (and piece (= (keyword (:orientation piece)) :pointing))
    (let [over-ice (calculate-over-ice board)
          target-id (:target-id piece)]
      (when-let [info (get over-ice target-id)]
        (and (= (:defender-owner info) player-id)
             (<= (piece-pips piece) (:excess info)))))))

(defn get-hovered-piece
  "Get the piece currently under the mouse cursor, if any"
  []
  (when-let [{:keys [x y]} @state/hover-pos]
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

(defn draw-pyramid [ctx x y size colour orientation angle & [{:keys [iced?]}]]
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
    (set! (.-lineWidth ctx) 2)

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
  [ctx piece]
  (let [verts (piece-vertices piece)]
    (.save ctx)
    (set! (.-strokeStyle ctx) theme/gold)
    (set! (.-lineWidth ctx) 4)
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

(defn draw-board [ctx game hover-pos current-player-id]
  (set! (.-fillStyle ctx) theme/board-background)
  (.fillRect ctx 0 0 canvas-width canvas-height)

  (set! (.-strokeStyle ctx) theme/grid-color)
  (set! (.-lineWidth ctx) 1)
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
          iced-pieces (calculate-iced-pieces board)]
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
                        {:iced? is-iced?})))
      ;; Draw capture highlight if hovering over a capturable piece
      (when (and hovered-piece
                 (capturable-piece? hovered-piece current-player-id board))
        (draw-capture-highlight ctx hovered-piece)))))

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

(defn draw-target-highlight
  "Draw a highlight around a target piece - green for valid, red for out of range"
  [ctx piece valid?]
  (let [verts (piece-vertices piece)
        color (if valid? "rgba(0,255,0,0.5)" "rgba(255,0,0,0.5)")]
    (.save ctx)
    (set! (.-strokeStyle ctx) color)
    (set! (.-lineWidth ctx) 4)
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

(defn draw-with-preview [ctx game drag-state selected-piece player-colour hover-pos player-id zoom-state]
  "Draw the board and optionally a preview of the piece being placed"
  ;; Apply zoom transform if active
  (when zoom-state
    (let [{:keys [center-x center-y scale]} zoom-state]
      (.save ctx)
      ;; Translate to zoom center, scale, translate back
      (.translate ctx center-x center-y)
      (.scale ctx scale scale)
      (.translate ctx (- center-x) (- center-y))))
  (draw-board ctx game hover-pos player-id)
  ;; Draw preview if dragging
  (when drag-state
    (let [{:keys [start-x start-y current-x current-y locked-angle]} drag-state
          {:keys [size orientation]} selected-piece
          base-size (get piece-sizes size default-piece-size)
          ;; In shift mode (position adjustment), start equals current, so use locked-angle
          ;; In normal mode, calculate angle from start to current
          in-shift-mode? (and (= start-x current-x) (= start-y current-y))
          angle (if in-shift-mode?
                  (or locked-angle 0)
                  (if (and current-x current-y)
                    (calculate-angle start-x start-y current-x current-y)
                    0))
          is-attacking? (= orientation :pointing)]
      ;; Draw a line showing the direction (only in normal mode)
      (when (and current-x current-y (not in-shift-mode?))
        (set! (.-strokeStyle ctx) "rgba(255,255,255,0.5)")
        (set! (.-lineWidth ctx) 2)
        (.beginPath ctx)
        (.moveTo ctx start-x start-y)
        (.lineTo ctx current-x current-y)
        (.stroke ctx))
      ;; Draw attack range indicator and target highlights for attacking pieces
      (when (and is-attacking? current-x current-y)
        (let [tip-offset (* base-size tip-offset-ratio)
              tip-x (+ start-x (* (js/Math.cos angle) tip-offset))
              tip-y (+ start-y (* (js/Math.sin angle) tip-offset))
              ;; Attack range extends piece height from tip (height = 2 * tip-offset-ratio * base-size)
              piece-height (* 2 tip-offset-ratio base-size)
              range-end-x (+ tip-x (* (js/Math.cos angle) piece-height))
              range-end-y (+ tip-y (* (js/Math.sin angle) piece-height))
              ;; Create preview attacker to find targets
              preview-attacker {:x start-x :y start-y :size size :orientation :pointing :angle angle}
              board (:board game)
              {:keys [valid]} (find-targets-for-attack preview-attacker player-id board)]
          ;; Highlight valid targets (green)
          (doseq [target valid]
            (draw-target-highlight ctx target true))
          ;; Draw range line from tip
          (set! (.-strokeStyle ctx) "rgba(255,100,100,0.7)")
          (set! (.-lineWidth ctx) 3)
          (set! (.-lineCap ctx) "round")
          (.beginPath ctx)
          (.moveTo ctx tip-x tip-y)
          (.lineTo ctx range-end-x range-end-y)
          (.stroke ctx)
          ;; Draw range end marker
          (.beginPath ctx)
          (.arc ctx range-end-x range-end-y 5 0 (* 2 js/Math.PI))
          (.stroke ctx)))
      ;; Draw preview piece with transparency
      (.save ctx)
      (set! (.-globalAlpha ctx) preview-alpha)
      (draw-pyramid ctx start-x start-y size player-colour orientation angle)
      (.restore ctx)))
  ;; Restore zoom transform if it was applied
  (when zoom-state
    (.restore ctx)))

(defn has-pieces-of-size? [size use-captured?]
  "Returns true if current player has pieces of the given size to place"
  (let [game @state/game-state
        player-id (keyword @state/player-id)
        player-data (get-in game [:players player-id])]
    (if use-captured?
      ;; Check captured pieces
      (let [captured (or (:captured player-data) [])]
        (some #(= (keyword (:size %)) size) captured))
      ;; Check regular pieces
      (let [pieces (or (:pieces player-data) {})]
        (pos? (get pieces size 0))))))

(defn game-canvas []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")]
            (draw-with-preview ctx @state/game-state nil @state/selected-piece
                               @state/player-colour @state/hover-pos @state/player-id nil))))

      :component-did-update
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                zoom? @state/zoom-active
                hover @state/hover-pos
                ;; Create zoom state map if zoom is active
                zoom-state (when zoom?
                             {:center-x (if hover (:x hover) (/ canvas-width 2))
                              :center-y (if hover (:y hover) (/ canvas-height 2))
                              :scale 4})]
            (draw-with-preview ctx
                               @state/game-state
                               @state/drag-state
                               @state/selected-piece
                               @state/player-colour
                               @state/hover-pos
                               @state/player-id
                               zoom-state))))

      :reagent-render
      (fn []
        ;; Deref state atoms so Reagent re-renders when they change
        (let [_ @state/game-state
              _ @state/drag-state
              _ @state/selected-piece
              _ @state/hover-pos
              _ @state/zoom-active]
          [:canvas
           {:ref #(reset! canvas-ref %)
            :width canvas-width
            :height canvas-height
            :style {:border "2px solid #4ecdc4" :cursor "crosshair"}
            :on-mouse-down
            (fn [e]
              (.preventDefault e)
              (let [{:keys [x y]} (get-canvas-coords e)
                    {:keys [size captured?]} @state/selected-piece]
                ;; Only start drag if player has pieces of this size
                (when (has-pieces-of-size? size captured?)
                  (reset! state/drag-state {:start-x x :start-y y
                                            :current-x x :current-y y
                                            :last-x x :last-y y
                                            :locked-angle 0}))))
            :on-mouse-move
            (fn [e]
              (let [{:keys [x y]} (get-canvas-coords e)
                    shift-held (.-shiftKey e)]
                ;; Always update hover position for capture detection
                (reset! state/hover-pos {:x x :y y})
                ;; Update drag state if dragging
                (when-let [drag @state/drag-state]
                  (let [{:keys [start-x start-y last-x last-y]} drag
                        dx (- x last-x)
                        dy (- y last-y)]
                    (if shift-held
                      ;; Shift held: move position by delta, keep locked angle
                      (reset! state/drag-state
                              (assoc drag
                                     :start-x (+ start-x dx)
                                     :start-y (+ start-y dy)
                                     :current-x (+ start-x dx)
                                     :current-y (+ start-y dy)
                                     :last-x x :last-y y))
                      ;; Normal: update current position for angle calculation, lock that angle
                      (let [new-angle (calculate-angle start-x start-y x y)]
                        (swap! state/drag-state assoc
                               :current-x x :current-y y
                               :last-x x :last-y y
                               :locked-angle new-angle)))))))
            :on-mouse-up
            (fn [e]
              (when-let [drag @state/drag-state]
                (let [{:keys [start-x start-y current-x current-y locked-angle]} drag
                      {:keys [size orientation captured?]} @state/selected-piece
                      shift-held (.-shiftKey e)
                      ;; Use locked angle when shift is held, otherwise calculate from position
                      angle (if shift-held
                              locked-angle
                              (calculate-angle start-x start-y current-x current-y))]
                  (ws/place-piece! start-x start-y size orientation angle nil captured?)
                  (reset! state/drag-state nil)
                  (reset! state/zoom-active false))))
            :on-mouse-leave
            (fn [e]
              (reset! state/hover-pos nil)
              (reset! state/drag-state nil))}]))})))

(defn can-attack? []
  "Returns true if attacking is allowed (after first few moves)"
  (let [game @state/game-state
        board-count (count (:board game))]
    (>= board-count attack-unlock-threshold)))

(defn has-captured-pieces? []
  "Returns true if current player has any captured pieces"
  (let [game @state/game-state
        player-id @state/player-id
        player-data (get-in game [:players player-id])
        captured (or (:captured player-data) [])]
    (pos? (count captured))))

(defn try-capture-hovered-piece!
  "Attempt to capture the piece under the cursor"
  []
  (when-let [hovered (get-hovered-piece)]
    (when-let [game @state/game-state]
      (let [player-id @state/player-id
            board (:board game)]
        (when (capturable-piece? hovered player-id board)
          (ws/capture-piece! (:id hovered)))))))

(defn handle-keydown [e]
  (let [key (.-key e)]
    (case key
      "1" (swap! state/selected-piece assoc :size :small)
      "2" (swap! state/selected-piece assoc :size :medium)
      "3" (swap! state/selected-piece assoc :size :large)
      ("a" "A") (when (can-attack?)
                  (swap! state/selected-piece assoc :orientation :pointing))
      ("d" "D") (swap! state/selected-piece assoc :orientation :standing)
      ("c" "C") (if (get-hovered-piece)
                  ;; If hovering over a piece, try to capture it
                  (try-capture-hovered-piece!)
                  ;; Otherwise, toggle captured piece selection
                  (when (has-captured-pieces?)
                    (swap! state/selected-piece update :captured? not)))
      "Escape" (do
                 (reset! state/drag-state nil)
                 (reset! state/show-help false)
                 (reset! state/zoom-active false))
      "?" (swap! state/show-help not)
      ("z" "Z") (swap! state/zoom-active not)
      nil)))

(defn piece-selector []
  (let [{:keys [size orientation captured?]} @state/selected-piece
        attack-allowed (can-attack?)
        has-captured (has-captured-pieces?)
        zoom? @state/zoom-active]
    [:div.piece-selector
     [:div.hotkey-display
      [:span.current-size
       (case size :small "Small (1)" :medium "Medium (2)" :large "Large (3)" "Small (1)")]
      [:span.separator " | "]
      [:span.current-mode
       (if (= orientation :standing) "Defend (D)" "Attack (A)")]
      (when captured?
        [:span.captured-indicator {:style {:color theme/gold :margin-left "0.5rem"}}
         "[Captured]"])
      (when zoom?
        [:span.zoom-indicator {:style {:color "#00ff00" :margin-left "0.5rem"}}
         "[ZOOM 4x]"])]
     [:div.hotkey-hint
      (cond
        (not attack-allowed)
        "1/2/3 size, D defend, Z zoom, Shift+drag | ? help (attack unlocks after 2 moves)"

        has-captured
        "1/2/3 size, A/D mode, C captured, Z zoom, Shift+drag | ? help"

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

(defn piece-size-row [size label pieces colour & [{:keys [captured?]}]]
  [:div.piece-row
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
        is-me (= (name player-id) @state/player-id)
        has-captured? (pos? (count captured))]
    [:div.player-stash {:class (when is-me "is-me")}
     [:div.stash-header {:style {:color colour}}
      player-name
      (when is-me " (you)")]
     [:div.stash-pieces
      [piece-size-row :large "L" pieces colour]
      [piece-size-row :medium "M" pieces colour]
      [piece-size-row :small "S" pieces colour]]
     (when has-captured?
       [:div.captured-pieces
        [:div.captured-header {:style {:color theme/gold :font-size "0.8em" :margin-top "0.5rem"}}
         "Captured:"]
        ;; Render each captured piece with its original colour
        (doall
          (for [[idx cap-piece] (map-indexed vector captured)]
            ^{:key (str "cap-" idx)}
            [draw-stash-pyramid (keyword (:size cap-piece)) (:colour cap-piece) {:captured? true}]))])]))

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
            urgent? (< remaining 30000)]  ;; Last 30 seconds
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
  (when @state/show-help
    [:div.help-overlay
     {:style {:position "fixed"
              :top 0 :left 0 :right 0 :bottom 0
              :background "rgba(0,0,0,0.85)"
              :display "flex"
              :justify-content "center"
              :align-items "center"
              :z-index 1000}
      :on-click #(reset! state/show-help false)}
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
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "D"]
         [:td {:style {:padding "0.5rem"}} "Defend mode (standing piece)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "A"]
         [:td {:style {:padding "0.5rem"}} "Attack mode (pointing piece)"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "C"]
         [:td {:style {:padding "0.5rem"}} "Capture piece / Toggle captured mode"]]
        [:tr [:td {:style {:padding "0.5rem" :color theme/gold}} "Z"]
         [:td {:style {:padding "0.5rem"}} "Toggle 4x zoom for fine placement"]]
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
          [game-timer]]
         [error-display]
         [game-results-overlay]
         [help-overlay]
         [piece-selector]
         [:div.game-area
          [stash-panel :left]
          [game-canvas]
          [stash-panel :right]]])})))

