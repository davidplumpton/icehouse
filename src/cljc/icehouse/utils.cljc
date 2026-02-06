(ns icehouse.utils
  (:require
    [icehouse.schema :as schema]
    #?(:clj [cheshire.core :as json])
    #?(:clj [org.httpkit.server :as http])
    #?(:clj [taoensso.timbre :as log])
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

(defn by-field
  "Returns a predicate function that checks if a map field equals a value."
  [field value]
  (fn [item] (= (get item field) value)))

(defn by-id
  "Returns a predicate function that checks if a map's :id field equals the 
   given id."
  [id]
  (by-field :id id))

(defn by-size
  "Returns a predicate function that checks if a map's :size field (as a keyword) 
   equals the given size."
  [size]
  (fn [item] (= (keyword (:size item)) (keyword size))))

(defn by-player-id
  "Returns a predicate function that checks if a map's :player-id field matches 
   the given player-id (after normalization)."
  [player-id]
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

;; =============================================================================
;; Room-Channel Index (CLJ only)
;; =============================================================================

#?(:clj
   ;; Secondary index mapping room-id to the set of channels in that room.
   ;; Maintained alongside the clients atom for O(1) room member lookups.
   (defonce room-channels (atom {})))

#?(:clj
   (defn add-channel-to-room!
     "Add a channel to the room-channels index for a given room."
     [room-id channel]
     (swap! room-channels update room-id (fnil conj #{}) channel)))

#?(:clj
   (defn remove-channel-from-room!
     "Remove a channel from the room-channels index for a given room."
     [room-id channel]
     (swap! room-channels
            (fn [m]
              (let [updated (disj (get m room-id #{}) channel)]
                (if (empty? updated)
                  (dissoc m room-id)
                  (assoc m room-id updated)))))))

#?(:clj
   (defn reset-room-channels!
     "Clear the room-channels index. Used by server/test reset paths."
     []
     (reset! room-channels {})))

;; =============================================================================
;; Room/Game Lookups (CLJ only)
;; =============================================================================

#?(:clj
   ;; Centralized room/game lookups for websocket handlers.
   (defn get-room-id
     "Return the room-id for a channel from the clients atom, or nil if none."
     [clients channel]
     (get-in @clients [channel :room-id])))

#?(:clj
   (defn get-game-for-channel
     "Return {:room-id r :game g} for a channel, or nil if the channel is not in a room."
     [clients games channel]
     (when-let [room-id (get-room-id clients channel)]
       {:room-id room-id
        :game (get @games room-id)})))

#?(:clj
   (defn validate-incoming-message
     "Validate incoming client message against schema"
     [message]
     (if (m/validate schema/ClientMessage message)
       message
       (do
         (log/warn "Invalid client message"
                   {:message message
                    :explanation (m/explain schema/ClientMessage message)})
         nil))))

#?(:clj
   (defn validate-outgoing-message
     "Validate outgoing server message against schema"
     [msg]
     (if (m/validate schema/ServerMessage msg)
       msg
       (let [explanation (m/explain schema/ServerMessage msg)]
         (log/error "Invalid outgoing message"
                    {:message msg
                     :explanation explanation})
         nil))))

#?(:clj
   (defn send-msg!
     "Send a JSON message to a single channel. Validates message against schema."
     [channel msg]
     (if (validate-outgoing-message msg)
       (http/send! channel (json/generate-string msg))
       (do
         (log/error "Refusing to send invalid outgoing message" {:message msg})
         nil))))

#?(:clj
   (defn broadcast-room!
     "Broadcast a JSON message to all channels in a room.
      Uses the room-channels index for O(1) room member lookup."
     [_clients room-id msg]
     (doseq [ch (get @room-channels room-id #{})]
       (send-msg! ch msg))))
