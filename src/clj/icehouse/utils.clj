(ns icehouse.utils
  (:require [cheshire.core :as json]
            [org.httpkit.server :as http]))

(defn send-msg!
  "Send a JSON message to a single channel"
  [channel msg]
  (http/send! channel (json/generate-string msg)))

(defn broadcast-room!
  "Broadcast a JSON message to all clients in a room"
  [clients room-id msg]
  (doseq [[ch client] @clients
          :when (= (:room-id client) room-id)]
    (send-msg! ch msg)))
