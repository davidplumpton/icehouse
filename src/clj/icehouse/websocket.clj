(ns icehouse.websocket
  (:require [org.httpkit.server :as http]
             [cheshire.core :as json]
             [icehouse.lobby :as lobby]
             [icehouse.game :as game]
             [icehouse.messages :as msg]
             [icehouse.utils :as utils]
             [icehouse.schema :as schema]
             [malli.core :as m]))

(defonce clients (atom {}))

(defn validate-incoming-message
  "Validate incoming client message against schema"
  [message]
  (if (m/validate schema/ClientMessage message)
    message
    (do
      (println "Invalid client message:" message)
      (println "Validation errors:" (m/explain schema/ClientMessage message))
      nil)))

(defn handle-message [channel data]
  (try
    (let [message (json/parse-string data true)
          validated-message (validate-incoming-message message)
          msg-type (:type message)]
      (if-not validated-message
        (do
          (println "Invalid message, discarding:" message)
          (utils/send-msg! channel {:type msg/error :message "Invalid message format"}))
        (if-not msg-type
          (do
            (println "Received message without type:" message)
            (utils/send-msg! channel {:type msg/error :message "Message missing 'type' field"}))
          (condp = msg-type
          ;; Lobby messages
          msg/join (lobby/handle-join clients channel message)
          msg/set-name (lobby/handle-set-name clients channel message)
          msg/set-colour (lobby/handle-set-colour clients channel message)
          msg/set-option (lobby/handle-set-option clients channel message)
          msg/ready (lobby/handle-ready clients channel)

          ;; Game messages
          msg/place-piece (game/handle-place-piece clients channel message)
          msg/capture-piece (game/handle-capture-piece clients channel message)
          msg/finish (game/handle-finish clients channel message)

          ;; Replay messages
          msg/list-games (game/handle-list-games channel)
          msg/load-game (game/handle-load-game channel message)

          ;; Unknown
          (utils/send-msg! channel {:type msg/error :message (str "Unknown message type: " msg-type)}))))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (println "Failed to parse WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type msg/error :message "Invalid JSON"}))
    (catch Exception e
      (println "Error handling WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type msg/error :message "Internal server error"}))))

(defn handler [req]
  (http/with-channel req channel
    (swap! clients assoc channel {:room-id nil :name nil :colour nil :ready false})

    (http/on-close channel
      (fn [_status]
        (lobby/handle-disconnect clients channel)
        (swap! clients dissoc channel)))

    (http/on-receive channel
      (fn [data]
        (handle-message channel data)))))
