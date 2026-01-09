(ns icehouse.replay
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.websocket :as ws]
            [icehouse.game :as game]))

;; =============================================================================
;; Replay Constants
;; =============================================================================

(def replay-speeds [0.5 1 2 4])
(def replay-tick-ms 500)

;; =============================================================================
;; Board Reconstruction
;; =============================================================================

(defn board-at-move
  "Reconstruct board state at a given move index.
   Returns the board after applying moves 0 through move-idx (inclusive)."
  [record move-idx]
  (let [moves (take (inc move-idx) (:moves record))]
    (reduce
     (fn [board move]
       ;; Handle both keyword and string types (keywords from EDN, strings from JSON)
       (let [move-type (keyword (:type move))]
         (case move-type
           :place-piece (conj board (:piece move))
           :capture-piece (vec (remove (utils/by-id (:piece-id move)) board))
           (do
             (js/console.warn "Unknown move type in replay:" move-type)
             board))))
     []
     moves)))

(defn game-state-at-move
  "Create a game-state-like structure for rendering at a given move index"
  [record move-idx]
  (when record
    {:players (:players record)
     :board (if (neg? move-idx)
              []
              (board-at-move record move-idx))}))

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
  (reset! state/replay-state nil)
  (reset! state/current-view :lobby))

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
                        (game/calculate-iced-pieces (:board game-state)))]
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
                  :style {:display "block"
                          :margin "0 auto"
                          :border "2px solid #444"}}])})))

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
        [:div.replay-controls {:style {:text-align "center" :padding "20px"}}
         ;; Progress info
         [:div {:style {:margin-bottom "10px" :color "#aaa"}}
          "Move " (inc current-move) " / " total-moves
          " - " (utils/format-time current-time) " / " (utils/format-time (:duration-ms record))]

         ;; Time slider
         [:div {:style {:margin "15px auto" :width "80%" :max-width "600px"}}
          [:input {:type "range"
                   :min -1
                   :max (dec total-moves)
                   :value current-move
                   :on-change #(go-to-move! (js/parseInt (.. % -target -value)))
                   :style {:width "100%"
                           :height "8px"
                           :cursor "pointer"
                           :accent-color theme/green}}]]

         ;; Control buttons
         [:div {:style {:display "flex" :justify-content "center" :gap "10px"}}
          [:button.replay-btn {:on-click #(go-to-start!)} "|<"]
          [:button.replay-btn {:on-click #(step-back!)} "<"]
          [:button.replay-btn {:on-click #(toggle-play!)}
           (if playing? "||" ">")]
          [:button.replay-btn {:on-click #(step-forward!)} ">"]
          [:button.replay-btn {:on-click #(go-to-end!)} ">|"]]

         ;; Speed controls
         [:div {:style {:margin-top "10px"}}
          [:span {:style {:color "#aaa" :margin-right "10px"}} "Speed:"]
          (for [s replay-speeds]
            ^{:key s}
            [:button.speed-btn {:on-click #(set-speed! s)
                                :style {:background (if (= speed s) theme/green theme/button-inactive)
                                        :margin "0 5px"}}
             (str s "x")])]

         ;; Game info
         [:div {:style {:margin-top "20px" :color "#888"}}
          [:div "Winner: " (if-let [winner-id (:winner record)]
                            (or (get-in record [:players winner-id :name])
                                (get-in record [:players (keyword winner-id) :name])
                                winner-id)
                            "None")]
          [:div "End reason: " (name (or (:end-reason record) :unknown))]]

         ;; Close button
         [:button {:on-click close-replay!
                   :style {:margin-top "20px"
                           :background "#e53935"
                           :color "white"
                           :padding "10px 20px"
                           :border "none"
                           :border-radius "4px"
                           :cursor "pointer"}}
          "Close Replay"]]))))

;; =============================================================================
;; Game List Component
;; =============================================================================

(defn close-game-list!
  "Close the game list and return to lobby"
  []
  (reset! state/game-list nil)
  (reset! state/current-view :lobby))

(defn game-list-panel
  "Panel showing list of saved games"
  []
  (let [games @state/game-list]
    [:div.game-list-panel {:style {:padding "20px"
                                    :background "#1a1a2e"
                                    :min-height "100vh"
                                    :color "white"}}
     [:h2 "Saved Games"]
     [:div {:style {:margin-bottom "20px"}}
      [:button {:on-click ws/list-games!
                :style {:margin-right "10px"
                        :padding "8px 16px"
                        :background theme/green
                        :color "white"
                        :border "none"
                        :border-radius "4px"
                        :cursor "pointer"}}
       "Refresh List"]
      [:button {:on-click close-game-list!
                :style {:padding "8px 16px"
                        :background "#666"
                        :color "white"
                        :border "none"
                        :border-radius "4px"
                        :cursor "pointer"}}
       "Back to Lobby"]]
     (if (seq games)
       [:ul {:style {:list-style "none" :padding 0}}
        (for [game-id games]
          ^{:key game-id}
          [:li {:style {:margin "10px 0"}}
           [:button {:on-click #(ws/load-game! game-id)
                     :style {:padding "10px 20px"
                             :background "#333"
                             :color "white"
                             :border "1px solid #555"
                             :border-radius "4px"
                             :cursor "pointer"}}
            game-id]])]
       [:p {:style {:color "#888"}}
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
    (let [replay @state/replay-state]
      [:div.replay-view {:style {:background "#1a1a2e"
                                  :min-height "100vh"
                                  :padding "20px"}}
       (if replay
         [:div
          [:h2 {:style {:color "white" :text-align "center"}} "Game Replay"]
          [replay-canvas]
          [replay-controls]]
         [game-list-panel])])
    (finally
      (js/clearInterval timer))))
