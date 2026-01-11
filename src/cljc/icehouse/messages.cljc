(ns icehouse.messages
  "Shared message type constants for WebSocket communication.
   Used by both Clojure backend and ClojureScript frontend.")

;; =============================================================================
;; Client -> Server Messages
;; =============================================================================

;; Lobby
(def join "join")
(def set-name "set-name")
(def set-colour "set-colour")
(def set-option "set-option")
(def ready "ready")

;; Game
(def place-piece "place-piece")
(def capture-piece "capture-piece")
(def finish "finish")
(def validate-move "validate-move")
(def query-legal-moves "query-legal-moves")

;; Replay
(def list-games "list-games")
(def load-game "load-game")

;; =============================================================================
;; Server -> Client Messages
;; =============================================================================

;; Lobby
(def joined "joined")
(def players "players")
(def options "options")

;; Game
(def game-start "game-start")
(def piece-placed "piece-placed")
(def piece-captured "piece-captured")
(def player-finished "player-finished")
(def game-over "game-over")

;; Replay
(def game-list "game-list")
(def game-record "game-record")

;; Error
(def error "error")

;; Validation responses
(def validation-result "validation-result")
(def legal-moves "legal-moves")

;; =============================================================================
;; Error Codes (for programmatic handling)
;; =============================================================================

;; Placement errors
(def err-no-pieces "NO_PIECES_REMAINING")
(def err-no-captured "NO_CAPTURED_REMAINING")
(def err-out-of-bounds "OUT_OF_BOUNDS")
(def err-overlap "PIECE_OVERLAP")
(def err-no-target "NO_ATTACK_TARGET")
(def err-out-of-range "TARGET_OUT_OF_RANGE")
(def err-blocked "LINE_OF_SIGHT_BLOCKED")

;; Capture errors
(def err-piece-not-found "PIECE_NOT_FOUND")
(def err-not-attacking "NOT_ATTACKING_PIECE")
(def err-no-target-assigned "NO_TARGET_ASSIGNED")
(def err-not-over-iced "NOT_OVER_ICED")
(def err-not-your-defender "NOT_YOUR_DEFENDER")
(def err-exceeds-excess "EXCEEDS_CAPTURE_LIMIT")

;; General errors
(def err-invalid-game "INVALID_GAME_STATE")
(def err-invalid-message "INVALID_MESSAGE")
(def err-internal-state "INTERNAL_STATE_ERROR")
