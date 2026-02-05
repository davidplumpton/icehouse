(ns icehouse.game.replay-handlers
  "Replay listing/loading message handlers."
  (:require [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.storage :as storage]))

(defn handle-list-games
  "Send list of saved game record IDs to client."
  [channel]
  (utils/send-msg! channel
                   {:type msg/game-list
                    :games (storage/list-game-records)}))

(defn handle-load-game
  "Load and send a game record to client."
  [channel msg]
  (if-let [record (storage/load-game-record (:game-id msg))]
    (utils/send-msg! channel
                     {:type msg/game-record
                      :record record})
    (utils/send-msg! channel
                     {:type msg/error
                      :message "Game not found"})))
