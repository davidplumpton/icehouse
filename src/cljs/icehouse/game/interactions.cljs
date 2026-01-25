(ns icehouse.game.interactions
  "Event handlers and keyboard interactions for the game."
  (:require [icehouse.state :as state]
            [icehouse.game.constants :as const]
            [icehouse.game.placement :as placement]
            [icehouse.game.rendering :as render]
            [icehouse.websocket :as ws]
            [icehouse.geometry :as geo]
            [icehouse.game-logic :as logic]
            [icehouse.utils :as utils]))

;; =============================================================================
;; Game Queries
;; =============================================================================

(defn can-attack? []
  "Returns true if attacking is allowed (after first few moves)"
  (let [game @state/game-state
        board-count (count (:board game))]
    (>= board-count const/attack-unlock-threshold)))

(defn has-captured-pieces? []
  "Returns true if current player has any captured pieces"
  (let [game @state/game-state
        player-id (utils/normalize-player-id (:id @state/current-player))
        player-data (get-in game [:players player-id])
        captured (or (:captured player-data) [])]
    (pos? (count captured))))

(defn get-hovered-piece
  "Get the piece currently under the mouse cursor, if any"
  []
  (when-let [{:keys [x y]} (:hover-pos @state/ui-state)]
    (when-let [game @state/game-state]
      (render/find-piece-at x y (:board game)))))

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

;; =============================================================================
;; Canvas Event Utilities
;; =============================================================================

(defn get-canvas-coords [e]
  "Get coordinates relative to canvas from mouse event"
  (let [rect (.getBoundingClientRect (.-target e))]
    {:x (- (.-clientX e) (.-left rect))
     :y (- (.-clientY e) (.-top rect))}))

;; =============================================================================
;; Mouse Event Handlers
;; =============================================================================

(defn handle-canvas-mousedown
  "Handles mousedown on canvas - starts a new drag from clicked position.
   Clears any pending stash drag since we're clicking directly on canvas."
  [e]
  (.preventDefault e)
  (let [{:keys [x y]} (get-canvas-coords e)
        {:keys [selected-piece zoom-active]} @state/ui-state
        {:keys [size captured?]} selected-piece
        [adjusted-x adjusted-y] (placement/adjust-coords-for-zoom x y zoom-active)]
    (reset! placement/stash-drag-pending nil)
    (placement/try-start-drag! adjusted-x adjusted-y size captured? false)))

(defn handle-canvas-mouseenter
  "Handles mouseenter on canvas - starts drag if user dragged from stash.
   Only triggers if mouse button is held (buttons > 0)."
  [e]
  (when (and @placement/stash-drag-pending (pos? (.-buttons e)))
    (let [{:keys [x y]} (get-canvas-coords e)
          {:keys [size captured?]} @placement/stash-drag-pending
          {:keys [zoom-active]} @state/ui-state
          [adjusted-x adjusted-y] (placement/adjust-coords-for-zoom x y zoom-active)]
      (placement/try-start-drag! adjusted-x adjusted-y size captured? true)
      (reset! placement/stash-drag-pending nil))))

(defn handle-canvas-mousemove
  "Handles mousemove on canvas - updates hover position and drag state.
   Also handles stash drag continuation if mouse was already on canvas when stash clicked."
  [e]
  (let [{:keys [x y]} (get-canvas-coords e)
        shift-held (.-shiftKey e)
        {:keys [zoom-active move-mode]} @state/ui-state
        [adjusted-x adjusted-y] (placement/adjust-coords-for-zoom x y zoom-active)
        position-adjust? (or shift-held move-mode)]
    ;; Always update hover position for capture detection (use original unscaled coords)
    (swap! state/ui-state assoc :hover-pos {:x x :y y})
    ;; Check for pending stash drag (mouse was already on canvas when stash clicked)
    (when (and @placement/stash-drag-pending (pos? (.-buttons e)))
      (let [{:keys [size captured?]} @placement/stash-drag-pending]
        (placement/try-start-drag! adjusted-x adjusted-y size captured? true)
        (reset! placement/stash-drag-pending nil)))
    ;; Update drag state if dragging
    (placement/update-drag-position! adjusted-x adjusted-y position-adjust?)))

(defn handle-canvas-mouseup
  "Handles mouseup on canvas - completes the piece placement."
  [e]
  (placement/complete-placement! (.-shiftKey e)))

(defn handle-canvas-mouseleave
  "Handles mouseleave on canvas - clears hover position and drag state."
  [_e]
  (swap! state/ui-state assoc :hover-pos nil :drag nil))

;; =============================================================================
;; Keyboard Handlers
;; =============================================================================

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
    (when-let [size (nth (placement/available-captured-sizes) index nil)]
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
             :drag (placement/transform-drag-for-zoom-toggle drag currently-zoomed))
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
