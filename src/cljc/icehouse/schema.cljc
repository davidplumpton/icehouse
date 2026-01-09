(ns icehouse.schema
  (:require [malli.core :as m]))

;; =============================================================================
;; Primitive Schemas
;; =============================================================================

(def id-string
  "Unique identifier as a string (or keyword in CLJS map keys)"
  [:or :string :keyword :nil])

(def colour
  "Hex colour string (e.g., #ff6b6b)"
  [:re #"^#[0-9a-fA-F]{6}$"])

(def piece-size
  "Pyramid size classification"
  [:enum :small "small" :medium "medium" :large "large"])

(def orientation
  "Piece orientation on board"
  [:enum :standing "standing" :pointing "pointing"])

(def PieceIDSet
  "Set of piece IDs"
  [:set id-string])

;; =============================================================================
;; Piece Schemas
;; =============================================================================

(def Piece
  "A pyramid piece on the board"
  [:map
   [:id id-string]
   [:player-id id-string]
   [:colour colour]
   [:x :int]
   [:y :int]
   [:size piece-size]
   [:orientation orientation]
   [:angle [:or :double :int]]
   [:target-id {:optional true} [:or id-string :nil]]])

(def CapturedPiece
  "A captured piece (in a player's captured list)"
  [:map
   [:size piece-size]
   [:colour colour]])

;; =============================================================================
;; Player Schemas
;; =============================================================================

(def PieceCounts
  "Remaining pieces for a player"
  [:map
   [:small [:int {:min 0 :max 5}]]
   [:medium [:int {:min 0 :max 5}]]
   [:large [:int {:min 0 :max 5}]]])

(def Player
  "Player state within a game"
  [:map
   [:name :string]
   [:colour colour]
   [:pieces PieceCounts]
   [:captured [:vector CapturedPiece]]])

(def PlayerMap
  "Map of player-id to Player"
  [:map-of id-string Player])

(def PlayerSummary
  "Minimal player info for lobby display"
  [:map
   [:id id-string]
   [:name :string]
   [:colour colour]
   [:ready {:optional true} :boolean]])

(def PlayerList
  "List of players in a room"
  [:sequential PlayerSummary])

;; =============================================================================
;; Game Options Schemas
;; =============================================================================

(def GameOptions
  "Game configuration options"
  [:map
   [:icehouse-rule {:optional true} :boolean]
   [:timer-enabled {:optional true} :boolean]
   [:timer-duration {:optional true} [:or
                                      [:enum :random "random"]
                                      [:int {:min 1000}]]]
   [:placement-throttle {:optional true} [:or :double :int]]])

;; =============================================================================
;; Score Schemas
;; =============================================================================

(def Scores
  "Map of player-id to numeric score"
  [:map-of id-string :int])

;; =============================================================================
;; Move Schemas
;; =============================================================================

(def Move
  "A recorded move with metadata"
  [:map {:closed false}
   [:type [:enum :place-piece "place-piece" :capture-piece "capture-piece"]]
   [:player-id id-string]
   [:timestamp :int]
   [:elapsed-ms :int]])

;; =============================================================================
;; Game State Schemas
;; =============================================================================

(def GameState
  "Complete game state"
  [:map
   [:game-id id-string]
   [:room-id :string]
   [:players PlayerMap]
   [:board [:vector Piece]]
   [:moves [:vector Move]]
   [:options GameOptions]
   [:started-at :int]
   [:ends-at [:or :int :nil]]
   [:finished {:optional true} [:sequential id-string]]])

;; =============================================================================
;; WebSocket Message Schemas
;; =============================================================================

;; Client -> Server

(def JoinMessage
  "Client join message"
  [:map
   [:type [:enum "join"]]])

(def SetNameMessage
  "Client set name message"
  [:map
   [:type [:enum "set-name"]]
   [:name :string]])

(def SetColourMessage
  "Client set colour message"
  [:map
   [:type [:enum "set-colour"]]
   [:colour colour]])

(def SetOptionMessage
  "Client set game option message"
  [:map
   [:type [:enum "set-option"]]
   [:key :string]
   [:value :any]])

(def ReadyMessage
  "Client ready message"
  [:map
   [:type [:enum "ready"]]])

(def PlacePieceMessage
  "Client place piece message"
  [:map
   [:type [:enum "place-piece"]]
   [:x :int]
   [:y :int]
   [:size [:enum "small" "medium" "large"]]
   [:orientation [:enum "standing" "pointing"]]
   [:angle [:or :double :int]]
   [:target-id {:optional true} [:or id-string :nil]]
   [:captured :boolean]])

(def CapturePieceMessage
  "Client capture piece message"
  [:map
   [:type [:enum "capture-piece"]]
   [:piece-id id-string]])

(def ListGamesMessage
  "Client list games message"
  [:map
   [:type [:enum "list-games"]]])

(def LoadGameMessage
  "Client load game message"
  [:map
   [:type [:enum "load-game"]]
   [:game-id id-string]])

(def FinishMessage
  "Client finish/end game message"
  [:map
   [:type [:enum "finish"]]])

(def ClientMessage
  "Union of all client message types"
  [:or
   JoinMessage
   SetNameMessage
   SetColourMessage
   SetOptionMessage
   ReadyMessage
   PlacePieceMessage
   CapturePieceMessage
   FinishMessage
   ListGamesMessage
   LoadGameMessage])

;; Server -> Client

(def JoinedMessage
  "Server joined message"
  [:map
   [:type [:enum "joined"]]
   [:player-id id-string]
   [:room-id :string]
   [:name :string]
   [:colour colour]])

(def PlayersMessage
  "Server players message"
  [:map
   [:type [:enum "players"]]
   [:players PlayerList]])

(def OptionsMessage
  "Server options message"
  [:map
   [:type [:enum "options"]]
   [:options GameOptions]])

(def GameStartMessage
  "Server game start message"
  [:map
   [:type [:enum "game-start"]]
   [:game GameState]])

(def PiecePlacedMessage
  "Server piece placed message"
  [:map
   [:type [:enum "piece-placed"]]
   [:game GameState]])

(def PieceCapturedMessage
  "Server piece captured message"
  [:map
   [:type [:enum "piece-captured"]]
   [:game GameState]])

(def PlayerFinishedMessage
  "Server player finished message"
  [:map
   [:type [:enum "player-finished"]]
   [:player-id id-string]
   [:game GameState]])

(def GameOverMessage
  "Server game over message"
  [:map
   [:type [:enum "game-over"]]
   [:scores Scores]
   [:icehouse-players [:vector id-string]]
   [:over-ice [:map-of id-string :any]]])

(def GameListMessage
  "Server game list message"
  [:map
   [:type [:enum "game-list"]]
   [:games [:vector id-string]]])

(def GameRecord
  "A persistent record of a completed game"
  [:map
   [:version :int]
   [:game-id id-string]
   [:room-id :string]
   [:players [:map-of id-string [:map [:name :string] [:colour colour]]]]
   [:started-at :int]
   [:ended-at :int]
   [:duration-ms :int]
   [:end-reason [:enum :all-pieces-placed :time-up :all-players-finished]]
   [:moves [:vector Move]]
   [:final-board [:vector Piece]]
   [:final-scores Scores]
   [:icehouse-players [:vector id-string]]
   [:winner {:optional true} [:or id-string :nil]]])

(def GameRecordMessage
  "Server game record message"
  [:map
   [:type [:enum "game-record"]]
   [:record [:or GameRecord :nil]]])

(def ErrorMessage
  "Server error message"
  [:map
   [:type [:enum "error"]]
   [:message :string]])

(def ServerMessage
  "Union of all server message types"
  [:or
   JoinedMessage
   PlayersMessage
   OptionsMessage
   GameStartMessage
   PiecePlacedMessage
   PieceCapturedMessage
   PlayerFinishedMessage
   GameOverMessage
   GameListMessage
   GameRecordMessage
   ErrorMessage])

;; =============================================================================
;; UI State Schemas
;; =============================================================================

(def SelectedPiece
  "Configuration for a piece to be placed"
  [:map
   [:size piece-size]
   [:orientation orientation]
   [:captured? :boolean]])

(def DragState
  "State for dragging a piece during placement"
  [:map
   [:start-x [:or :double :int]]
   [:start-y [:or :double :int]]
   [:current-x [:or :double :int]]
   [:current-y [:or :double :int]]
   [:last-x {:optional true} [:or :double :int :nil]]
   [:last-y {:optional true} [:or :double :int :nil]]
   [:locked-angle {:optional true} [:or :double :int :nil]]])

(def HoverPos
  "Current mouse position on canvas"
  [:map
   [:x [:or :double :int]]
   [:y [:or :double :int]]])

(def ThrottleWarning
  "Warning displayed when player tries to move too quickly"
  [:map
   [:remaining-ms [:or :int :double]]
   [:show-until [:or :int :double]]])

(def UIState
  "Frontend UI interaction state"
  [:map
   [:selected-piece SelectedPiece]
   [:drag [:or DragState :nil]]
   [:hover-pos [:or HoverPos :nil]]
   [:zoom-active :boolean]
   [:show-help :boolean]
   [:move-mode :boolean]
   [:last-placement-time [:or :int :double]]
   [:throttle-warning {:optional true} [:or ThrottleWarning :nil]]])

;; =============================================================================
;; Replay State Schemas
;; =============================================================================

(def ReplayState
  "Game replay state"
  [:map
   [:record [:or GameRecord :nil]]
   [:current-move :int]
   [:playing? :boolean]
   [:speed [:or :double :int {:min 0.1}]]])

;; =============================================================================
;; Game Validation Result Schemas
;; =============================================================================

(def ValidationResult
  "Result of a game action validation"
  [:map
   [:valid? :boolean]
   [:error-message [:or :string :nil]]])


;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate
  "Validate data against a schema, returning the data if valid"
  [schema data]
  (if (m/validate schema data)
    data
    (throw (ex-info "Schema validation failed" 
                    {:errors (m/explain schema data)
                     :schema schema
                     :data data}))))

(defn validate-or-nil
  "Validate data against a schema, returning nil if invalid"
  [schema data]
  (when (m/validate schema data)
    data))

(defn explain
  "Get detailed error information for failed validation"
  [schema data]
  (m/explain schema data))