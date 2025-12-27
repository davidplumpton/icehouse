(ns icehouse.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [icehouse.state :as state]
            [icehouse.websocket :as ws]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]))

(defn connection-warning []
  (let [status @state/ws-status]
    (when (not= status :connected)
      [:div.connection-warning
       {:style {:background (if (= status :connecting) "#ffc107" "#dc3545")
                :color (if (= status :connecting) "#000" "#fff")
                :padding "0.5rem 1rem"
                :text-align "center"
                :font-weight "bold"}}
       (if (= status :connecting)
         "Connecting to server..."
         "Disconnected from server. Reconnecting...")])))

(defn app []
  (let [view @state/current-view]
    [:div.app
     [connection-warning]
     (case view
       :lobby [lobby/lobby-view]
       :game [game/game-view]
       [:div "Loading..."])]))

(defonce root (atom nil))

(defn ^:export init []
  (ws/connect!)
  (reset! root (rdc/create-root (.getElementById js/document "app")))
  (rdc/render @root [app]))
