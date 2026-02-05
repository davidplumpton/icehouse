(ns icehouse.game.query
  "Pre-flight move validation and legal move query handlers."
  (:require [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.game-state :as state]
            [icehouse.game.validators :as validators]
            [icehouse.game.mutations :as mutations]
            [icehouse.game.errors :refer [make-error]]
            [icehouse.constants :as const]))

(defn handle-validate-move
  "Validate a move without executing it. Returns validation result with detailed error info if invalid.
   Supports both placement and capture validation based on :action field.
   Request format for placement: {:type 'validate-move' :action 'place' :x :y :size :orientation :angle :captured}
   Request format for capture: {:type 'validate-move' :action 'capture' :piece-id '...'}"
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (utils/send-msg! channel {:type msg/validation-result
                                :valid false
                                :error (make-error msg/err-invalid-game
                                                   "Not in a room"
                                                   "You must be in a game room to validate moves.")})
      (let [player-id (state/player-id-from-channel channel)
            action (keyword (or (:action msg) "place"))]
        (if-not game
          (utils/send-msg! channel {:type msg/validation-result
                                    :valid false
                                    :error (make-error msg/err-invalid-game
                                                       "No active game"
                                                       "You must be in an active game to validate moves.")})
          (case action
            :place
            (let [using-captured? (boolean (:captured msg))
                  piece (mutations/construct-piece-for-placement game player-id msg)
                  error (validators/validate-placement game player-id piece using-captured?)]
              (utils/send-msg! channel {:type msg/validation-result
                                        :valid (nil? error)
                                        :action "place"
                                        :error error
                                        :piece-preview (when (nil? error)
                                                         {:id (:id piece)
                                                          :target-id (:target-id piece)})}))
            :capture
            (let [error (validators/validate-capture game player-id (:piece-id msg))]
              (utils/send-msg! channel {:type msg/validation-result
                                        :valid (nil? error)
                                        :action "capture"
                                        :error error}))
            (utils/send-msg! channel {:type msg/validation-result
                                      :valid false
                                      :error (make-error msg/err-invalid-message
                                                         (str "Unknown action: " action)
                                                         "Valid actions are 'place' or 'capture'.")})))))))

(defn generate-sample-positions
  "Generate sample positions across the play area for legal move checks.
   Returns a grid of positions spaced by step-size pixels."
  [step-size]
  (for [x (range 0 (inc const/play-area-width) step-size)
        y (range 0 (inc const/play-area-height) step-size)]
    [x y]))

(defn find-legal-placements
  "Find legal placement positions for a piece of given size and orientation.
   For standing pieces, samples a grid of positions.
   For attacking pieces, also varies the angle to find valid attack positions.
   Returns a list of valid placement specs."
  [game player-id size orientation using-captured? sample-step angle-step]
  (let [positions (generate-sample-positions sample-step)
        angles (if (= orientation :standing) [0] (range 0 360 angle-step))]
    (for [[x y] positions
          angle angles
          :let [piece {:x x :y y :size size :orientation orientation :angle angle}
                error (validators/validate-placement game player-id piece using-captured?)]
          :when (nil? error)]
      {:x x :y y :angle angle})))

(defn handle-query-legal-moves
  "Query legal move positions for a given piece type.
   Returns sample valid positions where the piece could be placed."
  [games clients channel msg]
  (let [{:keys [room-id game]} (utils/get-game-for-channel clients games channel)]
    (if-not room-id
      (utils/send-msg! channel {:type msg/legal-moves
                                :valid-positions []
                                :error (make-error msg/err-invalid-game
                                                   "Not in a room"
                                                   "You must be in a game room to query legal moves.")})
      (let [player-id (state/player-id-from-channel channel)
            size (keyword (or (:size msg) "small"))
            orientation (keyword (or (:orientation msg) "standing"))
            using-captured? (boolean (:captured msg))
            sample-step (or (:sample-step msg) 50)
            angle-step (or (:angle-step msg) 15)]
        (if-not game
          (utils/send-msg! channel {:type msg/legal-moves
                                    :valid-positions []
                                    :error (make-error msg/err-invalid-game
                                                       "No active game"
                                                       "You must be in an active game to query legal moves.")})
          (let [player (get-in game [:players player-id])
                remaining (if using-captured?
                            (utils/count-captured-by-size (:captured player) size)
                            (get-in player [:pieces size] 0))]
            (if (not (pos? remaining))
              (utils/send-msg! channel {:type msg/legal-moves
                                        :valid-positions []
                                        :error (if using-captured?
                                                 (make-error msg/err-no-captured
                                                             "No captured pieces of that size"
                                                             "You have no captured pieces of this size to place.")
                                                 (make-error msg/err-no-pieces
                                                             (str "No " (name size) " pieces remaining")
                                                             "You have placed all pieces of this size."))})
              (let [legal-positions (find-legal-placements game player-id size orientation
                                                           using-captured? sample-step angle-step)]
                (utils/send-msg! channel {:type msg/legal-moves
                                          :size (name size)
                                          :orientation (name orientation)
                                          :captured using-captured?
                                          :valid-positions (vec (take 100 legal-positions))
                                          :total-found (count legal-positions)
                                          :sample-step sample-step
                                          :angle-step angle-step})))))))))
