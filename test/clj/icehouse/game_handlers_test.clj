(ns icehouse.game-handlers-test
  "Tests for game handler functions: nil room-id guards, end-game behaviour,
   and end-reason validation."
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.game-handlers :as gh]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.storage :as storage]
            [icehouse.game-rules :as rules]))

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

;; =============================================================================
;; End-reason validation tests
;; =============================================================================

(defn- make-test-game
  "Create a minimal valid game state for end-game testing."
  [overrides]
  (merge {:game-id "test-game-id"
          :room-id "test-room"
          :players {"player1" {:name "Alice"
                               :colour "#e53935"
                               :pieces {:small 5 :medium 5 :large 5}
                               :captured []}
                    "player2" {:name "Bob"
                               :colour "#fdd835"
                               :pieces {:small 5 :medium 5 :large 5}
                               :captured []}}
          :board []
          :moves []
          :options {}
          :started-at (- (System/currentTimeMillis) 60000)
          :ends-at nil}
         overrides))

(deftest end-game-explicit-end-reason-test
  (testing "end-game! with explicit end-reason passes it through to the game record"
    (let [saved-records (atom [])
          broadcast-msgs (atom [])
          game (make-test-game {:finished ["player1" "player2"]})
          games (atom {"test-room" game})]
      (with-redefs [storage/save-game-record! (fn [record] (swap! saved-records conj record))
                    utils/broadcast-room! (fn [_clients _room-id msg] (swap! broadcast-msgs conj msg))]
        (gh/end-game! games (atom {}) "test-room" :player-disconnected)
        ;; Verify game record was saved with correct end-reason
        (is (= 1 (count @saved-records)) "Should save exactly one game record")
        (is (= :player-disconnected (:end-reason (first @saved-records)))
            "Game record should have the explicit end-reason")
        ;; Verify game-over message was broadcast
        (let [game-over (first (filter #(= msg/game-over (:type %)) @broadcast-msgs))]
          (is (some? game-over) "Should broadcast game-over message")
          (is (= "test-game-id" (:game-id game-over)) "Should include game-id")
          (is (map? (:scores game-over)) "Should include scores map"))))))

(deftest end-game-auto-detect-end-reason-test
  (testing "end-game! without end-reason auto-detects from game state"
    (let [saved-records (atom [])
          game (make-test-game {:finished ["player1" "player2"]})
          games (atom {"test-room" game})]
      (with-redefs [storage/save-game-record! (fn [record] (swap! saved-records conj record))
                    utils/broadcast-room! (fn [_ _ _] nil)]
        (gh/end-game! games (atom {}) "test-room")
        (is (= 1 (count @saved-records)))
        (is (= :all-players-finished (:end-reason (first @saved-records)))
            "Should auto-detect :all-players-finished when all players are in finished list")))))

(deftest end-game-no-game-noop-test
  (testing "end-game! does nothing when no game exists for the room"
    (let [saved-records (atom [])
          games (atom {})]
      (with-redefs [storage/save-game-record! (fn [record] (swap! saved-records conj record))
                    utils/broadcast-room! (fn [_ _ _] nil)]
        (gh/end-game! games (atom {}) "nonexistent-room" :player-disconnected)
        (is (= 0 (count @saved-records)) "Should not save any record when no game exists")))))
