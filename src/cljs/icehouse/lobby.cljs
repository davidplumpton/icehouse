(ns icehouse.lobby
  (:require [reagent.core :as r]
            [icehouse.state :as state]
            [icehouse.theme :as theme]
            [icehouse.utils :as utils]
            [icehouse.websocket :as ws]))

(defn colour-picker []
  [:div.colour-picker
   [:label "Choose your colour:"]
   [:div.colours
    (doall
     (for [colour state/colours]
       ^{:key colour}
       [:div.colour-option
        {:style {:background-color colour
                 :border (if (= colour (:colour @state/current-player))
                           "3px solid white"
                           "3px solid transparent")}
         :on-click #(do
                      (swap! state/current-player assoc :colour colour)
                      (ws/set-colour! colour))}]))]])

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

(defn game-options-panel []
  (let [options @state/game-options]
    (when options
      [:div.game-options {:style {:margin-top "20px"
                                   :padding "15px"
                                   :background theme/board-background
                                   :border-radius "8px"}}
       [:h3 {:style {:margin-top 0 :color "#aaa"}} "Game Options"]
       [:div.option {:style {:margin "10px 0"}}
        [:label {:style {:display "flex" :align-items "center" :cursor "pointer"}}
         [:input {:type "checkbox"
                  :checked (:icehouse-rule options)
                  :on-change #(ws/set-option! :icehouse-rule (.. % -target -checked))
                  :style {:margin-right "10px"}}]
         [:span "Icehouse Rule"]
         [:span {:style {:color "#888" :font-size "0.85em" :margin-left "10px"}}
          "(0 pts if all defenders iced after 8+ pieces)"]]]
       [:div.option {:style {:margin "10px 0"}}
        [:label {:style {:display "flex" :align-items "center" :cursor "pointer"}}
         [:input {:type "checkbox"
                  :checked (:timer-enabled options)
                  :on-change #(ws/set-option! :timer-enabled (.. % -target -checked))
                  :style {:margin-right "10px"}}]
         [:span "Game Timer"]
         [:span {:style {:color "#888" :font-size "0.85em" :margin-left "10px"}}
          "(random 2-5 min)"]]]])))

(defn watch-replays-button []
  [:button.watch-replays-btn
   {:on-click #(ws/list-games!)
    :style {:margin-top "20px"
            :padding "10px 20px"
            :background theme/green
            :color "white"
            :border "none"
            :border-radius "4px"
            :cursor "pointer"}}
   "Watch Replays"])

(defn lobby-view []
  [:div.lobby
   [:h1 "Icehouse"]
   [:h2 "Lobby"]
   [:p.info "Waiting for 3-4 players to join and ready up..."]
   [name-input]
   [colour-picker]
   [player-list]
   [game-options-panel]
   [ready-button]
   [watch-replays-button]])
