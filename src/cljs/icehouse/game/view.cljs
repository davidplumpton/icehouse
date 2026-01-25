(ns icehouse.game.view
  "UI components for the game view."
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.websocket :as ws]
            [icehouse.utils :as utils]
            [icehouse.geometry :as geo]
            [icehouse.game.constants :as const]
            [icehouse.game.placement :as placement]
            [icehouse.game.interactions :as interact]
            [icehouse.game.canvas :as canvas]
            [icehouse.game.rendering :as render]))

;; =============================================================================
;; Piece Selector Component
;; =============================================================================

(defn piece-selector []
  (let [ui @state/ui-state
        {:keys [size orientation captured?]} (:selected-piece ui)
        my-id (:id @state/current-player)
        is-icehoused? (and my-id (contains? @state/icehoused-players my-id))
        attack-allowed (interact/can-attack?)
        has-captured (interact/has-captured-pieces?)
        zoom? (:zoom-active ui)
        move-mode? (:move-mode ui)
        has-size? (placement/has-pieces-of-size? size captured?)]
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
        [:span.captured-indicator "[Captured]"])
      (when is-icehoused?
        [:span.icehoused-indicator "[ICEHOUSE]"])
      (when zoom?
        [:span.zoom-indicator
         (str "[ZOOM " const/zoom-scale "x]")])
      (when move-mode?
        [:span.move-mode-indicator "[MOVE]"])]
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

;; =============================================================================
;; Stash Components
;; =============================================================================

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
       [render/draw-stash-pyramid size colour {:captured? captured? :count piece-count}]])))

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
      (reset! placement/stash-drag-pending {:size piece-size :captured? is-captured?}))))

