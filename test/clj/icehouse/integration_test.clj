(ns icehouse.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as server]
            [cheshire.core :as json]
            [icehouse.server :as app-server]
            [icehouse.messages :as msg])
  (:import [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.net URI]
           [java.util.concurrent CompletionStage]))

(def ^:dynamic *test-port* 3001)

(defn with-server [f]
  (app-server/reset-all!)
  (app-server/start! *test-port*)
  (try
    (f)
    (finally
      (app-server/stop!))))

(use-fixtures :each with-server)

(defn connect-client []
  (let [messages (atom [])
        buffer (StringBuilder.)
        promise (promise)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (.request ws 1)
                     nil)
                   (onText [_ ws data last?]
                     (.append buffer data)
                     (when last?
                       (let [full-data (.toString buffer)]
                         (.setLength buffer 0)
                         (try
                           (let [msg (json/parse-string full-data true)]
                             (swap! messages conj msg)
                             (when-not (realized? promise)
                               (deliver promise msg)))
                           (catch Exception e
                             (println "Error parsing message:" e)
                             (println "Full data was:" full-data)))))
                     (.request ws 1)
                     nil)
                   (onClose [_ ws status reason]
                     nil)
                   (onError [_ ws error]
                     (println "WebSocket error:" error)
                     nil))
        client (HttpClient/newHttpClient)
        ws-future (.buildAsync (.newWebSocketBuilder client)
                               (URI/create (str "ws://localhost:" *test-port* "/ws"))
                               listener)
        ws (.get ws-future)]
    {:ws ws
     :messages messages}))

(defn send-msg! [client msg]
  (.sendText (:ws client) (json/generate-string msg) true))

(defn send-raw!
  "Send raw text data (not JSON-encoded) through the WebSocket."
  [client text]
  (.sendText (:ws client) text true))

