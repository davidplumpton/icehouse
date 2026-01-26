(ns icehouse.game-over-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [icehouse.integration-test :as it]
            [icehouse.messages :as msg]
            [icehouse.server :as app-server]))

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