(defn- captured-pieces-section
  "Renders the captured pieces section of a player's stash."
  [{:keys [is-me selection captured-by-size size-to-hotkey start-stash-drag]}]
  (let [{:keys [size captured?]} selection]
    [:div.captured-pieces
     [:div.captured-header "Captured:"]
     (for [sz [:large :medium :small]
           :let [caps (get captured-by-size sz)
                 hotkey (get size-to-hotkey sz)]
           :when (seq caps)]
       ^{:key (str "cap-row-" (name sz))}
       [:div.captured-row {:style (merge (when (and is-me captured? (= size sz))
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
          [:span.captured-hotkey (str hotkey)])
        (for [[idx cap-piece] (map-indexed vector caps)]
          ^{:key (str "cap-" (name sz) "-" idx)}
          [render/draw-stash-pyramid sz (:colour cap-piece) {:captured? true}])])]))

(defn player-stash
  "Renders a single player's stash of unplayed pieces.
   opts can include :read-only? true for replay mode (no interaction)."
  ([player-id player-data] (player-stash player-id player-data nil))
  ([player-id player-data opts]
   (let [read-only? (:read-only? opts)
         pieces (or (:pieces player-data) const/default-pieces)
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
         [:span.icehoused-indicator "(Icehouse!)"])]
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

;; =============================================================================
;; Status Components
;; =============================================================================

(defn error-display
  "Displays the current error message from the state atom, if any."
  []
  (when-let [error @state/error-message]
    [:div.error-message error]))

(defn icehouse-banner
  "Displays a prominent banner when the current player is icehoused, 
   explaining that they can only play captured pieces."
  []
  (let [my-id (:id @state/current-player)
        is-icehoused? (and my-id (contains? @state/icehoused-players my-id))]
    (when is-icehoused?
      [:div.icehouse-banner
       [:span.title "You're in the Icehouse!"]
       [:span.subtitle "Only captured pieces can be played. Capture opponents to continue!"]])))

(defn placement-cooldown-indicator
  "Subtle circular cooldown indicator with smooth animation that depletes 
   as the placement throttle cooldown progresses."
  []
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

(defn game-timer
  "Display the remaining game time in a formatted string (MM:SS)."
  []
  (let [game @state/game-state
        current @state/current-time]
    (when-let [ends-at (:ends-at game)]
      (let [remaining (max 0 (- ends-at current))
            urgent? (< remaining const/timer-urgent-threshold-ms)]
        [:div.game-timer
         {:class (if urgent? "urgent" "normal")}
         (utils/format-time remaining)]))))

(defn finish-button
  "Button for players to signal they want to end the game. Shows how many 
   players have already finished."
  []
  (let [game @state/game-state
        player-id (:id @state/current-player)
        finished-set (set (or (:finished game) []))
        players-map (:players game)
        player-count (count players-map)
        finished-count (count finished-set)
        i-finished? (contains? finished-set player-id)]
    [:div.finish-controls
     [:button.finish-btn
      {:style {:background (if i-finished? theme/green "#4a4a5e")
               :opacity (if i-finished? 0.8 1)}
       :disabled i-finished?
       :on-click #(ws/finish!)}
      (if i-finished? "✓ Finished" "End Game")]
     (when (pos? finished-count)
       [:span.finish-status
        (str finished-count "/" player-count)])]))

;; =============================================================================
;; Overlay Components
;; =============================================================================

(defn- scores-table
  "Renders the scores table for the game results overlay."
  [{:keys [sorted-scores players-map icehouse-players max-score]}]
  [:table {:style {:width "100%" :border-collapse "collapse" :margin "1rem 0"}}
   [:thead
    [:tr
     [:th {:style {:text-align "left" :padding "0.5rem"}} "Player"]
     [:th {:style {:text-align "right" :padding "0.5rem"}} "Score"]]]
   [:tbody
    (for [score-entry sorted-scores]
      (let [[player-id score] score-entry
            player-data (get players-map (keyword player-id))
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

(defn game-results-overlay
  "Display the final scores and winner(s) when the game ends."
  []
  (when-let [result @state/game-result]
    (let [scores (:scores result)
          icehouse-players (set (:icehouse-players result))
          players-map (:players @state/game-state)
          sorted-scores (sort-by (fn [entry] (- (second entry))) scores)
          max-score (apply max (vals scores))]
      [:div.game-results-overlay
       [:div.game-results
        [:h2 "Game Over!"]
        [scores-table {:sorted-scores sorted-scores
                       :players-map players-map
                       :icehouse-players icehouse-players
                       :max-score max-score}]
        [results-action-buttons {:game-id (:game-id result)}]]])))

(defn help-overlay
  "Display a full-screen help overlay with keyboard shortcut descriptions 
   and gameplay tips."
  []
  (when (:show-help @state/ui-state)
    [:div.help-overlay
     {:on-click #(swap! state/ui-state assoc :show-help false)}
     [:div.help-content
      {:on-click #(.stopPropagation %)}
      [:h2 "Keyboard Controls"]
      [:table
       [:tbody
        [:tr [:td {:style {:color theme/gold}} "1 / 2 / 3"]
         [:td "Select piece size (Small / Medium / Large)"]]
        [:tr [:td {:style {:color theme/gold}} "4 / 5 / 6"]
         [:td "Select captured piece (Small / Medium / Large)"]]
        [:tr [:td {:style {:color theme/gold}} "D"]
         [:td "Defend mode (standing piece)"]]
        [:tr [:td {:style {:color theme/gold}} "A"]
         [:td "Attack mode (pointing piece)"]]
        [:tr [:td {:style {:color theme/gold}} "C"]
         [:td "Capture piece / Toggle captured mode"]]
        [:tr [:td {:style {:color theme/gold}} "Z"]
         [:td (str "Toggle " const/zoom-scale "x zoom for fine placement")]]
        [:tr [:td {:style {:color theme/gold}} "M"]
         [:td "Toggle move mode (adjust position, keep angle)"]]
        [:tr [:td {:style {:color theme/gold}} "Shift"]
         [:td "Hold while dragging for move mode"]]
        [:tr [:td {:style {:color theme/gold}} "Escape"]
         [:td "Cancel placement / Close help"]]
        [:tr [:td {:style {:color theme/gold}} "?"]
         [:td "Toggle this help"]]]]
      [:h3 "Gameplay Tips"]
      [:ul
       [:li "Click and drag to place a piece with rotation"]
       [:li "Attack mode unlocks after placing 2 pieces"]
       [:li "Attackers must point at an opponent's defender within range"]
       [:li "Over-ice: When attack pips exceed defense, capture excess attackers"]]
      [:div.help-footer
       "Click anywhere or press Escape to close"]]]))

;; =============================================================================
;; Main Game View
;; =============================================================================

(defn game-view []
  (let [timer-interval (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (.addEventListener js/document "keydown" interact/handle-keydown)
        ;; Start timer update interval
        (reset! timer-interval
                (js/setInterval #(reset! state/current-time (js/Date.now)) 1000)))

      :component-will-unmount
      (fn [this]
        (.removeEventListener js/document "keydown" interact/handle-keydown)
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
          [canvas/game-canvas]
          [stash-panel :right]]])})))
