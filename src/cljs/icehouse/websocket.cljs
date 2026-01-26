(ns icehouse.websocket
  (:require [icehouse.audio :as audio]
            [icehouse.messages :as msg]
            [icehouse.state :as state]
            [icehouse.schema :as schema]
            [icehouse.constants :as const]
            [icehouse.utils :as utils]
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
  (reset! state/icehoused-players #{})  ;; Clear icehoused players for new game
  (swap! state/ui-state assoc :selected-piece {:size :small :orientation :standing :captured? false})
  (reset! state/current-view :game))

(defn- handle-piece-placed
  "Handle piece placement update.
   Starts cooldown timer only when the placed piece belongs to current player."
  [data]
  (reset! state/game-state (:game data))
  ;; Start cooldown only if this was our placement
  (let [piece (:piece data)
        current-player-id (:id @state/current-player)
        piece-player-id (utils/normalize-player-id (:player-id piece))]
    (when piece
      (audio/play-placement-sound (:size piece)))
    (when (and current-player-id
               (= (utils/normalize-player-id current-player-id) piece-player-id))
      (let [throttle-sec (get-in (:game data) [:options :placement-throttle] const/default-placement-throttle-sec)
            throttle-ms (* throttle-sec 1000)
            now (js/Date.now)]
        (swap! state/ui-state assoc
               :last-placement-time now
               :throttle-warning {:throttle-ends-at (+ now throttle-ms)
                                  :throttle-duration-ms throttle-ms})))))

(defn- handle-piece-captured
  "Handle piece capture update"
  [data]
  (reset! state/game-state (:game data)))

(defn- handle-player-finished
  "Handle player finished signal"
  [data]
  (reset! state/game-state (:game data)))

(defn- handle-player-icehoused
  "Handle player icehoused notification.
   Adds the player to the icehoused set - they can only play captured pieces."
  [data]
  (let [player-id (:player-id data)]
    (swap! state/icehoused-players conj player-id)
    (reset! state/game-state (:game data))))

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
  "Handle error message from server. Displays message and optionally the rule explanation.
   Error data may contain :code (for programmatic handling), :message, and :rule."
  [data]
  (let [code (:code data)
        message (:message data)
        rule (:rule data)
        ;; Include rule explanation in display if available
        display-message (if rule
                          (str message "\n\n" rule)
                          message)]
    (js/console.log "Error from server:" message)
    (when code (js/console.log "Error code:" code))
    (when rule (js/console.log "Rule:" rule))
    (reset! state/error-message display-message)
    (js/setTimeout #(reset! state/error-message nil) error-message-timeout-ms)))

(defn- handle-validation-result
  "Handle validation result from server (response to validate-move).
   Logs the result for debugging/AI agents. Does not display to user."
  [data]
  (js/console.log "Validation result:" (clj->js data))
  (when-not (:valid data)
    (let [error (:error data)]
      (js/console.log "Move invalid - Code:" (:code error)
                      "Message:" (:message error)
                      "Rule:" (:rule error)))))

(defn- handle-legal-moves
  "Handle legal moves response from server (response to query-legal-moves).
   Logs the result for debugging/AI agents. Does not display to user."
  [data]
  (js/console.log "Legal moves:" (clj->js data))
  (when (:error data)
    (let [error (:error data)]
      (js/console.log "Legal moves query failed - Code:" (:code error)
                      "Message:" (:message error)))))

(def ^:private message-handlers
  "Map of message types to handler functions"
  {msg/joined            handle-joined
   msg/players           handle-players
   msg/options           handle-options
   msg/game-start        handle-game-start
   msg/piece-placed      handle-piece-placed
   msg/piece-captured    handle-piece-captured
   msg/player-finished   handle-player-finished
   msg/player-icehoused  handle-player-icehoused
   msg/game-over         handle-game-over
   msg/game-list         handle-game-list
   msg/game-record       handle-game-record
   msg/error             handle-error
   msg/validation-result handle-validation-result
   msg/legal-moves       handle-legal-moves})

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

(defn send!
  "Send a message over the WebSocket. Blocks invalid messages from being sent."
  [msg]
  (when (validate-outgoing-message msg)
    (when-let [socket @ws]
      (when (= 1 (.-readyState socket))
        (.send socket (js/JSON.stringify (clj->js msg)))))))

(defn handle-message
  "Handle an incoming WebSocket message. Blocks invalid messages from being processed."
  [event]
  (try
    (let [raw-data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
      (when (validate-incoming-message raw-data)
        (let [msg-type (:type raw-data)]
          (if-let [handler (get message-handlers msg-type)]
            (handler raw-data)
            (if msg-type
              (js/console.log "Unknown message:" msg-type)
              (js/console.warn "Received message without type:" raw-data))))))
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

(defn set-name!
  "Send a set-name message to the server."
  [name]
  (send! {:type msg/set-name :name name}))

(defn set-colour!
  "Send a set-colour message to the server."
  [colour]
  (send! {:type msg/set-colour :colour colour}))

(defn toggle-ready!
  "Send a toggle-ready message to the server."
  []
  (send! {:type msg/ready}))

(defn place-piece!
  "Send a place-piece message to the server."
  [x y size orientation angle target-id captured?]
  (send! {:type msg/place-piece
          :x x
          :y y
          :size (name size)
          :orientation (name orientation)
          :angle angle
          :target-id target-id
          :captured captured?}))

(defn capture-piece!
  "Send a capture-piece message to the server to capture an over-iced attacker piece."
  [piece-id]
  (send! {:type msg/capture-piece
          :piece-id piece-id}))

(defn list-games!
  "Request a list of saved game records from the server."
  []
  (send! {:type msg/list-games}))

(defn load-game!
  "Send a load-game message to the server to retrieve a saved game record for replay."
  [game-id]
  (send! {:type msg/load-game :game-id game-id}))

(defn set-option!
  "Set a game option for the room on the server."
  [key value]
  (send! {:type msg/set-option :key (name key) :value value}))

(defn finish!
  "Signal to the server that the player wants to end the game."
  []
  (send! {:type msg/finish}))

(defn validate-move!
  "Validate a move without executing it. Server responds with validation-result message.
   For placement: (validate-move! {:action 'place' :x 100 :y 200 :size 'small' :orientation 'standing' :angle 0})
   For capture: (validate-move! {:action 'capture' :piece-id 'abc123'})"
  [params]
  (send! (merge {:type msg/validate-move} params)))

(defn query-legal-moves!
  "Query legal placement positions for a piece type. Server responds with legal-moves message.
   Options: :size ('small'|'medium'|'large'), :orientation ('standing'|'pointing'),
            :captured (true|false), :sample-step (grid spacing, default 50),
            :angle-step (angle increment for attacks, default 15)"
  [params]
  (send! (merge {:type msg/query-legal-moves} params)))
