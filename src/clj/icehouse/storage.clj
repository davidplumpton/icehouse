(ns icehouse.storage
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [icehouse.schema :as schema]
            [malli.core :as m]))

(def ^:private edn-extension ".edn")

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
    (str games-dir "/" game-id edn-extension)))

(defn save-game-record!
  "Save a game record to disk as EDN. Returns the file path, or nil if invalid."
  [record]
  (if-not (m/validate schema/GameRecord record)
    (do
      (println "ERROR: Refusing to save invalid game record:" (m/explain schema/GameRecord record))
      nil)
    (when-let [path (game-record-path (:game-id record))]
      (ensure-games-dir!)
      (spit path (pr-str record))
      path)))

(defn load-game-record
  "Load a game record from disk by game-id. Returns nil if not found or invalid."
  [game-id]
  (when-let [path (game-record-path game-id)]
    (when (.exists (io/file path))
      (let [record (edn/read-string {:readers {}} (slurp path))]
        (if (m/validate schema/GameRecord record)
          record
          (do
            (println "ERROR: Loaded invalid game record from" path ":" (m/explain schema/GameRecord record))
            nil))))))

(defn- extract-game-id
  "Extract game-id from an EDN filename"
  [^java.io.File file]
  (let [name (.getName file)]
    (when (str/ends-with? name edn-extension)
      (subs name 0 (- (count name) (count edn-extension))))))

(defn list-game-records
  "List all saved game record IDs, sorted by filename"
  []
  {:post [(m/validate [:sequential schema/id-string] %)]}
  (ensure-games-dir!)
  (if-let [files (.listFiles (io/file games-dir))]
    (->> files
         (filter #(.isFile ^java.io.File %))
         (keep extract-game-id)
         (sort)
         vec)
    []))
