(ns icehouse.game-over-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [icehouse.integration-test :as it]
            [icehouse.messages :as msg]
            [icehouse.server :as app-server])
  (:import [java.net.http WebSocket]))

(use-fixtures :each it/with-server)

(deftest test-game-over-all-players-finished
  (testing "Game ends when all players send finish message"
    (let [c1 (it/connect-client)
          c2 (it/connect-client)]
      ;; Players join
      (it/send-msg! c1 {:type msg/join})
      (it/wait-for-msg c1 msg/joined 1000)
      (it/send-msg! c2 {:type msg/join})
      (it/wait-for-msg c2 msg/joined 1000)

      ;; Start game
      (it/send-msg! c1 {:type msg/ready})
      (it/wait-for-msg c1 msg/game-start 1000)
      (it/wait-for-msg c2 msg/game-start 1000)

      ;; C1 finishes
      (it/send-msg! c1 {:type msg/finish})
      (let [finished-msg (it/wait-for-msg c1 msg/player-finished 1000)]
        (is (some? finished-msg) "Should receive player-finished message"))

      ;; C2 finishes
      (it/send-msg! c2 {:type msg/finish})
      (let [game-over-msg (it/wait-for-msg c1 msg/game-over 2000)]
        (is (some? game-over-msg) "Should receive game-over message")
        (is (some? (:scores game-over-msg)) "Should contain scores")))))

(deftest test-game-over-player-disconnect
  (testing "Game ends with correct message when a player disconnects mid-game"
    (let [c1 (it/connect-client)
          c2 (it/connect-client)]
      ;; Players join
      (it/send-msg! c1 {:type msg/join})
      (it/wait-for-msg c1 msg/joined 1000)
      (it/send-msg! c2 {:type msg/join})
      (it/wait-for-msg c2 msg/joined 1000)

      ;; Start game
      (it/send-msg! c1 {:type msg/ready})
      (it/wait-for-msg c1 msg/game-start 2000)
      (it/wait-for-msg c2 msg/game-start 2000)

      ;; C2 disconnects mid-game
      (.sendClose (:ws c2) WebSocket/NORMAL_CLOSURE "")

      ;; C1 should receive player-disconnected notification
      (let [disconnect-msg (it/wait-for-msg c1 msg/player-disconnected 2000)]
        (is (some? disconnect-msg) "Should receive player-disconnected notification")
        (is (string? (:player-id disconnect-msg)) "Should include player-id")
        (is (string? (:player-name disconnect-msg)) "Should include player-name"))

      ;; C1 should then receive game-over with scores
      (let [game-over-msg (it/wait-for-msg c1 msg/game-over 2000)]
        (is (some? game-over-msg) "Should receive game-over message after disconnect")
        (is (some? (:scores game-over-msg)) "Game-over should contain scores")
        (is (string? (:game-id game-over-msg)) "Game-over should contain game-id")))))

(deftest test-game-over-message-structure
  (testing "Game-over message contains all required fields with correct types"
    (let [c1 (it/connect-client)]
      ;; Single player game
      (it/send-msg! c1 {:type msg/join})
      (it/wait-for-msg c1 msg/joined 1000)
      (it/send-msg! c1 {:type msg/ready})
      (it/wait-for-msg c1 msg/game-start 2000)

      ;; Finish immediately
      (it/send-msg! c1 {:type msg/finish})
      (it/wait-for-msg c1 msg/player-finished 1000)

      (let [game-over-msg (it/wait-for-msg c1 msg/game-over 2000)]
        (is (some? game-over-msg) "Should receive game-over")
        (is (= msg/game-over (:type game-over-msg)) "Type should be game-over")
        (is (string? (:game-id game-over-msg)) "game-id should be a string")
        (is (map? (:scores game-over-msg)) "scores should be a map")
        (is (vector? (:icehouse-players game-over-msg)) "icehouse-players should be a vector")
        (is (map? (:over-ice game-over-msg)) "over-ice should be a map")))))
