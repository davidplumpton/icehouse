(ns icehouse.replay
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.websocket :as ws]
            [icehouse.game :as game]
            [icehouse.game-logic :as logic]))

;; =============================================================================
;; Replay Constants
;; =============================================================================

(def replay-speeds [0.5 1 2 4])
(def replay-tick-ms 500)

;; =============================================================================
;; Board Reconstruction
;; =============================================================================

(def default-pieces {:small 5 :medium 5 :large 5})

(defn- apply-move-to-state
  "Apply a single move to game state, updating board and player stashes"
  [{:keys [board players] :as state} move]
  (let [move-type (keyword (:type move))]
    (case move-type
      :place-piece
      (let [piece (:piece move)
            player-id (keyword (:player-id move))
            piece-size (keyword (:size piece))
            using-captured? (:using-captured? move)]
        (-> state
            (update :board conj piece)
            (update-in [:players player-id]
                       (fn [pdata]
                         (if using-captured?
                           ;; Remove one from captured pool matching the size
                           (update pdata :captured
                                   (fn [caps]
                                     (let [idx (.indexOf (mapv #(keyword (:size %)) caps) piece-size)]
                                       (if (>= idx 0)
                                         (vec (concat (subvec caps 0 idx) (subvec caps (inc idx))))
                                         caps))))
                           ;; Decrement regular pieces
                           (update-in pdata [:pieces piece-size] dec))))))

      :capture-piece
      (let [piece-id (:piece-id move)
            player-id (keyword (:player-id move))
            captured-piece (:captured-piece move)]
        (-> state
            (update :board (fn [b] (vec (remove (utils/by-id piece-id) b))))
            (update-in [:players player-id :captured] conj captured-piece)))

      ;; Unknown move type
      (do
        (js/console.warn "Unknown move type in replay:" move-type)
        state))))

(defn game-state-at-move
  "Create a game-state-like structure for rendering at a given move index.
   Reconstructs board and player stashes from move history."
  [record move-idx]
  (when record
    ;; Initialize players with full stashes and empty captured pools
    (let [initial-players (into {}
                                (map (fn [[pid pdata]]
                                       [(keyword pid)
                                        (assoc pdata
                                               :pieces default-pieces
                                               :captured [])])
                                     (:players record)))
          initial-state {:board [] :players initial-players}
          moves (if (neg? move-idx)
                  []
                  (take (inc move-idx) (:moves record)))]
      (reduce apply-move-to-state initial-state moves))))

;; =============================================================================
;; Replay Controls
;; =============================================================================

(defn step-forward!
  "Advance to the next move"
  []
  (when-let [replay @state/replay-state]
    (let [max-move (dec (count (get-in replay [:record :moves])))]
      (when (< (:current-move replay) max-move)
        (swap! state/replay-state update :current-move inc)))))

(defn step-back!
  "Go back to the previous move"
  []
  (when-let [replay @state/replay-state]
    (when (> (:current-move replay) -1)
      (swap! state/replay-state update :current-move dec))))

(defn go-to-start!
  "Jump to the beginning (before any moves)"
  []
  (swap! state/replay-state assoc :current-move -1))

(defn go-to-end!
  "Jump to the final state"
  []
  (when-let [replay @state/replay-state]
    (let [max-move (dec (count (get-in replay [:record :moves])))]
      (swap! state/replay-state assoc :current-move max-move))))

(defn toggle-play!
  "Toggle auto-play"
  []
  (swap! state/replay-state update :playing? not))

(defn set-speed!
  "Set playback speed multiplier"
  [speed]
  (swap! state/replay-state assoc :speed speed))

(defn go-to-move!
  "Jump to a specific move index"
  [move-idx]
  (when-let [replay @state/replay-state]
    (let [max-move (dec (count (get-in replay [:record :moves])))]
      (swap! state/replay-state assoc :current-move (max -1 (min move-idx max-move))))))

(defn close-replay!
  "Close the replay view"
  []
  (state/leave-replay-to-lobby!))

;; =============================================================================
;; Replay Canvas Component
;; =============================================================================

(defn- render-replay-board!
  "Render the current replay state to the canvas"
  [canvas]
  (when canvas
    (let [ctx (.getContext canvas "2d")
          replay @state/replay-state
          game-state (when replay
                       (game-state-at-move (:record replay) (:current-move replay)))
          iced-pieces (when game-state
                        (logic/calculate-iced-pieces (:board game-state)))]
      (game/draw-board ctx game-state nil nil {:iced-pieces iced-pieces}))))

(defn replay-canvas
  "Canvas component for rendering replay board state"
  []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_] (render-replay-board! @canvas-ref))

      :component-did-update
      (fn [_] (render-replay-board! @canvas-ref))

      :reagent-render
      (fn []
        @state/replay-state  ;; Subscribe to updates
        [:canvas {:ref #(reset! canvas-ref %)
                  :width game/canvas-width
                  :height game/canvas-height
                  :class "replay-canvas"}])})))

