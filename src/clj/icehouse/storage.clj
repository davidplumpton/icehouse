(ns icehouse.storage
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def games-dir "data/games")

;; UUID pattern for game-id validation
(def uuid-pattern #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(defn valid-game-id?
  "Check if game-id is a valid UUID format (prevents path traversal)"
  [game-id]
  (and (string? game-id)
       (re-matches uuid-pattern game-id)))

(defn ensure-games-dir!
  "Create the games directory if it doesn't exist"
  []
  (.mkdirs (io/file games-dir)))

(defn game-record-path
  "Get the file path for a game record.
   Returns nil if game-id is invalid."
  [game-id]
  (when (valid-game-id? game-id)
    (str games-dir "/" game-id ".edn")))

(defn save-game-record!
  "Save a game record to disk as EDN. Returns the file path, or nil if game-id is invalid."
  [record]
  (when-let [path (game-record-path (:game-id record))]
    (ensure-games-dir!)
    (spit path (pr-str record))
    path))

(defn load-game-record
  "Load a game record from disk by game-id. Returns nil if not found or game-id is invalid."
  [game-id]
  (when-let [path (game-record-path game-id)]
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
