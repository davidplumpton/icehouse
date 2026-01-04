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
     "Send a JSON message to a single channel"
     [channel msg]
     ;; Validation is optional - log failures but still send the message
     ;; This allows the app to function even if schemas don't match reality
     (when-not (validate-outgoing-message msg)
       (println "Warning: Message validation failed:" msg))
     (http/send! channel (json/generate-string msg))))

#?(:clj
   (defn broadcast-room!
     "Broadcast a JSON message to all clients in a room"
     [clients room-id msg]
     (doseq [[ch client] @clients
             :when (= (:room-id client) room-id)]
       (send-msg! ch msg))))
