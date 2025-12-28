(ns icehouse.websocket
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]
            [icehouse.utils :as utils]))

(defonce clients (atom {}))

(defn broadcast! [room-id msg]
  (doseq [[ch client] @clients
          :when (= (:room-id client) room-id)]
    (utils/send-msg! ch msg)))

(defn handle-message [channel data]
  (let [msg (json/parse-string data true)
        client (get @clients channel)
        msg-type (:type msg)]
    (case msg-type
      ;; Lobby messages
      "join" (lobby/handle-join clients channel msg)
      "set-name" (lobby/handle-set-name clients channel msg)
      "set-colour" (lobby/handle-set-colour clients channel msg)
      "ready" (lobby/handle-ready clients channel)

      ;; Game messages
      "place-piece" (game/handle-place-piece clients channel msg)
      "capture-piece" (game/handle-capture-piece clients channel msg)

      ;; Unknown
      (utils/send-msg! channel {:type "error" :message "Unknown message type"}))))

(defn handler [req]
  (http/with-channel req channel
    (swap! clients assoc channel {:room-id nil :name nil :colour nil :ready false})
    (println "Client connected")

    (http/on-close channel
      (fn [status]
        (println "Client disconnected:" status)
        (lobby/handle-disconnect clients channel)
        (swap! clients dissoc channel)))

    (http/on-receive channel
      (fn [data]
        (handle-message channel data)))))
