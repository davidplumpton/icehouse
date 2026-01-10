(ns icehouse.utils
  (:require
    [icehouse.schema :as schema]
    #?(:clj [cheshire.core :as json])
    #?(:clj [org.httpkit.server :as http])
    #?(:clj [malli.core :as m])))

(defn normalize-player-id
  "Normalize player ID to consistent form for comparison and map lookups.
   CLJ: Strings (matches backend storage/logic)
   CLJS: Keywords (matches frontend state/JSON handling)"
  [player-id]
  #?(:clj
     (cond
       (nil? player-id) nil
       (string? player-id) player-id
       (keyword? player-id) (name player-id)
       :else (str player-id))
     :cljs
     (cond
       (nil? player-id) nil
       (keyword? player-id) player-id
       :else (keyword player-id))))

;; =============================================================================
;; Filter Helpers
;; =============================================================================

(defn by-field [field value]
  (fn [item] (= (get item field) value)))

(defn by-id [id]
  (by-field :id id))

(defn by-size [size]
  (fn [item] (= (keyword (:size item)) (keyword size))))

(defn by-player-id [player-id]
  (let [normalized (normalize-player-id player-id)]
    (fn [item] (= (normalize-player-id (:player-id item)) normalized))))

;; Note: standing? and pointing? are defined in icehouse.geometry

;; =============================================================================
;; Captured Piece Helpers
;; =============================================================================

(defn count-captured-by-size
  "Count captured pieces of a given size"
  [captured size]
  (count (filter (by-size size) captured)))

(defn get-captured-piece
  "Get the first captured piece of a given size, or nil if none"
  [captured size]
  (first (filter (by-size size) captured)))

(defn remove-first-captured
  "Remove the first captured piece of the given size from the list"
  [captured size]
  (let [idx (first (keep-indexed #(when ((by-size size) %2) %1) captured))]
    (if idx
      (vec (concat (subvec captured 0 idx) (subvec captured (inc idx))))
      captured)))

;; =============================================================================
;; Format Helpers
;; =============================================================================

(defn format-time
  "Format milliseconds as MM:SS"
  [ms]
  (let [total-seconds (quot ms 1000)
        minutes (quot total-seconds 60)
        seconds (mod total-seconds 60)]
    (str (when (< minutes 10) "0") minutes
         ":"
         (when (< seconds 10) "0") seconds)))

#?(:clj
   (defn validate-incoming-message
     "Validate incoming client message against schema"
     [message]
     (if (m/validate schema/ClientMessage message)
       message
       (do
         (println "Invalid client message:" message)
         (println "Validation errors:" (m/explain schema/ClientMessage message))
         nil))))

#?(:clj
   (defn validate-outgoing-message
     "Validate outgoing server message against schema"
     [msg]
     (if (m/validate schema/ServerMessage msg)
       msg
       (do
         (println "Invalid outgoing message:" msg)
         (println "Validation errors:" (m/explain schema/ServerMessage msg))
         nil))))

#?(:clj
   (defn send-msg!
     "Send a JSON message to a single channel. Validates message against schema."
     [channel msg]
     (if (validate-outgoing-message msg)
       (http/send! channel (json/generate-string msg))
       (do
         (println "ERROR: Refusing to send invalid outgoing message:" msg)
         nil))))

#?(:clj
   (defn broadcast-room!
     "Broadcast a JSON message to all clients in a room"
     [clients room-id msg]
     (doseq [[ch client] @clients
             :when (= (:room-id client) room-id)]
       (send-msg! ch msg))))
