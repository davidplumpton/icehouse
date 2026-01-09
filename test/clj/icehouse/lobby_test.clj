(ns icehouse.lobby-test
  (:require [clojure.test :refer [deftest is testing]]
            [icehouse.lobby :as lobby]
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

(deftest default-options-test
  (testing "default placement throttle is 2.0 seconds"
    (is (= 2.0 (:placement-throttle lobby/default-options)))
    (is (= 2.0 (:placement-throttle (lobby/get-room-options "any-room"))))))
