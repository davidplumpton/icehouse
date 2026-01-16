(ns icehouse.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [icehouse.state :as state]
            [icehouse.websocket :as ws]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]))

(defn app []
  (let [view @state/current-view]
    [:div.app
     (case view
       :lobby [lobby/lobby-view]
       :game [game/game-view]
       [:div "Loading..."])]))

(defonce root (atom nil))

(defn ^:export init []
  (ws/connect!)
  (reset! root (rdc/create-root (.getElementById js/document "app")))
  (rdc/render @root [app]))
