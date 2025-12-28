(ns icehouse.lobby
  (:require [reagent.core :as r]
            [icehouse.state :as state]
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
                 :border (if (= colour @state/player-colour)
                           "3px solid white"
                           "3px solid transparent")}
         :on-click #(do
                      (reset! state/player-colour colour)
                      (ws/set-colour! colour))}]))]])

(defn name-input []
  [:div.name-input
   [:label "Your name:"]
   [:input {:type "text"
            :value @state/player-name
            :placeholder "Enter your name"
            :on-change #(reset! state/player-name (-> % .-target .-value))
            :on-blur #(ws/set-name! @state/player-name)}]])

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
  (let [current-player (->> @state/players
                            (filter #(= (:id %) @state/player-id))
                            first)]
    [:button.ready-btn
     {:on-click #(ws/toggle-ready!)
      :class (when (:ready current-player) "is-ready")}
     (if (:ready current-player) "Not Ready" "Ready!")]))

(defn watch-replays-button []
  [:button.watch-replays-btn
   {:on-click #(ws/list-games!)
    :style {:margin-top "20px"
            :padding "10px 20px"
            :background "#4CAF50"
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
   [ready-button]
   [watch-replays-button]])
