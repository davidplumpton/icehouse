(ns icehouse.game-handlers-test
  "Tests for nil room-id guards in game handler functions.
   Verifies that handlers send appropriate errors when a client
   has no room-id (e.g., after disconnect)."
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.game-handlers :as gh]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]))

(defn- make-mock-channel
  "Create a mock channel object for testing. Uses a unique object so it can
   serve as a key in the clients map."
  []
  (Object.))

(defn- capture-sends
  "Run body-fn with utils/send-msg! and utils/broadcast-room! mocked.
   Returns a vector of all messages that were sent via send-msg!."
  [body-fn]
  (let [sent (atom [])]
    (with-redefs [utils/send-msg! (fn [_ch msg] (swap! sent conj msg))
                  utils/broadcast-room! (fn [_clients _room-id msg] (swap! sent conj msg))]
      (body-fn))
    @sent))

;; =============================================================================
;; Nil room-id guard tests
;; =============================================================================

(deftest handle-place-piece-nil-room-id-test
  (testing "sends error when client has no room-id"
    (let [channel (make-mock-channel)
          clients (atom {channel {:name "Alice"}})  ;; no :room-id
          games (atom {})
          msgs (capture-sends
                 #(gh/handle-place-piece games clients channel
                    {:size "small" :orientation "standing" :x 100 :y 100 :angle 0}))]
      (is (= 1 (count msgs)))
      (is (= msg/error (:type (first msgs))))
      (is (= msg/err-invalid-game (:code (first msgs)))))))

(deftest handle-capture-piece-nil-room-id-test
  (testing "sends error when client has no room-id"
    (let [channel (make-mock-channel)
          clients (atom {channel {:name "Alice"}})
          games (atom {})
          msgs (capture-sends
                 #(gh/handle-capture-piece games clients channel
                    {:piece-id "some-piece"}))]
      (is (= 1 (count msgs)))
      (is (= msg/error (:type (first msgs))))
      (is (= msg/err-invalid-game (:code (first msgs)))))))

(deftest handle-finish-nil-room-id-test
  (testing "sends error when client has no room-id"
    (let [channel (make-mock-channel)
          clients (atom {channel {:name "Alice"}})
          games (atom {})
          msgs (capture-sends
                 #(gh/handle-finish games clients channel {}))]
      (is (= 1 (count msgs)))
      (is (= msg/error (:type (first msgs))))
      (is (= msg/err-invalid-game (:code (first msgs)))))))

(deftest handle-validate-move-nil-room-id-test
  (testing "sends validation-result error when client has no room-id"
    (let [channel (make-mock-channel)
          clients (atom {channel {:name "Alice"}})
          games (atom {})
          msgs (capture-sends
                 #(gh/handle-validate-move games clients channel
                    {:action "place" :size "small" :orientation "standing"
                     :x 100 :y 100 :angle 0}))]
      (is (= 1 (count msgs)))
      (is (= msg/validation-result (:type (first msgs))))
      (is (false? (:valid (first msgs))))
      (is (= msg/err-invalid-game (get-in (first msgs) [:error :code]))))))

(deftest handle-query-legal-moves-nil-room-id-test
  (testing "sends legal-moves error when client has no room-id"
    (let [channel (make-mock-channel)
          clients (atom {channel {:name "Alice"}})
          games (atom {})
          msgs (capture-sends
                 #(gh/handle-query-legal-moves games clients channel
                    {:size "small" :orientation "standing"}))]
      (is (= 1 (count msgs)))
      (is (= msg/legal-moves (:type (first msgs))))
       (is (= [] (:valid-positions (first msgs))))
       (is (= msg/err-invalid-game (get-in (first msgs) [:error :code]))))))

;; =============================================================================
;; End-game consolidation tests
;; =============================================================================

(deftest end-game-consolidation-test
  (testing "post-placement does not end game when all players finished"
    (let [end-game-calls (atom [])
          games (atom {"test-room" {:game-id "game1"
                                   :players {"player1" {:name "Alice"}
                                            "player2" {:name "Bob"}}
                                   :finished ["player1" "player2"]
                                   :board {}
                                   :options {}
                                   :started-at (System/currentTimeMillis)
                                   :moves []}})]
      ;; Mock end-game! to capture calls and prevent broadcasting
      (with-redefs [gh/end-game! (fn [games-clients clients-arg room-id-arg end-reason-arg]
                                   (swap! end-game-calls conj {:room-id room-id-arg :end-reason end-reason-arg}))
                    utils/broadcast-room! (fn [_ _ _] nil)]
        ;; Simulate post-placement after all players finished
        (gh/handle-post-placement! games (atom {}) "test-room" "player1" 
                                 {:size "small" :x 100 :y 100} false #{})
        ;; Should NOT call end-game! since all-players-finished? should be handled by handle-finish
        (is (= 0 (count @end-game-calls)) "Should not call end-game! when all players finished in post-placement")))))
