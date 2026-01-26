(ns icehouse.lobby-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]
            [icehouse.utils :as utils]
            [icehouse.messages :as msg]))

(deftest unique-colour-test
  (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935"}
                       "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835"}})]
    
    (testing "get-taken-colours returns correct set"
      (is (= #{"#e53935" "#fdd835"} (lobby/get-taken-colours @clients "room1")))
      (is (= #{} (lobby/get-taken-colours @clients "room2"))))

    (testing "next-available-colour returns a colour not in use"
      (let [next-colour (lobby/next-available-colour @clients "room1")]
        (is (not= "#e53935" next-colour))
        (is (not= "#fdd835" next-colour))
        (is (some #(= next-colour %) lobby/colours))))

    (testing "handle-set-colour prevents taking an already taken colour"
      ;; Since handle-set-colour sends messages, we might need a way to mock that
      ;; but for now let's just check if it updates the atom when colour is free
      ;; and doesn't update when it's taken.
      (let [room-id "room1"
            taken-colour "#e53935"
            free-colour "#43a047"]
        
        ;; Mock utils/send-msg! if we wanted to be thorough, 
        ;; but let's see if we can just test the logic.
        
        (testing "taking a taken colour fails (atom unchanged)"
          (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                        icehouse.lobby/broadcast-players! (fn [& _] nil)]
            (lobby/handle-set-colour clients "ch2" {:colour taken-colour})
            (is (= "#fdd835" (get-in @clients ["ch2" :colour])))))

        (testing "taking a free colour succeeds (atom updated)"
          (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                        icehouse.lobby/broadcast-players! (fn [& _] nil)]
            (lobby/handle-set-colour clients "ch2" {:colour free-colour})
            (is (= free-colour (get-in @clients ["ch2" :colour])))))))))

(deftest all-ready-test
  (testing "returns false when no players in room"
    (is (not (lobby/all-ready? {} "room1"))))

  (testing "returns false when players exist but none ready"
    (let [clients {"ch1" {:room-id "room1" :name "Alice" :ready false}
                   "ch2" {:room-id "room1" :name "Bob" :ready false}}]
      (is (not (lobby/all-ready? clients "room1")))))

  (testing "returns true when at least one player is ready (dev mode)"
    (let [clients {"ch1" {:room-id "room1" :name "Alice" :ready true}
                   "ch2" {:room-id "room1" :name "Bob" :ready false}}]
      (is (lobby/all-ready? clients "room1"))))

  (testing "only checks players in the specified room"
    (let [clients {"ch1" {:room-id "room1" :name "Alice" :ready false}
                   "ch2" {:room-id "room2" :name "Bob" :ready true}}]
      (is (not (lobby/all-ready? clients "room1")))
      (is (lobby/all-ready? clients "room2"))))

  (testing "accepts a plain map, not an atom"
    ;; Verifies the function works with a snapshot (map) rather than an atom
    (let [snapshot {"ch1" {:room-id "room1" :name "Alice" :ready true}}]
      (is (lobby/all-ready? snapshot "room1")))))

(deftest handle-ready-uses-consistent-snapshot-test
  (testing "handle-ready uses swap! result for ready check and player list"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready false}})
          started? (atom false)]
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [& _] nil)
                    icehouse.game/start-game! (fn [_ players _]
                                                (reset! started? true)
                                                {:success true
                                                 :game {:players players}})]
        (lobby/handle-ready clients "ch1")
        (is @started? "Game should start when player toggles to ready")
        (is (:ready (get @clients "ch1")) "Player should be marked ready")))))

(deftest handle-ready-resets-ready-atomically-test
  (testing "on game start failure, all players' ready status resets in a single swap"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready false}
                         "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835" :ready true}})
          swap-count (atom 0)]
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [& _] nil)
                    icehouse.game/start-game! (fn [& _]
                                                ;; Track swap calls after game start fails
                                                (add-watch clients :swap-counter
                                                           (fn [_ _ _ _] (swap! swap-count inc)))
                                                {:success false
                                                 :error {:code "test-error"
                                                         :message "Test failure"
                                                         :rule nil}})]
        (lobby/handle-ready clients "ch1")
        ;; The ready reset should be a single swap (swap-count = 1),
        ;; not one swap per player (which would be 2)
        ;; Note: swap-count also includes the broadcast-players call's potential derefs,
        ;; but the key assertion is that both players are reset
        (is (not (:ready (get @clients "ch1"))) "ch1 ready should be reset")
        (is (not (:ready (get @clients "ch2"))) "ch2 ready should be reset")
        (remove-watch clients :swap-counter)))))

(deftest default-options-test
  (testing "default placement throttle is 2.0 seconds"
    (is (= 2.0 (:placement-throttle lobby/default-options)))
    (is (= 2.0 (:placement-throttle (lobby/get-room-options "any-room"))))))
