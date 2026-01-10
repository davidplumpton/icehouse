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
;; Message Handlers
;; =============================================================================

(defn- handle-joined
  "Handle player joined confirmation"
  [data]
  (swap! state/current-player merge
         {:id (:player-id data)}
         (when-let [n (:name data)] {:name n})
         (when-let [c (:colour data)] {:colour c})))

(defn- handle-players
  "Handle player list update"
  [data]
  (reset! state/players (:players data)))

(defn- handle-options
  "Handle game options update"
  [data]
  (reset! state/game-options (:options data)))

(defn- handle-game-start
  "Handle game start"
  [data]
  (reset! state/game-state (:game data))
  (reset! state/game-result nil)
  (swap! state/ui-state assoc :selected-piece {:size :small :orientation :standing :captured? false})
  (reset! state/current-view :game))

(defn- handle-piece-placed
  "Handle piece placement update"
  [data]
  (reset! state/game-state (:game data)))

(defn- handle-piece-captured
  "Handle piece capture update"
  [data]
  (reset! state/game-state (:game data)))

(defn- handle-player-finished
  "Handle player finished signal"
  [data]
  (reset! state/game-state (:game data)))

(defn- handle-game-over
  "Handle game over with final scores"
  [data]
  (reset! state/game-result {:scores (:scores data)
                             :icehouse-players (:icehouse-players data)
                             :over-ice (:over-ice data)
                             :game-id (:game-id data)}))

(defn- handle-game-list
  "Handle list of saved games"
  [data]
  (reset! state/current-view :replay)
  (reset! state/game-list (:games data)))

(defn- handle-game-record
  "Handle game record for replay"
  [data]
  (reset! state/game-list nil)
  (reset! state/game-result nil)
  (reset! state/current-view :replay)
  (reset! state/replay-state {:record (:record data)
                              :current-move 0
                              :playing? false
                              :speed 1}))

(defn- handle-error
  "Handle error message from server"
  [data]
  (js/console.log "Error from server:" (:message data))
  (reset! state/error-message (:message data))
  (js/setTimeout #(reset! state/error-message nil) error-message-timeout-ms))

(def ^:private message-handlers
  "Map of message types to handler functions"
  {msg/joined         handle-joined
   msg/players        handle-players
   msg/options        handle-options
   msg/game-start     handle-game-start
   msg/piece-placed   handle-piece-placed
   msg/piece-captured handle-piece-captured
   msg/player-finished handle-player-finished
   msg/game-over      handle-game-over
   msg/game-list      handle-game-list
   msg/game-record    handle-game-record
   msg/error          handle-error})

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
    (let [raw-data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
      ;; Log validation errors but still process the message
      (when-not (validate-incoming-message raw-data)
        (js/console.warn "Message validation warning:" raw-data))
      (let [msg-type (:type raw-data)]
        (if-let [handler (get message-handlers msg-type)]
          (handler raw-data)
          (if msg-type
            (js/console.log "Unknown message:" msg-type)
            (js/console.warn "Received message without type:" raw-data)))))
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
