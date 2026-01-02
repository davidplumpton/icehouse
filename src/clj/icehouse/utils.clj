(ns icehouse.utils
  (:require [cheshire.core :as json]
             [org.httpkit.server :as http]
             [icehouse.schema :as schema]
             [malli.core :as m]))

(defn validate-outgoing-message
  "Validate outgoing server message against schema"
  [msg]
  (if (m/validate schema/ServerMessage msg)
    msg
    (do
      (println "Invalid outgoing message:" msg)
      (println "Validation errors:" (m/explain schema/ServerMessage msg))
      nil)))

(defn send-msg!
  "Send a JSON message to a single channel"
  [channel msg]
  ;; Validation is optional - log failures but still send the message
  ;; This allows the app to function even if schemas don't match reality
  (when-not (validate-outgoing-message msg)
    (println "Warning: Message validation failed:" msg))
  (http/send! channel (json/generate-string msg)))

(defn broadcast-room!
  "Broadcast a JSON message to all clients in a room"
  [clients room-id msg]
  (doseq [[ch client] @clients
          :when (= (:room-id client) room-id)]
    (send-msg! ch msg)))
