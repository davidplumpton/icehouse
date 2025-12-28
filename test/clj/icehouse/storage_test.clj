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

(deftest ensure-games-dir-test
  (testing "ensure-games-dir creates directory if it doesn't exist"
    (let [dir (io/file test-dir)]
      (is (not (.exists dir)))
      (storage/ensure-games-dir!)
      (is (.exists dir))
      (is (.isDirectory dir)))))

(deftest save-and-load-game-record-test
  (testing "save-game-record! writes EDN file and load-game-record reads it back"
    (let [record {:game-id "test-123"
                  :version 1
                  :room-id "room-1"
                  :players {"p1" {:name "Alice"}}
                  :moves [{:type :place-piece :elapsed-ms 1000}]
                  :final-board []
                  :final-scores {"p1" 5}}
          path (storage/save-game-record! record)
          loaded (storage/load-game-record "test-123")]
      (is (.endsWith path ".edn"))
      (is (= record loaded)))))

(deftest load-nonexistent-game-test
  (testing "load-game-record returns nil for nonexistent game"
    (is (nil? (storage/load-game-record "nonexistent-game-id")))))

(deftest list-game-records-test
  (testing "list-game-records returns empty list when no games"
    (storage/ensure-games-dir!)
    (is (= [] (storage/list-game-records))))

  (testing "list-game-records returns saved game IDs"
    (storage/save-game-record! {:game-id "game-1" :version 1})
    (storage/save-game-record! {:game-id "game-2" :version 1})
    (storage/save-game-record! {:game-id "game-3" :version 1})
    (let [games (storage/list-game-records)]
      (is (= 3 (count games)))
      (is (some #{"game-1"} games))
      (is (some #{"game-2"} games))
      (is (some #{"game-3"} games)))))
