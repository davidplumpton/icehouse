(ns icehouse.lobby
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.constants :as const]
            [icehouse.websocket :as ws]))

(defn colour-picker []
  (let [players @state/players
        current-player @state/current-player
        taken-colours (->> players
                           (remove #(= (:id %) (:id current-player)))
                           (map :colour)
                           set)]
    [:div.colour-picker
     [:label "Choose your colour:"]
     [:div.colours
      (doall
       (for [colour state/colours]
         (let [taken? (contains? taken-colours colour)
               selected? (= colour (:colour current-player))]
           ^{:key colour}
           [:div.colour-option
            {:class [(when selected? "selected")
                     (when (and taken? (not selected?)) "taken")]
             :style {:background-color colour}
             :on-click #(when-not taken?
                          (swap! state/current-player assoc :colour colour)
                          (ws/set-colour! colour))}])))]]))

(defn name-input []
  [:div.name-input
   [:label "Your name:"]
   [:input {:type "text"
            :value (:name @state/current-player)
            :placeholder "Enter your name"
            :on-change #(swap! state/current-player assoc :name (-> % .-target .-value))
            :on-blur #(ws/set-name! (:name @state/current-player))}]])

(defn player-list []
  [:div.player-list
   [:h3 "Players in lobby:"]
   [:ul
    (for [player @state/players]
      ^{:key (:id player)}
      [:li {:style {:color (:colour player)}}
       (:name player "Anonymous")
       (when (:ready player) " - Ready")])]])

(defn ready-button []
  (let [my-id (:id @state/current-player)
        me (->> @state/players
                (filter (utils/by-id my-id))
                first)]
    [:button.ready-btn
     {:on-click #(ws/toggle-ready!)
      :class (when (:ready me) "is-ready")}
     (if (:ready me) "Not Ready" "Ready!")]))

(defn throttle-input
  "Input component for the placement throttle option. Uses local state for typing 
   and syncs with the server on blur."
  []
  (let [local-value (r/atom nil)]
    (fn []
      (let [options @state/game-options
            server-value (or (:placement-throttle options) const/default-placement-throttle-sec)
            display-value (if (nil? @local-value) server-value @local-value)]
        [:input {:type "text"
                 :value display-value
                 :on-focus #(reset! local-value (str server-value))
                 :on-change #(reset! local-value (.. % -target -value))
                 :on-blur #(let [parsed (js/parseFloat @local-value)]
                             (when-not (js/isNaN parsed)
                               (ws/set-option! :placement-throttle parsed))
                             (reset! local-value nil))
                 :class "throttle-input"}]))))

(defn game-options-panel []
  (let [options @state/game-options]
    (when options
      [:div.game-options
       [:h3 "Game Options"]
       [:div.option
        [:label.clickable
         [:input {:type "checkbox"
                  :checked (:icehouse-rule options)
                  :on-change #(ws/set-option! :icehouse-rule (.. % -target -checked))}]
         [:span "Icehouse Rule"]
         [:span.option-hint
          "(0 pts if all defenders iced after 8+ pieces)"]]]
       [:div.option
        [:label.clickable
         [:input {:type "checkbox"
                  :checked (:timer-enabled options)
                  :on-change #(ws/set-option! :timer-enabled (.. % -target -checked))}]
         [:span "Game Timer"]
         [:span.option-hint
          "(random 2-5 min)"]]]
       [:div.option
        [:label
         [:span.label-text "Placement Throttle"]
         [throttle-input]
         [:span "sec"]
         [:span.option-hint
          "(cooldown between placements)"]]]])))

(defn lobby-view []
  [:div.lobby
   [:h1 "Icehouse"]
   [:h2 "Lobby"]
   [:p.info "Waiting for 3-4 players to join and ready up..."]
   [name-input]
   [colour-picker]
   [player-list]
   [game-options-panel]
   [ready-button]])