;; =============================================================================
;; Replay Controls Component
;; =============================================================================

(defn replay-controls
  "Control panel for replay"
  []
  (let [replay @state/replay-state]
    (when replay
      (let [record (:record replay)
            current-move (:current-move replay)
            total-moves (count (:moves record))
            playing? (:playing? replay)
            speed (:speed replay)
            current-time (if (neg? current-move)
                           0
                           (get-in record [:moves current-move :elapsed-ms] 0))]
        [:div.replay-controls
         ;; Progress info
         [:div.replay-info
          "Move " (inc current-move) " / " total-moves
          " - " (utils/format-time current-time) " / " (utils/format-time (:duration-ms record))]

         ;; Time slider
         [:div.replay-slider-container
          [:input {:type "range"
                   :min -1
                   :max (dec total-moves)
                   :value current-move
                   :on-change #(go-to-move! (js/parseInt (.. % -target -value)))
                   :class "replay-slider"}]]

         ;; Control buttons
         [:div.replay-buttons
          [:button.replay-btn {:on-click #(go-to-start!)} "|<"]
          [:button.replay-btn {:on-click #(step-back!)} "<"]
          [:button.replay-btn {:on-click #(toggle-play!)}
           (if playing? "||" ">")]
          [:button.replay-btn {:on-click #(step-forward!)} ">"]
          [:button.replay-btn {:on-click #(go-to-end!)} ">|"]]

         ;; Speed controls
         [:div.speed-controls
          [:span.speed-label "Speed:"]
          (for [s replay-speeds]
            ^{:key s}
            [:button.speed-btn {:on-click #(set-speed! s)
                                :style {:background (if (= speed s) theme/green theme/button-inactive)}
                                :class "speed-btn"}
             (str s "x")])]

         ;; Game info
         [:div.game-info
          [:div "Winner: " (if-let [winner-id (:winner record)]
                            (or (get-in record [:players winner-id :name])
                                (get-in record [:players (keyword winner-id) :name])
                                winner-id)
                            "None")]
          [:div "End reason: " (name (or (:end-reason record) :unknown))]]

         ;; Close button
         [:button.close-replay-btn {:on-click close-replay!}
          "Close Replay"]]))))

;; =============================================================================
;; Game List Component
;; =============================================================================

(defn close-game-list!
  "Close the game list and return to lobby"
  []
  (state/leave-replay-to-lobby!))

(defn game-list-panel
  "Panel showing list of saved games"
  []
  (let [games @state/game-list]
    [:div.game-list-panel
     [:h2 "Saved Games"]
     [:div.game-list-actions
      [:button.btn-primary {:on-click ws/list-games!}
       "Refresh List"]
      [:button.btn-secondary {:on-click close-game-list!}
       "Back to Lobby"]]
     (if (seq games)
       [:ul.game-list
        (for [game-id games]
          ^{:key game-id}
          [:li.game-list-item
           [:button.game-list-btn {:on-click #(ws/load-game! game-id)}
            game-id]])]
       [:p.info
        "No saved games found"])]))

;; =============================================================================
;; Auto-play Logic
;; =============================================================================

(defonce tick-counter (atom 0))

(defn- auto-play-tick!
  "Advance replay if playing, respecting speed setting"
  []
  (when-let [replay @state/replay-state]
    (when (:playing? replay)
      (let [speed (or (:speed replay) 1)
            ;; At 500ms base interval: speed 0.5 = every 2 ticks, speed 2 = every tick + extra
            ticks-needed (/ 1 speed)]
        (swap! tick-counter inc)
        (when (>= @tick-counter ticks-needed)
          (reset! tick-counter 0)
          (let [max-move (dec (count (get-in replay [:record :moves])))
                current (:current-move replay)
                ;; For speeds > 1, advance multiple moves per tick
                steps (max 1 (int speed))]
            (if (< current max-move)
              (dotimes [_ steps]
                (when (< (:current-move @state/replay-state) max-move)
                  (step-forward!)))
              (swap! state/replay-state assoc :playing? false))))))))

;; =============================================================================
;; Main Replay View
;; =============================================================================

(defn replay-view
  "Main replay view component with integrated auto-play timer"
  []
  (r/with-let [timer (js/setInterval auto-play-tick! replay-tick-ms)]
    (let [replay @state/replay-state
          game-state (when replay
                       (game-state-at-move (:record replay) (:current-move replay)))]
      [:div.replay-view
       (if replay
         [:div
          [:h2 "Game Replay"]
          [:div.game-area
           [game/stash-panel :left {:game-state game-state :read-only? true}]
           [replay-canvas]
           [game/stash-panel :right {:game-state game-state :read-only? true}]]
          [replay-controls]]
         [game-list-panel])])
    (finally
      (js/clearInterval timer))))
