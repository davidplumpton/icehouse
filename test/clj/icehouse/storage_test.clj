(ns icehouse.storage-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [icehouse.storage :as storage]
            [clojure.java.io :as io]))

(def test-dir "data/games-test")

(defn with-test-dir [f]
  ;; Use a test directory to avoid interfering with real data
  (with-redefs [storage/games-dir test-dir]
    ;; Clean up test directory before and after
    (let [dir (io/file test-dir)]
      (when (.exists dir)
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir)))
    (f)
    (let [dir (io/file test-dir)]
      (when (.exists dir)
        (doseq [file (.listFiles dir)]
          (.delete file))
        (.delete dir)))))

(use-fixtures :each with-test-dir)

(defn minimal-valid-record [game-id]
  {:version 1
   :game-id game-id
   :room-id "test-room"
   :players {"p1" {:name "Alice" :colour "#ff6b6b"}}
   :started-at 1000
   :ended-at 2000
   :duration-ms 1000
   :end-reason :all-pieces-placed
   :moves []
   :final-board []
   :final-scores {"p1" 0}
   :icehouse-players []})

(deftest ensure-games-dir-test
  (testing "ensure-games-dir creates directory if it doesn't exist"
    (let [dir (io/file test-dir)]
      (is (not (.exists dir)))
      (storage/ensure-games-dir!)
      (is (.exists dir))
      (is (.isDirectory dir)))))

(deftest save-and-load-game-record-test
  (testing "save-game-record! writes EDN file and load-game-record reads it back"
    (let [game-id "12345678-1234-1234-1234-123456789012"
          record (minimal-valid-record game-id)
          path (storage/save-game-record! record)
          loaded (storage/load-game-record game-id)]
      (is (.endsWith path ".edn"))
      (is (= record loaded)))))

(deftest load-nonexistent-game-test
  (testing "load-game-record returns nil for nonexistent game"
    (is (nil? (storage/load-game-record "12345678-1234-1234-1234-123456789012")))))

(deftest invalid-game-id-test
  (testing "valid-game-id? accepts valid UUIDs"
    (is (storage/valid-game-id? "12345678-1234-1234-1234-123456789012"))
    (is (storage/valid-game-id? "ABCDEF12-3456-7890-ABCD-EF1234567890")))

  (testing "valid-game-id? rejects invalid inputs"
    (is (not (storage/valid-game-id? nil)))
    (is (not (storage/valid-game-id? "")))
    (is (not (storage/valid-game-id? "simple-string")))
    (is (not (storage/valid-game-id? "../etc/passwd")))
    (is (not (storage/valid-game-id? "../../secret")))
    (is (not (storage/valid-game-id? "game-id/../../etc"))))

  (testing "save-game-record! returns nil for invalid game-id"
    (is (nil? (storage/save-game-record! (minimal-valid-record "../malicious")))))

  (testing "load-game-record returns nil for invalid game-id"
    (is (nil? (storage/load-game-record "../etc/passwd")))))

(deftest list-game-records-test
  (testing "list-game-records returns empty list when no games"
    (storage/ensure-games-dir!)
    (is (= [] (storage/list-game-records))))

  (testing "list-game-records returns saved game IDs"
    (let [id1 "11111111-1111-1111-1111-111111111111"
          id2 "22222222-2222-2222-2222-222222222222"
          id3 "33333333-3333-3333-3333-333333333333"]
      (storage/save-game-record! (minimal-valid-record id1))
      (storage/save-game-record! (minimal-valid-record id2))
      (storage/save-game-record! (minimal-valid-record id3))
      (let [games (storage/list-game-records)]
        (is (= 3 (count games)))
        (is (some #{id1} games))
        (is (some #{id2} games))
        (is (some #{id3} games))))))
