(ns icehouse.game.placement
  "Drag/drop placement mechanics for game pieces."
  (:require [reagent.core :as r]
            [icehouse.game.constants :as const]
            [icehouse.state :as state]
            [icehouse.geometry :as geo]
            [icehouse.websocket :as ws]
            [icehouse.utils :as utils]
            [icehouse.constants :as shared-const]))

;; Forward declaration for has-pieces-of-size?
(declare has-pieces-of-size?)

;; =============================================================================
;; Local State
;; =============================================================================

;; Local state for stash drag (not in schema-validated ui-state)
;; Tracks when user starts dragging from their stash before entering canvas
(defonce stash-drag-pending (r/atom nil))

;; =============================================================================
;; Placement Throttle
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
  (let [throttle-sec (get-in @state/game-state [:options :placement-throttle] shared-const/default-placement-throttle-sec)
        throttle-ms (* throttle-sec 1000)
        now (js/Date.now)
        time-since-last (- now last-placement-time)]
    {:can-place? (>= time-since-last throttle-ms)
     :throttle-ms throttle-ms
     :time-since-last time-since-last}))

;; =============================================================================
;; Coordinate Transformations
;; =============================================================================

(defn adjust-coords-for-zoom
  "Scale down mouse coordinates when zoom is active.
   Returns [adjusted-x adjusted-y]."
  [x y zoom-active]
  (if zoom-active
    [(/ x const/zoom-scale) (/ y const/zoom-scale)]
    [x y]))

(defn scale-coords-for-placement
  "Scale up coordinates for placement when zoom is active.
   Returns [scaled-x scaled-y] rounded to integers."
  [x y zoom-active]
  (if zoom-active
    [(js/Math.round (* x const/zoom-scale))
     (js/Math.round (* y const/zoom-scale))]
    [(js/Math.round x) (js/Math.round y)]))

(defn scale-to-world-coords
  "Scale coordinates from zoomed space to world space for rendering.
   Uses zoom-state scale if provided, otherwise returns original coords."
  [x y zoom-state]
  (let [scale (if zoom-state (:scale zoom-state) 1)]
    [(* x scale) (* y scale)]))

(defn calculate-zoom-state
  "Calculate zoom state for rendering based on current drag and hover position.
   When dragging, zoom centers on the piece being placed.
   When not dragging, zoom centers on hover position or canvas center."
  [zoom-active drag hover-pos]
  (when zoom-active
    (let [;; Drag coordinates are stored in scaled space, so scale them back up for zoom center
          zoom-center-x (if drag
                          (* (:start-x drag) const/zoom-scale)
                          (if hover-pos
                            (:x hover-pos)
                            (/ const/canvas-width 2)))
          zoom-center-y (if drag
                          (* (:start-y drag) const/zoom-scale)
                          (if hover-pos
                            (:y hover-pos)
                            (/ const/canvas-height 2)))]
      {:center-x zoom-center-x
       :center-y zoom-center-y
       :scale const/zoom-scale})))

(defn transform-drag-for-zoom-toggle
  "Transform drag coordinates when toggling zoom on or off.
   When zooming in (currently-zoomed false), scales coords down.
   When zooming out (currently-zoomed true), scales coords up."
  [drag currently-zoomed]
  (let [{:keys [start-x start-y current-x current-y last-x last-y locked-angle from-stash?]} drag
        transform-fn (if currently-zoomed
                       #(* % const/zoom-scale)  ;; Zooming out: scale up
                       #(/ % const/zoom-scale))]  ;; Zooming in: scale down
    {:start-x (transform-fn start-x)
     :start-y (transform-fn start-y)
     :current-x (transform-fn current-x)
     :current-y (transform-fn current-y)
     :last-x (transform-fn last-x)
     :last-y (transform-fn last-y)
     :locked-angle locked-angle
     :from-stash? from-stash?}))

;; =============================================================================
;; Piece Availability
;; =============================================================================

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

;; =============================================================================
;; Drag Operations
;; =============================================================================

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
