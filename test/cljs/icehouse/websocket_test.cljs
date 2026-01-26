(ns icehouse.websocket-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.websocket :as ws]))

(deftest validate-outgoing-message-test
  (testing "returns data for valid client messages"
    (is (some? (ws/validate-outgoing-message {:type "join"})))
    (is (some? (ws/validate-outgoing-message {:type "set-name" :name "Alice"})))
    (is (some? (ws/validate-outgoing-message {:type "ready"})))
    (is (some? (ws/validate-outgoing-message {:type "finish"})))
    (is (some? (ws/validate-outgoing-message {:type "list-games"}))))

  (testing "returns nil for invalid client messages"
    (is (nil? (ws/validate-outgoing-message {})))
    (is (nil? (ws/validate-outgoing-message {:type "not-a-real-type"})))
    (is (nil? (ws/validate-outgoing-message {:type "set-name"})))
    (is (nil? (ws/validate-outgoing-message nil)))))

(deftest validate-incoming-message-test
  (testing "returns data for valid server messages"
    (is (some? (ws/validate-incoming-message
                {:type "joined" :player-id "p1" :room-id "r1"
                 :name "Alice" :colour "#ff6b6b"})))
    (is (some? (ws/validate-incoming-message
                {:type "error" :message "Something went wrong"}))))

  (testing "returns nil for invalid server messages"
    (is (nil? (ws/validate-incoming-message {})))
    (is (nil? (ws/validate-incoming-message {:type "not-a-real-type"})))
    (is (nil? (ws/validate-incoming-message {:type "joined"})))
    (is (nil? (ws/validate-incoming-message nil)))))
