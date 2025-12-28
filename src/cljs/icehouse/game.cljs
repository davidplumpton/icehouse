(ns icehouse.game
  (:require [reagent.core :as r]
            [icehouse.state :as state]
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

;; Piece sizes for stash SVG rendering [width height]
;; 3:2 height:base ratio, small height = large base
(def stash-sizes {:small [24 36] :medium [30 45] :large [36 54]})

;; Default piece counts per player
(def default-pieces {:small 5 :medium 5 :large 5})

;; Points per piece size (pip values)
(def pips {:small 1 :medium 2 :large 3})

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
  "Get vertices of a piece in world coordinates"
  [{:keys [x y size orientation angle]}]
  (let [base-size (get piece-sizes (keyword size) 30)
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
          local-verts)))

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
  (reduce + (map #(get pips (keyword (:size %)) 0) attackers)))

(defn calculate-over-ice
  "Returns a map of defender-id -> {:excess pips :attackers [...] :defender-owner player-id}
   for each over-iced defender"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [result target-id attackers]
       (let [defender (find-piece-by-id board target-id)
             defender-pips (get pips (keyword (:size defender)) 0)
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
             (<= (get pips (keyword (:size piece)) 0) (:excess info)))))))

(defn get-hovered-piece
  "Get the piece currently under the mouse cursor, if any"
  []
  (when-let [{:keys [x y]} @state/hover-pos]
    (when-let [game @state/game-state]
      (find-piece-at x y (:board game)))))

(defn draw-pyramid [ctx x y size colour orientation angle]
  (let [size-kw (keyword size)
        orient-kw (keyword orientation)
        base-size (get piece-sizes size-kw 30)
        half-size (/ base-size 2)
        rotation (or angle 0)]
    (.save ctx)
    (.translate ctx x y)
    (.rotate ctx rotation)

    (set! (.-fillStyle ctx) colour)
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
    (set! (.-strokeStyle ctx) "#ffd700")
    (set! (.-lineWidth ctx) 4)
    (set! (.-shadowColor ctx) "#ffd700")
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
  (set! (.-fillStyle ctx) "#2a2a3e")
  (.fillRect ctx 0 0 canvas-width canvas-height)

  (set! (.-strokeStyle ctx) "#3a3a4e")
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
                          (find-piece-at (:x hover-pos) (:y hover-pos) board))]
      ;; Draw all pieces
      (doseq [piece board]
        (let [player-id (:player-id piece)
              player-data (get-in game [:players player-id])
              colour (or (:colour piece) (:colour player-data) "#888")]
          (draw-pyramid ctx
                        (:x piece)
                        (:y piece)
                        (:size piece)
                        colour
                        (:orientation piece)
                        (:angle piece))))
      ;; Draw capture highlight if hovering over a capturable piece
      (when (and hovered-piece
                 (capturable-piece? hovered-piece current-player-id board))
        (draw-capture-highlight ctx hovered-piece)))))

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

(defn draw-with-preview [ctx game drag-state selected-piece player-colour hover-pos player-id]
  "Draw the board and optionally a preview of the piece being placed"
  (draw-board ctx game hover-pos player-id)
  ;; Draw preview if dragging
  (when drag-state
    (let [{:keys [start-x start-y current-x current-y locked-angle]} drag-state
          {:keys [size orientation]} selected-piece
          base-size (get piece-sizes size 30)
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
      ;; Draw attack range indicator for attacking pieces
      (when (and is-attacking? current-x current-y)
        (let [tip-offset (* base-size tip-offset-ratio)
              tip-x (+ start-x (* (js/Math.cos angle) tip-offset))
              tip-y (+ start-y (* (js/Math.sin angle) tip-offset))
              ;; Attack range extends base-size from tip
              range-end-x (+ tip-x (* (js/Math.cos angle) base-size))
              range-end-y (+ tip-y (* (js/Math.sin angle) base-size))]
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
      (.restore ctx))))

(defn game-canvas []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")]
            (draw-board ctx @state/game-state @state/hover-pos @state/player-id))))

      :component-did-update
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")]
            (draw-with-preview ctx
                               @state/game-state
                               @state/drag-state
                               @state/selected-piece
                               @state/player-colour
                               @state/hover-pos
                               @state/player-id))))

      :reagent-render
      (fn []
        ;; Deref state atoms so Reagent re-renders when they change
        (let [_ @state/game-state
              _ @state/drag-state
              _ @state/selected-piece
              _ @state/hover-pos]
          [:canvas
           {:ref #(reset! canvas-ref %)
            :width canvas-width
            :height canvas-height
            :style {:border "2px solid #4ecdc4" :cursor "crosshair"}
            :on-mouse-down
            (fn [e]
              (.preventDefault e)
              (let [{:keys [x y]} (get-canvas-coords e)]
                (reset! state/drag-state {:start-x x :start-y y
                                          :current-x x :current-y y
                                          :last-x x :last-y y
                                          :locked-angle 0})))
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
                  (reset! state/drag-state nil))))
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
      "Escape" (reset! state/drag-state nil)
      nil)))

(defn piece-selector []
  (let [{:keys [size orientation captured?]} @state/selected-piece
        attack-allowed (can-attack?)
        has-captured (has-captured-pieces?)]
    [:div.piece-selector
     [:div.hotkey-display
      [:span.current-size
       (case size :small "Small (1)" :medium "Medium (2)" :large "Large (3)" "Small (1)")]
      [:span.separator " | "]
      [:span.current-mode
       (if (= orientation :standing) "Defend (D)" "Attack (A)")]
      (when captured?
        [:span.captured-indicator {:style {:color "#ffd700" :margin-left "0.5rem"}}
         "[Captured]"])]
     [:div.hotkey-hint
      (cond
        (not attack-allowed)
        "1/2/3 size, D defend, Shift+drag to reposition (attack unlocks after 2 moves)"

        has-captured
        "1/2/3 size, A/D mode, C captured, Shift+drag to reposition"

        :else
        "1/2/3 size, A/D mode, Shift+drag to reposition")]]))

(defn draw-stash-pyramid [size colour & [{:keys [captured?]}]]
  "Returns SVG element for a pyramid in the stash"
  (let [[width height] (get stash-sizes size [24 36])]
    [:svg {:width width :height height :style {:display "inline-block" :margin "1px"}}
     [:polygon {:points (str (/ width 2) ",0 0," height " " width "," height)
                :fill colour
                :stroke (if captured? "#ffd700" "#000")
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
        [:div.captured-header {:style {:color "#ffd700" :font-size "0.8em" :margin-top "0.5rem"}}
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

(defn game-view []
  (r/create-class
   {:component-did-mount
    (fn [this]
      (.addEventListener js/document "keydown" handle-keydown))

    :component-will-unmount
    (fn [this]
      (.removeEventListener js/document "keydown" handle-keydown))

    :reagent-render
    (fn []
      [:div.game
       [:h2 "Icehouse"]
       [error-display]
       [piece-selector]
       [:div.game-area
        [stash-panel :left]
        [game-canvas]
        [stash-panel :right]]])}))

