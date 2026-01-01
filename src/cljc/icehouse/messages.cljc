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
