(ns icehouse.websocket
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]
            [icehouse.utils :as utils]))

(defonce clients (atom {}))

(defn handle-message [channel data]
  (try
    (let [msg (json/parse-string data true)
          msg-type (:type msg)]
      (if-not msg-type
        (do
          (println "Received message without type:" msg)
          (utils/send-msg! channel {:type "error" :message "Message missing 'type' field"}))
        (case msg-type
          ;; Lobby messages
          "join" (lobby/handle-join clients channel msg)
          "set-name" (lobby/handle-set-name clients channel msg)
          "set-colour" (lobby/handle-set-colour clients channel msg)
          "set-option" (lobby/handle-set-option clients channel msg)
          "ready" (lobby/handle-ready clients channel)

          ;; Game messages
          "place-piece" (game/handle-place-piece clients channel msg)
          "capture-piece" (game/handle-capture-piece clients channel msg)
          "finish" (game/handle-finish clients channel msg)

          ;; Replay messages
          "list-games" (game/handle-list-games channel)
          "load-game" (game/handle-load-game channel msg)

          ;; Unknown
          (utils/send-msg! channel {:type "error" :message (str "Unknown message type: " msg-type)}))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (println "Failed to parse WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type "error" :message "Invalid JSON"}))
    (catch Exception e
      (println "Error handling WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type "error" :message "Internal server error"}))))

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