(defn wait-for-msg [client type-or-pred timeout-ms]
  (let [predicate (if (fn? type-or-pred) type-or-pred #(= (:type %) type-or-pred))
        start (System/currentTimeMillis)]
    (loop []
      (let [msgs @(:messages client)
            match (some (fn [msg] (when (predicate msg) msg)) msgs)]
        (cond
          match match
          (> (- (System/currentTimeMillis) start) timeout-ms) 
          (do 
            (println "Timeout waiting for message. Current messages:" @(:messages client))
            nil)
          :else (do (Thread/sleep 10) (recur)))))))

(deftest test-connection-and-join
  (testing "WebSocket connection and join flow"
    (let [client (connect-client)]
      (send-msg! client {:type msg/join})
      (let [joined-msg (wait-for-msg client msg/joined 2000)]
        (is (= msg/joined (:type joined-msg)))
        (is (string? (:player-id joined-msg)))
        (is (= "default" (:room-id joined-msg))))

      (let [players-msg (wait-for-msg client msg/players 2000)]
        (is (= msg/players (:type players-msg)))
        (is (pos? (count (:players players-msg)))))

      (send-msg! client {:type msg/set-name :name "Bob-the-Builder"})
      (let [players-msg (wait-for-msg client (fn [m] 
                                               (and (= (:type m) msg/players)
                                                    (some #(= "Bob-the-Builder" (:name %)) (:players m))))
                                     2000)]
        (is (some? players-msg) "Should receive players message with new name")))))

(deftest test-multiple-players-lobby
  (testing "Multiple players join and leave"
    (let [c1 (connect-client)
          c2 (connect-client)]
      (send-msg! c1 {:type msg/join})
      (send-msg! c2 {:type msg/join})

      (let [players-msg (wait-for-msg c1 (fn [m]
                                          (and (= (:type m) msg/players)
                                               (= (count (:players m)) 2)))
                                     2000)]
        (is (some? players-msg) "c1 should see 2 players"))

      ;; c2 leaves
      (.sendClose (:ws c2) WebSocket/NORMAL_CLOSURE "")
      
      (let [players-msg (wait-for-msg c1 (fn [m]
                                          (and (= (:type m) msg/players)
                                               (= (count (:players m)) 1)))
                                     2000)]
        (is (some? players-msg) "c1 should see 1 player after c2 leaves")))))

(deftest test-game-round-trip
  (testing "Game start and piece placement"
    (let [c1 (connect-client)]
      (send-msg! c1 {:type msg/join})
      (wait-for-msg c1 msg/joined 1000)
      
      ;; Ready up to start game
      (send-msg! c1 {:type msg/ready})
      (let [start-msg (wait-for-msg c1 msg/game-start 2000)]
        (is (some? start-msg) "Game should start")
        (is (some? (:game start-msg))))

      ;; Place a piece
      (send-msg! c1 {:type msg/place-piece
                     :x 100 :y 100
                     :size "large"
                     :orientation "standing"
                     :angle 0
                     :captured false})

      (let [placed-msg (wait-for-msg c1 msg/piece-placed 2000)]
        (is (some? placed-msg) "Should receive piece-placed message")
        (is (= 1 (count (get-in placed-msg [:game :board]))))
        (let [piece (first (get-in placed-msg [:game :board]))]
          (is (= 100 (:x piece)))
          (is (= "large" (:size piece)))
          (is (= "standing" (:orientation piece))))))))

(deftest test-reconnection-sync
  (testing "New player joins ongoing game and receives state"
    (let [c1 (connect-client)]
      (send-msg! c1 {:type msg/join})
      (wait-for-msg c1 msg/joined 1000)
      (send-msg! c1 {:type msg/ready})
      (wait-for-msg c1 msg/game-start 1000)
      
      ;; c1 places a piece
      (send-msg! c1 {:type msg/place-piece
                     :x 100 :y 100 :size "large" :orientation "standing" :angle 0 :captured false})
      (wait-for-msg c1 msg/piece-placed 1000)

      ;; Now c2 joins
      (let [c2 (connect-client)]
        (send-msg! c2 {:type msg/join})
        (let [sync-msg (wait-for-msg c2 msg/game-start 2000)]
          (is (some? sync-msg) "c2 should receive game-start/sync message")
          (is (= 1 (count (get-in sync-msg [:game :board]))) "c2 should see the piece placed by c1"))))))

(deftest test-piece-capture
  (testing "Game piece capture round-trip"
    (let [c1 (connect-client)
          c2 (connect-client)]
      ;; Both join
      (send-msg! c1 {:type msg/join})
      (let [joined1 (wait-for-msg c1 msg/joined 1000)
            pid1 (keyword (str (:player-id joined1)))]
        (is (some? joined1) "c1 joined")
        (send-msg! c2 {:type msg/join})
        (wait-for-msg c2 msg/joined 1000)
        
        ;; Ready up to start game
        (send-msg! c1 {:type msg/ready})
        (wait-for-msg c1 msg/game-start 1000)
        (wait-for-msg c2 msg/game-start 1000)

        ;; Disable placement throttle for test
        (send-msg! c1 {:type msg/set-option :key "placement-throttle" :value 0})
        (wait-for-msg c1 msg/options 1000)

        ;; C1 places two defenders
        (send-msg! c1 {:type msg/place-piece :x 100 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
        (wait-for-msg c1 (fn [m] (and (= (:type m) msg/piece-placed) (= (get-in m [:piece :x]) 100))) 1000)
        (send-msg! c1 {:type msg/place-piece :x 400 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
        (wait-for-msg c1 (fn [m] (and (= (:type m) msg/piece-placed) (= (get-in m [:piece :x]) 400))) 1000)

        ;; C2 places two defenders far away
        (send-msg! c2 {:type msg/place-piece :x 800 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
        (wait-for-msg c2 (fn [m] (and (= (:type m) msg/piece-placed) (= (get-in m [:piece :x]) 800))) 1000)
        (send-msg! c2 {:type msg/place-piece :x 900 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
        (wait-for-msg c2 (fn [m] (and (= (:type m) msg/piece-placed) (= (get-in m [:piece :x]) 900))) 1000)

        ;; C2 places a Large attacker pointing at C1's defender (100, 100)
        ;; Attacker at (100, 200) pointing up
        (send-msg! c2 {:type msg/place-piece :x 100 :y 200 :size "large" :orientation "pointing" :angle (* -0.5 Math/PI) :captured false})
        (wait-for-msg c2 (fn [m] (and (= (:type m) msg/piece-placed) (= (get-in m [:piece :size]) "large"))) 1000)

        ;; C2 places a Small attacker pointing at C1's defender (100, 100)
        ;; Attacker at (170, 100) pointing left
        (send-msg! c2 {:type msg/place-piece :x 170 :y 100 :size "small" :orientation "pointing" :angle Math/PI :captured false})
        (let [placed-msg (wait-for-msg c2 (fn [m]
                                           (and (= (:type m) msg/piece-placed)
                                                (= (count (get-in m [:game :board])) 6)))
                                     2000)
              board (get-in placed-msg [:game :board])
              small-attacker (first (filter #(and (= (:size %) "small") (= (:orientation %) "pointing")) board))]
          (is (some? small-attacker) "Should find the small attacker")
          
          ;; C1 captures the small attacker
          (send-msg! c1 {:type msg/capture-piece :piece-id (:id small-attacker)})
          (let [captured-msg (wait-for-msg c1 msg/piece-captured 2000)]
            (is (some? captured-msg) "Should receive piece-captured message")
            (is (= (:id small-attacker) (:piece-id captured-msg)))
            (is (= 5 (count (get-in captured-msg [:game :board])))) ;; 4 defenders + 1 large attacker left
            (is (= 1 (count (get-in captured-msg [:game :players pid1 :captured]))))))))))

;; =============================================================================
;; Malformed WebSocket Input Tests
;; =============================================================================

(deftest test-malformed-json
  (testing "Server responds with error when receiving invalid JSON"
    (let [client (connect-client)]
      (send-raw! client "not json at all{{")
      (let [error-msg (wait-for-msg client msg/error 2000)]
        (is (some? error-msg) "Should receive an error message")
        (is (= msg/error (:type error-msg)))
        (is (re-find #"(?i)invalid json" (:message error-msg)))))))

(deftest test-invalid-schema-message
  (testing "Server responds with error for valid JSON that fails schema validation"
    (let [client (connect-client)]
      ;; Valid JSON but doesn't match any ClientMessage schema variant
      (send-raw! client "{\"type\": \"place-piece\", \"x\": \"not-a-number\"}")
      (let [error-msg (wait-for-msg client msg/error 2000)]
        (is (some? error-msg) "Should receive an error message")
        (is (= msg/error (:type error-msg)))
        (is (re-find #"(?i)invalid message" (:message error-msg)))))))

(deftest test-missing-type-field
  (testing "Server responds with error when message has no type field"
    (let [client (connect-client)]
      (send-raw! client "{\"foo\": \"bar\"}")
      (let [error-msg (wait-for-msg client msg/error 2000)]
        (is (some? error-msg) "Should receive an error message")
        (is (= msg/error (:type error-msg)))))))

(deftest test-unknown-message-type
  (testing "Server responds with error for unknown message type"
    (let [client (connect-client)]
      (send-msg! client {:type "nonexistent-action"})
      ;; Schema validation catches unknown types before the condp dispatch,
      ;; so the error is "Invalid message format" rather than "Unknown message type"
      (let [error-msg (wait-for-msg client msg/error 2000)]
        (is (some? error-msg) "Should receive an error message")
        (is (= msg/error (:type error-msg)))
        (is (re-find #"(?i)invalid message" (:message error-msg)))))))

;; =============================================================================
;; Nil Room-ID Integration Test
;; =============================================================================

(deftest test-place-piece-before-join
  (testing "Sending a game message before joining returns an error"
    (let [client (connect-client)]
      ;; Don't join a room, just send a game action directly
      (send-msg! client {:type msg/place-piece
                         :x 100 :y 100
                         :size "small"
                         :orientation "standing"
                         :angle 0
                         :captured false})
      (let [error-msg (wait-for-msg client msg/error 2000)]
        (is (some? error-msg) "Should receive an error message")
        (is (= msg/error (:type error-msg)))
        (is (= msg/err-invalid-game (:code error-msg)))))))

;; =============================================================================
;; Concurrent Multi-Client Operations Test
;; =============================================================================

(deftest test-concurrent-piece-placement
  (testing "Two clients placing pieces simultaneously results in consistent game state"
    (let [c1 (connect-client)
          c2 (connect-client)]
      ;; Both join
      (send-msg! c1 {:type msg/join})
      (wait-for-msg c1 msg/joined 1000)
      (send-msg! c2 {:type msg/join})
      (wait-for-msg c2 msg/joined 1000)

      ;; Disable placement throttle
      (send-msg! c1 {:type msg/set-option :key "placement-throttle" :value 0})
      (wait-for-msg c1 msg/options 1000)

      ;; Start game
      (send-msg! c1 {:type msg/ready})
      (wait-for-msg c1 msg/game-start 2000)
      (wait-for-msg c2 msg/game-start 2000)

      ;; Both clients send piece placements at the same time (no waiting between sends)
      (send-msg! c1 {:type msg/place-piece :x 100 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
      (send-msg! c2 {:type msg/place-piece :x 800 :y 100 :size "small" :orientation "standing" :angle 0 :captured false})
      (send-msg! c1 {:type msg/place-piece :x 200 :y 200 :size "medium" :orientation "standing" :angle 0 :captured false})
      (send-msg! c2 {:type msg/place-piece :x 700 :y 200 :size "medium" :orientation "standing" :angle 0 :captured false})

      ;; Wait for the board to show 4 pieces (or at least some pieces placed)
      (let [final-msg (wait-for-msg c1
                                     (fn [m]
                                       (and (= (:type m) msg/piece-placed)
                                            (>= (count (get-in m [:game :board])) 4)))
                                     5000)]
        (is (some? final-msg) "Should see at least 4 pieces on board after concurrent placement")
        (when final-msg
          (let [board (get-in final-msg [:game :board])
                piece-ids (map :id board)]
            ;; All piece IDs should be unique (no duplicate placements)
            (is (= (count piece-ids) (count (distinct piece-ids)))
                "All piece IDs should be unique after concurrent placement")))))))