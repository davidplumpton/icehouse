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

(defn- dispatch-message!
  "Route a validated client message to the matching handler."
  [channel message]
  (let [msg-type (:type message)]
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
        msg/validate-move (game/handle-validate-move clients channel message)
        msg/query-legal-moves (game/handle-query-legal-moves clients channel message)

        ;; Replay messages
        msg/list-games (game/handle-list-games channel)
        msg/load-game (game/handle-load-game channel message)

        ;; Unknown
        (utils/send-msg! channel {:type msg/error :message (str "Unknown message type: " msg-type)})))))

(defn handle-message [channel data]
  (try
    (let [message (json/parse-string data true)
          validated-message (utils/validate-incoming-message message)]
      (if-not validated-message
        (let [explanation (schema/explain schema/ClientMessage message)]
          (println "Invalid message, discarding:" message)
          (utils/send-msg! channel {:type msg/error 
                                    :message (str "Invalid message format: " 
                                                 (pr-str explanation))}))
        (dispatch-message! channel message)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (println "Failed to parse WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type msg/error :message "Invalid JSON"}))
    (catch clojure.lang.ExceptionInfo e
      (println "Error handling WebSocket message:" (.getMessage e))
      (println "Raw data:" data)
      (utils/send-msg! channel {:type msg/error :message "Internal server error"}))))

(defn handler [req]
  (http/with-channel req channel
    (swap! clients assoc channel {:room-id nil :name nil :colour nil :ready false})

    (http/on-close channel
      (fn [_status]
        (lobby/handle-disconnect clients channel)))

    (http/on-receive channel
      (fn [data]
        (handle-message channel data)))))
