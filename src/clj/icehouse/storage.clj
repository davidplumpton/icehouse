(ns icehouse.storage
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def games-dir "data/games")

(defn ensure-games-dir!
  "Create the games directory if it doesn't exist"
  []
  (.mkdirs (io/file games-dir)))

(defn game-record-path
  "Get the file path for a game record"
  [game-id]
  (str games-dir "/" game-id ".edn"))

(defn save-game-record!
  "Save a game record to disk as EDN. Returns the file path."
  [record]
  (ensure-games-dir!)
  (let [path (game-record-path (:game-id record))]
    (spit path (pr-str record))
    path))

(defn load-game-record
  "Load a game record from disk by game-id. Returns nil if not found."
  [game-id]
  (let [path (game-record-path game-id)]
    (when (.exists (io/file path))
      (edn/read-string (slurp path)))))

(defn list-game-records
  "List all saved game record IDs, sorted by filename"
  []
  (ensure-games-dir!)
  (->> (io/file games-dir)
       (.listFiles)
       (filter #(and (.isFile %)
                     (.endsWith (.getName %) ".edn")))
       (map #(subs (.getName %) 0 (- (count (.getName %)) 4)))
       (sort)))
