(ns icehouse.game
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.websocket :as ws]))

;; Canvas dimensions
(def canvas-width 800)
(def canvas-height 600)
(def grid-size 50)

;; Piece sizes for canvas rendering (base width in pixels)
(def piece-sizes {:small 30 :medium 50 :large 70})

;; Piece sizes for stash SVG rendering [width height]
(def stash-sizes {:small [24 36] :medium [36 54] :large [48 72]})

;; Default piece counts per player
(def default-pieces {:small 5 :medium 5 :large 5})

(defn calculate-angle
  "Calculate angle in radians from point (x1,y1) to (x2,y2)"
  [x1 y1 x2 y2]
  (js/Math.atan2 (- y2 y1) (- x2 x1)))

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
      (let [half-width (* base-size 0.75)]
        (.beginPath ctx)
        (.moveTo ctx half-width 0)                    ; tip pointing right
        (.lineTo ctx (- half-width) (- half-size))    ; top-left corner
        (.lineTo ctx (- half-width) half-size)        ; bottom-left corner
        (.closePath ctx)
        (.fill ctx)
        (.stroke ctx)))

    (.restore ctx)))

(defn draw-board [ctx game]
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
    (doseq [piece (:board game)]
      (let [player-id (:player-id piece)
            player-data (get-in game [:players player-id])
            colour (or (:colour piece) (:colour player-data) "#888")]
        (draw-pyramid ctx
                      (:x piece)
                      (:y piece)
                      (:size piece)
                      colour
                      (:orientation piece)
                      (:angle piece))))))

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

(defn draw-with-preview [ctx game drag-state selected-piece player-colour]
  "Draw the board and optionally a preview of the piece being placed"
  (draw-board ctx game)
  ;; Draw preview if dragging
  (when drag-state
    (let [{:keys [start-x start-y current-x current-y]} drag-state
          {:keys [size orientation]} selected-piece
          base-size (get piece-sizes size 30)
          angle (if (and current-x current-y)
                  (calculate-angle start-x start-y current-x current-y)
                  0)
          is-attacking? (= orientation :pointing)]
      ;; Draw a line showing the direction
      (when (and current-x current-y)
        (set! (.-strokeStyle ctx) "rgba(255,255,255,0.5)")
        (set! (.-lineWidth ctx) 2)
        (.beginPath ctx)
        (.moveTo ctx start-x start-y)
        (.lineTo ctx current-x current-y)
        (.stroke ctx))
      ;; Draw attack range indicator for attacking pieces
      (when (and is-attacking? current-x current-y)
        (let [;; Calculate tip position (0.75 * base-size from center)
              tip-offset (* base-size 0.75)
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
      (set! (.-globalAlpha ctx) 0.6)
      (draw-pyramid ctx start-x start-y size player-colour orientation angle)
      (.restore ctx))))

(defn game-canvas []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")]
            (draw-board ctx @state/game-state))))

      :component-did-update
      (fn [this]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")]
            (draw-with-preview ctx
                               @state/game-state
                               @state/drag-state
                               @state/selected-piece
                               @state/player-colour))))

      :reagent-render
      (fn []
        ;; Deref state atoms so Reagent re-renders when they change
        (let [_ @state/game-state
              _ @state/drag-state
              _ @state/selected-piece]
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
                                          :current-x x :current-y y})))
            :on-mouse-move
            (fn [e]
              (when @state/drag-state
                (let [{:keys [x y]} (get-canvas-coords e)]
                  (swap! state/drag-state assoc :current-x x :current-y y))))
            :on-mouse-up
            (fn [e]
              (when-let [drag @state/drag-state]
                (let [{:keys [start-x start-y current-x current-y]} drag
                      {:keys [size orientation]} @state/selected-piece
                      angle (calculate-angle start-x start-y current-x current-y)]
                  (ws/place-piece! start-x start-y size orientation angle nil)
                  (reset! state/drag-state nil))))
            :on-mouse-leave
            (fn [e]
              (reset! state/drag-state nil))}]))})))

(defn can-attack? []
  "Returns true if attacking is allowed (after first 2 moves)"
  (let [game @state/game-state
        board-count (count (:board game))]
    (>= board-count 2)))

(defn has-captured-pieces? []
  "Returns true if current player has any captured pieces"
  (let [game @state/game-state
        player-id @state/player-id
        player-data (get-in game [:players player-id])
        captured (or (:captured player-data) {:small 0 :medium 0 :large 0})]
    (pos? (+ (get captured :small 0)
             (get captured :medium 0)
             (get captured :large 0)))))

(defn handle-keydown [e]
  (let [key (.-key e)]
    (case key
      "1" (swap! state/selected-piece assoc :size :small)
      "2" (swap! state/selected-piece assoc :size :medium)
      "3" (swap! state/selected-piece assoc :size :large)
      ("a" "A") (when (can-attack?)
                  (swap! state/selected-piece assoc :orientation :pointing))
      ("d" "D") (swap! state/selected-piece assoc :orientation :standing)
      ("c" "C") (when (has-captured-pieces?)
                  (swap! state/selected-piece update :captured? not))
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
        "Press 1/2/3 for size, D for defend, Esc to cancel (attack unlocks after 2 moves)"

        has-captured
        "Press 1/2/3 for size, A/D for mode, C for captured, Esc to cancel"

        :else
        "Press 1/2/3 for size, A/D for mode, Esc to cancel")]]))

(defn draw-stash-pyramid [size colour & [{:keys [captured?]}]]
  "Returns SVG element for a pyramid in the stash"
  (let [[width height] (get stash-sizes size [24 36])]
    [:svg {:width width :height height :style {:display "inline-block" :margin "2px"}}
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
        captured (or (:captured player-data) {:small 0 :medium 0 :large 0})
        colour (or (:colour player-data) "#888")
        player-name (or (:name player-data) "Player")
        is-me (= (name player-id) @state/player-id)
        has-captured? (pos? (+ (get captured :small 0)
                               (get captured :medium 0)
                               (get captured :large 0)))]
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
        [piece-size-row :large "L" captured colour {:captured? true}]
        [piece-size-row :medium "M" captured colour {:captured? true}]
        [piece-size-row :small "S" captured colour {:captured? true}]])]))

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

