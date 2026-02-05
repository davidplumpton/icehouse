(ns icehouse.websocket-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [icehouse.websocket :as ws]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.game :as game]))

(defn- sent-messages-for
  "Capture messages sent through utils/send-msg! while running body-fn."
  [body-fn]
  (let [sent (atom [])]
    (with-redefs [utils/send-msg! (fn [_channel message]
                                    (swap! sent conj message))]
      (body-fn)
      @sent)))

(deftest handle-message-invalid-json-test
  (testing "invalid JSON is handled explicitly with a client-facing error"
    (let [channel (Object.)
          sent (sent-messages-for
                 #(ws/handle-message channel "not json at all{{"))]
      (is (= 1 (count sent)))
      (is (= msg/error (:type (first sent))))
      (is (= "Invalid JSON" (:message (first sent)))))))

(deftest handle-message-exception-info-test
  (testing "expected ExceptionInfo from handlers is converted to an internal error message"
    (let [channel (Object.)
          payload {:type msg/place-piece
                   :x 100
                   :y 100
                   :size "small"
                   :orientation "standing"
                   :angle 0
                   :captured false}
          sent (atom [])]
      (with-redefs [game/handle-place-piece (fn [& _]
                                              (throw (ex-info "expected failure" {:reason :test})))
                    utils/send-msg! (fn [_channel message]
                                      (swap! sent conj message))]
        (ws/handle-message channel (json/generate-string payload)))
      (is (= 1 (count @sent)))
      (is (= msg/error (:type (first @sent))))
      (is (= "Internal server error" (:message (first @sent)))))))

(deftest handle-message-unexpected-runtime-exception-propagates-test
  (testing "unexpected runtime exceptions propagate for visibility"
    (let [channel (Object.)
          payload {:type msg/place-piece
                   :x 100
                   :y 100
                   :size "small"
                   :orientation "standing"
                   :angle 0
                   :captured false}]
      (with-redefs [game/handle-place-piece (fn [& _]
                                              (throw (RuntimeException. "unexpected failure")))]
        (is (thrown-with-msg? RuntimeException #"unexpected failure"
                              (ws/handle-message channel (json/generate-string payload))))))))
