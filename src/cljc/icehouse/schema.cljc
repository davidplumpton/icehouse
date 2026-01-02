(ns icehouse.schema
  (:require [malli.core :as m]))

;; =============================================================================
;; Primitive Schemas
;; =============================================================================

(def uuid-string
  "UUID as a string"
  :string)

(def colour
  "Hex colour string (e.g., #ff6b6b)"
  :string)

(def piece-size
  "Pyramid size classification"
  [:enum :small :medium :large])

(def orientation
  "Piece orientation on board"
  [:enum :standing :pointing])

;; =============================================================================
;; Piece Schemas
;; =============================================================================

(def Piece
  "A pyramid piece on the board"
  [:map
   [:id uuid-string]
   [:player-id uuid-string]
   [:colour colour]
   [:x :int]
   [:y :int]
   [:size piece-size]
   [:orientation orientation]
   [:angle [:or :double :int]]
   [:target-id {:optional true} [:or uuid-string :nil]]])

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
  [:map-of uuid-string Player])

;; =============================================================================
;; Game Options Schemas
;; =============================================================================

(def GameOptions
  "Game configuration options"
  [:map
   [:icehouse-rule {:optional true} :boolean]
   [:timer-enabled {:optional true} :boolean]
   [:timer-duration {:optional true} [:or
                                      [:enum :random]
                                      [:int {:min 1000}]]]])

;; =============================================================================
;; Move Schemas
;; =============================================================================

(def Move
  "A recorded move with metadata"
  [:map
   [:type [:enum :place-piece :capture-piece]]
   [:player-id uuid-string]
   [:timestamp :int]
   [:elapsed-ms :int]
   [:data [:map]]])  ;; Flexible data based on move type

;; =============================================================================
;; Game State Schemas
;; =============================================================================

(def GameState
  "Complete game state"
  [:map
   [:game-id uuid-string]
   [:room-id :string]
   [:players PlayerMap]
   [:board [:vector Piece]]
   [:moves [:vector Move]]
   [:options GameOptions]
   [:started-at :int]
   [:ends-at [:or :int :nil]]])

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
   [:size piece-size]
   [:orientation orientation]
   [:angle [:or :double :int]]
   [:target-id [:or uuid-string :nil]]
   [:captured :boolean]])

(def CapturePieceMessage
  "Client capture piece message"
  [:map
   [:type [:enum "capture-piece"]]
   [:piece-id uuid-string]])

(def ListGamesMessage
  "Client list games message"
  [:map
   [:type [:enum "list-games"]]])

(def LoadGameMessage
  "Client load game message"
  [:map
   [:type [:enum "load-game"]]
   [:game-id uuid-string]])

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
   ListGamesMessage
   LoadGameMessage])

;; Server -> Client

(def JoinedMessage
  "Server joined message"
  [:map
   [:type [:enum "joined"]]
   [:player-id uuid-string]
   [:room-id :string]
   [:name :string]
   [:colour colour]])

(def PlayersMessage
  "Server players message"
  [:map
   [:type [:enum "players"]]
   [:players [:vector [:map
                       [:id uuid-string]
                       [:name :string]
                       [:colour colour]
                       [:ready {:optional true} :boolean]]]]])

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
   [:player-id uuid-string]
   [:game GameState]])

(def GameOverMessage
  "Server game over message"
  [:map
   [:type [:enum "game-over"]]
   [:scores [:map-of uuid-string :int]]
   [:icehouse-players [:vector uuid-string]]
   [:over-ice [:map-of uuid-string :any]]])

(def GameListMessage
  "Server game list message"
  [:map
   [:type [:enum "game-list"]]
   [:games [:vector uuid-string]]])

(def GameRecordMessage
  "Server game record message"
  [:map
   [:type [:enum "game-record"]]
   [:record [:or GameState :nil]]])

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
   [:start-x :int]
   [:start-y :int]
   [:current-x :int]
   [:current-y :int]
   [:last-x {:optional true} [:or :int :nil]]
   [:last-y {:optional true} [:or :int :nil]]
   [:locked-angle {:optional true} [:or :double :nil]]])

(def HoverPos
  "Current mouse position on canvas"
  [:map
   [:x :int]
   [:y :int]])

(def UIState
  "Frontend UI interaction state"
  [:map
   [:selected-piece SelectedPiece]
   [:drag [:or DragState :nil]]
   [:hover-pos [:or HoverPos :nil]]
   [:zoom-active :boolean]
   [:show-help :boolean]])

;; =============================================================================
;; Replay State Schemas
;; =============================================================================

(def ReplayState
  "Game replay state"
  [:map
   [:record [:or GameState :nil]]
   [:current-move :int]
   [:playing? :boolean]
   [:speed [:or :double :int {:min 0.1}]]])

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
