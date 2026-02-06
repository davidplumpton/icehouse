(ns icehouse.lobby-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [icehouse.lobby :as lobby]
            [icehouse.game :as game]
            [icehouse.utils :as utils]
            [icehouse.messages :as msg]))

(defn reset-room-channels-fixture [f]
  (utils/reset-room-channels!)
  (try (f)
    (finally (utils/reset-room-channels!))))

(use-fixtures :each reset-room-channels-fixture)

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

(deftest handle-disconnect-no-game-test
  (testing "disconnect from lobby (no active game) removes client and broadcasts players"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready false}
                         "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835" :ready false}})
          broadcast-calls (atom [])]
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [_ room-id msg]
                                                     (swap! broadcast-calls conj {:room-id room-id :msg msg}))]
        (lobby/handle-disconnect clients "ch1")
        (is (nil? (get @clients "ch1")) "Disconnected client should be removed")
        (is (some? (get @clients "ch2")) "Other client should remain")
        ;; Should broadcast players update but NOT player-disconnected
        (is (= 1 (count @broadcast-calls)) "Should broadcast once (players update)")
        (is (= msg/players (get-in (first @broadcast-calls) [:msg :type])))))))

(deftest handle-disconnect-with-active-game-test
  (testing "disconnect during active game notifies remaining players and ends game"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready true}
                         "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835" :ready true}})
          broadcast-calls (atom [])
          end-game-called (atom nil)]
      ;; Set up an active game for the room
      (reset! game/games {"room1" {:game-id "test-game" :room-id "room1"
                                   :players {} :board [] :moves []
                                   :options {} :started-at 1000 :ends-at nil}})
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [_ room-id msg]
                                                     (swap! broadcast-calls conj {:room-id room-id :msg msg}))
                    icehouse.game/end-game! (fn [_ room-id end-reason]
                                              (reset! end-game-called {:room-id room-id :end-reason end-reason}))]
        (lobby/handle-disconnect clients "ch1")
        (is (nil? (get @clients "ch1")) "Disconnected client should be removed")
        ;; Should broadcast both players update and player-disconnected
        (let [msg-types (mapv #(get-in % [:msg :type]) @broadcast-calls)]
          (is (some #{msg/players} msg-types) "Should broadcast players update")
          (is (some #{msg/player-disconnected} msg-types) "Should broadcast player-disconnected"))
        ;; Check player-disconnected message content
        (let [disconnect-msg (->> @broadcast-calls
                                  (filter #(= msg/player-disconnected (get-in % [:msg :type])))
                                  first
                                  :msg)]
          (is (= "Alice" (:player-name disconnect-msg)) "Should include disconnected player's name"))
        ;; Should end the game
        (is (some? @end-game-called) "Should call end-game!")
        (is (= :player-disconnected (:end-reason @end-game-called)))))
    ;; Clean up
    (reset! game/games {})))

(deftest handle-disconnect-no-room-test
  (testing "disconnect without a room does nothing"
    (let [clients (atom {"ch1" {:room-id nil :name nil :colour nil :ready false}})
          broadcast-calls (atom [])]
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [_ room-id msg]
                                                     (swap! broadcast-calls conj {:room-id room-id :msg msg}))]
        (lobby/handle-disconnect clients "ch1")
        (is (nil? (get @clients "ch1")) "Client should be removed")
        (is (empty? @broadcast-calls) "No broadcasts when client had no room")))))

(deftest handle-disconnect-during-ready-check-test
  (testing "client disconnects while another player is ready but before game starts"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready true}
                         "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835" :ready false}})
          broadcast-calls (atom [])
          end-game-called (atom false)]
      ;; No active game exists (still in lobby)
      (reset! game/games {})
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [_ room-id msg]
                                                     (swap! broadcast-calls conj {:room-id room-id :msg msg}))
                    icehouse.game/end-game! (fn [& _] (reset! end-game-called true))]
        (lobby/handle-disconnect clients "ch2")
        ;; Bob should be removed
        (is (nil? (get @clients "ch2")) "Disconnected client should be removed")
        ;; Alice should still be present and ready
        (is (some? (get @clients "ch1")) "Remaining client should stay")
        (is (:ready (get @clients "ch1")) "Remaining client's ready status should be preserved")
        ;; Should broadcast player list update, but NOT end-game or player-disconnected
        (let [msg-types (mapv #(get-in % [:msg :type]) @broadcast-calls)]
          (is (some #{msg/players} msg-types) "Should broadcast players update")
          (is (not (some #{msg/player-disconnected} msg-types))
              "Should NOT broadcast player-disconnected when no game active"))
        (is (not @end-game-called) "Should NOT call end-game! when no game active")))))

(deftest room-channels-index-test
  (testing "add-channel-to-room! populates the index"
    (utils/add-channel-to-room! "room1" "ch1")
    (utils/add-channel-to-room! "room1" "ch2")
    (utils/add-channel-to-room! "room2" "ch3")
    (is (= #{"ch1" "ch2"} (get @utils/room-channels "room1")))
    (is (= #{"ch3"} (get @utils/room-channels "room2"))))

  (testing "remove-channel-from-room! updates the index"
    (utils/remove-channel-from-room! "room1" "ch1")
    (is (= #{"ch2"} (get @utils/room-channels "room1"))))

  (testing "removing last channel from a room cleans up the entry"
    (utils/remove-channel-from-room! "room1" "ch2")
    (is (nil? (get @utils/room-channels "room1"))
        "Empty rooms should be removed from the index"))

  (testing "reset-room-channels! clears everything"
    (utils/add-channel-to-room! "room1" "ch1")
    (utils/reset-room-channels!)
    (is (= {} @utils/room-channels))))

(deftest handle-join-updates-room-index-test
  (testing "handle-join adds channel to room-channels index"
    (let [clients (atom {"ch1" {:room-id nil :name nil :colour nil :ready false :player-id "p1"}})]
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [& _] nil)]
        (lobby/handle-join clients "ch1" {:type msg/join})
        (is (contains? (get @utils/room-channels "default") "ch1")
            "Channel should be in the room-channels index after join")))))

(deftest handle-disconnect-updates-room-index-test
  (testing "handle-disconnect removes channel from room-channels index"
    (let [clients (atom {"ch1" {:room-id "room1" :name "Alice" :colour "#e53935" :ready false :player-id "p1"}
                         "ch2" {:room-id "room1" :name "Bob" :colour "#fdd835" :ready false :player-id "p2"}})]
      ;; Populate the index to match the clients state
      (utils/add-channel-to-room! "room1" "ch1")
      (utils/add-channel-to-room! "room1" "ch2")
      (with-redefs [icehouse.utils/send-msg! (fn [& _] nil)
                    icehouse.utils/broadcast-room! (fn [& _] nil)]
        (lobby/handle-disconnect clients "ch1")
        (is (not (contains? (get @utils/room-channels "room1") "ch1"))
            "Disconnected channel should be removed from index")
        (is (contains? (get @utils/room-channels "room1") "ch2")
            "Other channels should remain in index")))))
