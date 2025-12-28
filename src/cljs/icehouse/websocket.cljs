(ns icehouse.websocket
  (:require [icehouse.state :as state]))

(defonce ws (atom nil))

(defn get-ws-url []
  (let [loc (.-location js/window)
        protocol (if (= "https:" (.-protocol loc)) "wss:" "ws:")
        host (.-hostname loc)]
    ;; Always use port 3000 for backend WebSocket
    (str protocol "//" host ":3000/ws")))

(defn send! [msg]
  (when-let [socket @ws]
    (when (= 1 (.-readyState socket))
      (.send socket (js/JSON.stringify (clj->js msg))))))

(defn handle-message [event]
  (let [data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)
        msg-type (:type data)]
    (case msg-type
      "joined"
      (do
        (reset! state/player-id (:player-id data))
        (reset! state/room-id (:room-id data))
        (when-let [name (:name data)]
          (reset! state/player-name name))
        (when-let [colour (:colour data)]
          (reset! state/player-colour colour)))

      "players"
      (reset! state/players (:players data))

      "game-start"
      (do
        (reset! state/game-state (:game data))
        (reset! state/current-view :game))

      "piece-placed"
      (reset! state/game-state (:game data))

      "game-over"
      (js/alert (str "Game Over! Scores: " (pr-str (:scores data))))

      "error"
      (do
        (js/console.log "Error from server:" (:message data))
        (reset! state/error-message (:message data))
        ;; Auto-clear after 3 seconds
        (js/setTimeout #(reset! state/error-message nil) 3000))

      (js/console.log "Unknown message:" msg-type))))

(defn connect! []
  (reset! state/ws-status :connecting)
  (let [url (get-ws-url)
        socket (js/WebSocket. url)]
    (reset! ws socket)

    (set! (.-onopen socket)
          (fn [_]
            (js/console.log "WebSocket connected")
            (reset! state/ws-status :connected)
            (send! {:type "join"})))

    (set! (.-onmessage socket) handle-message)

    (set! (.-onclose socket)
          (fn [_]
            (js/console.log "WebSocket disconnected")
            (reset! state/ws-status :disconnected)
            (js/setTimeout connect! 3000)))

    (set! (.-onerror socket)
          (fn [e]
            (js/console.error "WebSocket error:" e)))))

(defn set-name! [name]
  (send! {:type "set-name" :name name}))

(defn set-colour! [colour]
  (send! {:type "set-colour" :colour colour}))

(defn toggle-ready! []
  (send! {:type "ready"}))

(defn place-piece! [x y size orientation angle target-id captured?]
  (send! {:type "place-piece"
          :x x
          :y y
          :size (name size)
          :orientation (name orientation)
          :angle angle
          :target-id target-id
          :captured captured?}))
