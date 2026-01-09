(ns icehouse.websocket
  (:require [icehouse.messages :as msg]
             [icehouse.state :as state]
             [icehouse.schema :as schema]
             [malli.core :as m]))

(defonce ws (atom nil))

;; =============================================================================
;; Constants
;; =============================================================================

(def reconnect-timeout-ms 3000)
(def error-message-timeout-ms 3000)

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn validate-incoming-message
  "Validate incoming server message against schema"
  [data]
  (if (m/validate schema/ServerMessage data)
    data
    (do
      (js/console.error "Invalid incoming message:" data)
      (js/console.error "Validation errors:" (clj->js (m/explain schema/ServerMessage data)))
      nil)))

(defn validate-outgoing-message
  "Validate outgoing client message against schema"
  [data]
  (if (m/validate schema/ClientMessage data)
    data
    (do
      (js/console.error "Invalid outgoing message:" data)
      (js/console.error "Validation errors:" (clj->js (m/explain schema/ClientMessage data)))
      nil)))

(defn get-ws-url []
  (let [loc (.-location js/window)
        protocol (if (= "https:" (.-protocol loc)) "wss:" "ws:")
        host (.-hostname loc)]
    ;; Always use port 3000 for backend WebSocket
    (str protocol "//" host ":3000/ws")))

(defn send! [msg]
  ;; Validation is non-blocking - log warnings but send anyway
  (when-not (validate-outgoing-message msg)
    (js/console.warn "Message validation warning:" msg))
  (when-let [socket @ws]
    (when (= 1 (.-readyState socket))
      (.send socket (js/JSON.stringify (clj->js msg))))))

(defn handle-message [event]
  (try
    (let [raw-data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)
          data raw-data]  ;; Use data as-is, validation is non-blocking
      ;; Log validation errors but still process the message
      (when-not (validate-incoming-message raw-data)
        (js/console.warn "Message validation warning:" raw-data))
      (let [msg-type (:type data)]
        (when-not msg-type
          (js/console.warn "Received message without type:" data))
        (condp = msg-type
        msg/joined
        (swap! state/current-player merge
               {:id (:player-id data)}
               (when-let [n (:name data)] {:name n})
               (when-let [c (:colour data)] {:colour c}))

        msg/players
        (reset! state/players (:players data))

        msg/options
        (reset! state/game-options (:options data))

        msg/game-start
        (do
          (reset! state/game-state (:game data))
          (reset! state/game-result nil)  ;; Clear previous game result
          (swap! state/ui-state assoc :selected-piece {:size :small :orientation :standing :captured? false})
          (reset! state/current-view :game))

        msg/piece-placed
        (reset! state/game-state (:game data))

        msg/piece-captured
        (reset! state/game-state (:game data))

        msg/player-finished
        (reset! state/game-state (:game data))

        msg/game-over
        (reset! state/game-result {:scores (:scores data)
                                    :icehouse-players (:icehouse-players data)
                                    :over-ice (:over-ice data)
                                    :game-id (:game-id data)})

        msg/game-list
        (do
          (reset! state/current-view :replay)
          (reset! state/game-list (:games data)))

        msg/game-record
        (do
          (reset! state/game-list nil)  ;; Clear game list when loading replay
          (reset! state/game-result nil) ;; Clear game results overlay
          (reset! state/current-view :replay) ;; Explicitly switch to replay view
          (reset! state/replay-state {:record (:record data)
                                       :current-move 0
                                       :playing? false
                                       :speed 1}))

        msg/error
        (do
          (js/console.log "Error from server:" (:message data))
          (reset! state/error-message (:message data))
          ;; Auto-clear after timeout
          (js/setTimeout #(reset! state/error-message nil) error-message-timeout-ms))

        (js/console.log "Unknown message:" msg-type))))
    (catch js/Error e
      (js/console.error "Failed to parse WebSocket message:" e)
      (js/console.error "Raw data:" (.-data event)))))

(defn connect! []
  (reset! state/ws-status :connecting)
  (let [url (get-ws-url)
        socket (js/WebSocket. url)]
    (reset! ws socket)

    (set! (.-onopen socket)
          (fn [_]
            (js/console.log "WebSocket connected")
            (reset! state/ws-status :connected)
            (send! {:type msg/join})))

    (set! (.-onmessage socket) handle-message)

    (set! (.-onclose socket)
          (fn [_]
            (js/console.log "WebSocket disconnected")
            (reset! state/ws-status :disconnected)
            (js/setTimeout connect! reconnect-timeout-ms)))

    (set! (.-onerror socket)
          (fn [e]
            (js/console.error "WebSocket error:" e)))))

(defn set-name! [name]
  (send! {:type msg/set-name :name name}))

(defn set-colour! [colour]
  (send! {:type msg/set-colour :colour colour}))

(defn toggle-ready! []
  (send! {:type msg/ready}))

(defn place-piece! [x y size orientation angle target-id captured?]
  (send! {:type msg/place-piece
          :x x
          :y y
          :size (name size)
          :orientation (name orientation)
          :angle angle
          :target-id target-id
          :captured captured?}))

(defn capture-piece! [piece-id]
  "Capture an over-iced attacker piece"
  (send! {:type msg/capture-piece
          :piece-id piece-id}))

(defn list-games!
  "Request list of saved game records"
  []
  (send! {:type msg/list-games}))

(defn load-game!
  "Load a saved game record for replay"
  [game-id]
  (send! {:type msg/load-game :game-id game-id}))

(defn set-option!
  "Set a game option for the room"
  [key value]
  (send! {:type msg/set-option :key (name key) :value value}))

(defn finish!
  "Signal that the player wants to end the game"
  []
  (send! {:type msg/finish}))
