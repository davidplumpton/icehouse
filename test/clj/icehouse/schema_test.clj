(ns icehouse.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.schema :as schema]))

;; Test basic schemas

(deftest test-piece-size-schema
  (is (schema/validate-or-nil schema/piece-size :small))
  (is (schema/validate-or-nil schema/piece-size :medium))
  (is (schema/validate-or-nil schema/piece-size :large))
  (is (nil? (schema/validate-or-nil schema/piece-size :invalid))))

(deftest test-orientation-schema
  (is (schema/validate-or-nil schema/orientation :standing))
  (is (schema/validate-or-nil schema/orientation :pointing))
  (is (nil? (schema/validate-or-nil schema/orientation :invalid))))

(deftest test-piece-schema
  (let [valid-piece {:id "550e8400-e29b-41d4-a716-446655440000"
                     :player-id "550e8400-e29b-41d4-a716-446655440001"
                     :colour "#ff6b6b"
                     :x 100
                     :y 200
                     :size :small
                     :orientation :standing
                     :angle 0.5}]
    (is (schema/validate-or-nil schema/Piece valid-piece))))

(deftest test-piece-with-optional-target
  (let [attacking-piece {:id "550e8400-e29b-41d4-a716-446655440000"
                         :player-id "550e8400-e29b-41d4-a716-446655440001"
                         :colour "#ff6b6b"
                         :x 100
                         :y 200
                         :size :small
                         :orientation :pointing
                         :angle 1.5
                         :target-id "550e8400-e29b-41d4-a716-446655440002"}]
    (is (schema/validate-or-nil schema/Piece attacking-piece))))

(deftest test-captured-piece-schema
  (let [captured {:size :small :colour "#ff6b6b"}]
    (is (schema/validate-or-nil schema/CapturedPiece captured))))

(deftest test-player-schema
  (let [player {:name "Alice"
                :colour "#ff6b6b"
                :pieces {:small 5 :medium 5 :large 5}
                :captured []}]
    (is (schema/validate-or-nil schema/Player player))))

(deftest test-game-options-schema
  (is (schema/validate-or-nil schema/GameOptions {}))
  (is (schema/validate-or-nil schema/GameOptions {:icehouse-rule true}))
  (is (schema/validate-or-nil schema/GameOptions {:timer-enabled true :timer-duration :random}))
  (is (schema/validate-or-nil schema/GameOptions {:timer-duration 300000})))

(deftest test-game-state-schema
  (let [game-state {:game-id "550e8400-e29b-41d4-a716-446655440000"
                    :room-id "default"
                    :players {"550e8400-e29b-41d4-a716-446655440001"
                              {:name "Alice"
                               :colour "#ff6b6b"
                               :pieces {:small 5 :medium 5 :large 5}
                               :captured []}}
                    :board []
                    :moves []
                    :options {}
                    :started-at 1234567890
                    :ends-at 1234567950}]
    (is (schema/validate-or-nil schema/GameState game-state))))

(deftest test-selected-piece-schema
  (let [selected {:size :small :orientation :standing :captured? false}]
    (is (schema/validate-or-nil schema/SelectedPiece selected))))

(deftest test-ui-state-schema
  (let [ui-state {:selected-piece {:size :small :orientation :standing :captured? false}
                  :drag nil
                  :hover-pos nil
                  :zoom-active false
                  :show-help false
                  :move-mode false
                  :last-placement-time 0}]
    (is (schema/validate-or-nil schema/UIState ui-state))))

(deftest test-client-message-validation
  (testing "valid messages"
    (let [join-msg {:type "join"}
          set-name-msg {:type "set-name" :name "Alice"}
          place-piece-msg {:type "place-piece"
                           :x 500
                           :y 375
                           :size "small"
                           :orientation "standing"
                           :angle 0
                           :target-id nil
                           :captured false}]
      (is (schema/validate-or-nil schema/ClientMessage join-msg))
      (is (schema/validate-or-nil schema/ClientMessage set-name-msg))
      (is (schema/validate-or-nil schema/ClientMessage place-piece-msg))))

  (testing "invalid place-piece message"
    (let [invalid-msg {:type "place-piece"
                       :x 500
                       :y 375
                       :size "gigantic"  ;; Invalid size
                       :orientation "standing"
                       :angle 0
                       :captured false}]
      (is (nil? (schema/validate-or-nil schema/ClientMessage invalid-msg))))))

(deftest test-server-message-validation
  (let [joined-msg {:type "joined"
                    :player-id "550e8400-e29b-41d4-a716-446655440001"
                    :room-id "default"
                    :name "Alice"
                    :colour "#ff6b6b"}
        error-msg {:type "error" :message "Something went wrong"}]
    (is (schema/validate-or-nil schema/ServerMessage joined-msg))
    (is (schema/validate-or-nil schema/ServerMessage error-msg))))

(deftest test-invalid-piece-fails
  (let [invalid-piece {:id "550e8400-e29b-41d4-a716-446655440000"
                       :player-id "550e8400-e29b-41d4-a716-446655440001"
                       :colour "#ff6b6b"
                       :x 100
                       :y 200
                       :size :invalid  ;; Invalid size
                       :orientation :standing
                       :angle 0.5}]
    (is (nil? (schema/validate-or-nil schema/Piece invalid-piece)))))
