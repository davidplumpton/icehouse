(ns icehouse.replay
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.websocket :as ws]
            [icehouse.game :as game]))

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
       (case (:type move)
         :place-piece (conj board (:piece move))
         :capture-piece (vec (remove #(= (:id %) (:piece-id move)) board))
         board))
     []
     moves)))

(defn game-state-at-move
  "Create a game-state-like structure for rendering at a given move index"
  [record move-idx]
  {:players (:players record)
   :board (if (neg? move-idx)
            []
            (board-at-move record move-idx))})

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

(defn close-replay!
  "Close the replay view"
  []
  (reset! state/replay-state nil))

;; =============================================================================
;; Replay Canvas Component
;; =============================================================================

(defn replay-canvas
  "Canvas component for rendering replay board state"
  []
  (let [canvas-ref (r/atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                replay @state/replay-state
                game-state (when replay
                             (game-state-at-move (:record replay) (:current-move replay)))]
            (game/draw-board ctx game-state nil nil))))

      :component-did-update
      (fn [_]
        (when-let [canvas @canvas-ref]
          (let [ctx (.getContext canvas "2d")
                replay @state/replay-state
                game-state (when replay
                             (game-state-at-move (:record replay) (:current-move replay)))]
            (game/draw-board ctx game-state nil nil))))

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

(defn format-time
  "Format milliseconds as mm:ss"
  [ms]
  (let [total-secs (quot ms 1000)
        mins (quot total-secs 60)
        secs (mod total-secs 60)]
    (str (when (< mins 10) "0") mins ":" (when (< secs 10) "0") secs)))

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
          " - " (format-time current-time) " / " (format-time (:duration-ms record))]

         ;; Control buttons
         [:div {:style {:display "flex" :justify-content "center" :gap "10px"}}
          [:button.replay-btn {:on-click go-to-start!} "|<"]
          [:button.replay-btn {:on-click step-back!} "<"]
          [:button.replay-btn {:on-click toggle-play!}
           (if playing? "||" ">")]
          [:button.replay-btn {:on-click step-forward!} ">"]
          [:button.replay-btn {:on-click go-to-end!} ">|"]]

         ;; Speed controls
         [:div {:style {:margin-top "10px"}}
          [:span {:style {:color "#aaa" :margin-right "10px"}} "Speed:"]
          (for [s [0.5 1 2 4]]
            ^{:key s}
            [:button.speed-btn {:on-click #(set-speed! s)
                                :style {:background (if (= speed s) "#4CAF50" "#333")
                                        :margin "0 5px"}}
             (str s "x")])]

         ;; Game info
         [:div {:style {:margin-top "20px" :color "#888"}}
          [:div "Winner: " (or (:winner record) "None")]
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
  (reset! state/game-list nil))

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
                        :background "#4CAF50"
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
;; Main Replay View
;; =============================================================================

(defn replay-view
  "Main replay view component"
  []
  (let [replay @state/replay-state]
    [:div.replay-view {:style {:background "#1a1a2e"
                                :min-height "100vh"
                                :padding "20px"}}
     (if replay
       ;; Replay mode - show canvas and controls
       [:div
        [:h2 {:style {:color "white" :text-align "center"}} "Game Replay"]
        [replay-canvas]
        [replay-controls]]
       ;; No replay loaded - show game list
       [game-list-panel])]))

;; =============================================================================
;; Auto-play Timer
;; =============================================================================

(defonce auto-play-interval (atom nil))

(defn start-auto-play!
  "Start the auto-play timer"
  []
  (when @auto-play-interval
    (js/clearInterval @auto-play-interval))
  (reset! auto-play-interval
          (js/setInterval
           (fn []
             (when-let [replay @state/replay-state]
               (when (:playing? replay)
                 (let [max-move (dec (count (get-in replay [:record :moves])))
                       current (:current-move replay)]
                   (if (< current max-move)
                     (step-forward!)
                     (swap! state/replay-state assoc :playing? false))))))
           500)))  ;; Base interval, adjusted by speed

(defn stop-auto-play!
  "Stop the auto-play timer"
  []
  (when @auto-play-interval
    (js/clearInterval @auto-play-interval)
    (reset! auto-play-interval nil)))
