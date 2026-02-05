(ns icehouse.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.server :as app-server]
            [icehouse.websocket :as ws]
            [icehouse.game :as game]
            [icehouse.lobby :as lobby]))

(deftest reset-all-clears-global-state-test
  (testing "reset-all! clears clients, games, and room options"
    (reset! ws/clients {:ch1 {:room-id "room-a" :name "Alice"}})
    (reset! game/games {"room-a" {:game-id "g1" :room-id "room-a"}})
    (reset! lobby/room-options {"room-a" {:timer-enabled false}})

    (app-server/reset-all!)

    (is (= {} @ws/clients))
    (is (= {} @game/games))
    (is (= {} @lobby/room-options))))
