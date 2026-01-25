(ns icehouse.game
  "Public API facade for the game module.
   This namespace owns the shared games atom and re-exports all public functions
   from the split modules for backwards compatibility.

   Consumers should require this namespace rather than the internal modules."
  (:require [icehouse.game-state :as state]
            [icehouse.game-targeting :as targeting]
            [icehouse.game-rules :as rules]
            [icehouse.game-handlers :as handlers]))

;; =============================================================================
;; Shared State
;; =============================================================================

(defonce games (atom {}))

;; =============================================================================
;; Re-exported Constants (from game-state)
;; =============================================================================

(def play-area-width state/play-area-width)
(def play-area-height state/play-area-height)
(def icehouse-min-pieces state/icehouse-min-pieces)
(def initial-piece-counts state/initial-piece-counts)
(def game-duration-min-ms state/game-duration-min-ms)
(def game-duration-max-ms state/game-duration-max-ms)

;; =============================================================================
;; Re-exported Functions from game-state
;; =============================================================================

(def player-id-from-channel state/player-id-from-channel)
(def create-game state/create-game)
(def validate-game-state state/validate-game-state)

(defn record-move!
  "Append a move to the game's move history"
  [room-id move]
  (state/record-move! games room-id move))

;; =============================================================================
;; Re-exported Functions from game-targeting
;; =============================================================================

(def intersects-any-piece? targeting/intersects-any-piece?)
(def within-play-area? targeting/within-play-area?)
(def potential-target? targeting/potential-target?)
(def valid-target? targeting/valid-target?)
(def find-potential-targets targeting/find-potential-targets)
(def has-potential-target? targeting/has-potential-target?)
(def find-valid-targets targeting/find-valid-targets)
(def has-valid-target? targeting/has-valid-target?)
(def find-targets-in-range targeting/find-targets-in-range)
(def has-target-in-range? targeting/has-target-in-range?)
(def has-blocked-target? targeting/has-blocked-target?)
(def find-closest-target targeting/find-closest-target)
(def refresh-all-targets targeting/refresh-all-targets)

;; =============================================================================
;; Re-exported Functions from game-rules
;; =============================================================================

(def capturable-attackers rules/capturable-attackers)
(def pieces-placed-by-player rules/pieces-placed-by-player)
(def player-defenders rules/player-defenders)
(def in-icehouse? rules/in-icehouse?)
(def calculate-icehouse-players rules/calculate-icehouse-players)
(def successful-attackers rules/successful-attackers)
(def calculate-scores rules/calculate-scores)
(def player-pieces-placed? rules/player-pieces-placed?)
(def all-pieces-placed? rules/all-pieces-placed?)
(def all-active-players-finished? rules/all-active-players-finished?)
(def time-up? rules/time-up?)
(def any-player-in-icehouse? rules/any-player-in-icehouse?)
(def game-over? rules/game-over?)
(def all-players-finished? rules/all-players-finished?)
(def build-game-record rules/build-game-record)

;; =============================================================================
;; Re-exported Functions from game-handlers
;; =============================================================================

(def make-error handlers/make-error)
(def validate-placement handlers/validate-placement)
(def valid-placement? handlers/valid-placement?)
(def validate-capture handlers/validate-capture)
(def construct-piece-for-placement handlers/construct-piece-for-placement)
(def generate-sample-positions handlers/generate-sample-positions)
(def find-legal-placements handlers/find-legal-placements)
(def handle-list-games handlers/handle-list-games)
(def handle-load-game handlers/handle-load-game)

;; =============================================================================
;; Wrapper Functions (bind to the games atom)
;; =============================================================================

(defn start-game!
  "Start a new game with the given players and options.
   Returns {:success true :game <game-state>} on success,
   or {:success false :error <structured-error>} on failure."
  ([room-id players]
   (handlers/start-game! games room-id players))
  ([room-id players options]
   (handlers/start-game! games room-id players options)))

(defn handle-place-piece
  "Handle a place-piece message from a client"
  [clients channel msg]
  (handlers/handle-place-piece games clients channel msg))

(defn handle-capture-piece
  "Handle a capture-piece message from a client"
  [clients channel msg]
  (handlers/handle-capture-piece games clients channel msg))

(defn handle-finish
  "Handle a player pressing the finish button"
  [clients channel msg]
  (handlers/handle-finish games clients channel msg))

(defn handle-validate-move
  "Validate a move without executing it."
  [clients channel msg]
  (handlers/handle-validate-move games clients channel msg))

(defn handle-query-legal-moves
  "Query legal move positions for a given piece type."
  [clients channel msg]
  (handlers/handle-query-legal-moves games clients channel msg))
