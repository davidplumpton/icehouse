(ns icehouse.lobby
  (:require [icehouse.game :as game]
            [icehouse.messages :as msg]
            [icehouse.utils :as utils]
            [icehouse.schema :as schema]
            [malli.core :as m]))

;; Game options stored per room
(defonce room-options (atom {}))

(def default-options
  {:icehouse-rule true        ;; Whether icehouse rule is enforced
   :timer-enabled true        ;; Whether game has a time limit
   :timer-duration :random    ;; :random (2-5 min), or specific ms value
   :placement-throttle 2.0})  ;; Seconds to wait between placements

(defn get-room-options [room-id]
  {:post [(m/validate schema/GameOptions %)]}
  (get @room-options room-id default-options))

(def default-names
  ["Alice" "Bob" "Charles" "Dave" "Eve" "Frank" "Grace" "Henry"])

(def colours
  ["#e53935"   ; red (Rainbow)
   "#fdd835"   ; yellow (Rainbow)
   "#43a047"   ; green (Rainbow)
   "#1e88e5"   ; blue (Rainbow)
   "#7b1fa2"   ; purple (Xeno)
   "#00acc1"   ; cyan (Xeno)
   "#fb8c00"   ; orange (Xeno)
   "#212121"]) ; black (Rainbow)

(defn get-taken-names [clients-map room-id]
  "Returns set of names already taken in the room"
  (->> clients-map
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[_ c]] (:name c)))
       (remove nil?)
       set))

(defn get-taken-colours [clients-map room-id]
  "Returns set of colours already taken in the room"
  (->> clients-map
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[_ c]] (:colour c)))
       (remove nil?)
       set))

(defn next-available-name [clients-map room-id]
  "Returns the first available default name for the room"
  (let [taken (get-taken-names clients-map room-id)]
    (first (remove taken default-names))))

(defn next-available-colour [clients-map room-id]
  "Returns the first available colour for the room"
  (let [taken (get-taken-colours clients-map room-id)]
    (first (remove taken colours))))

(defn get-room-players [clients-map room-id]
  {:post [(m/validate schema/PlayerList %)]}
  (->> clients-map
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[ch c]] {:id (game/player-id-from-channel ch)
                          :name (:name c)
                          :colour (:colour c)
                          :ready (:ready c)}))))

(defn broadcast-players! [clients room-id]
  (let [players (get-room-players @clients room-id)]
    (utils/broadcast-room! clients room-id
                           {:type msg/players
                            :players players})))

(defn broadcast-options! [clients room-id]
  (utils/broadcast-room! clients room-id
                         {:type msg/options
                          :options (get-room-options room-id)}))

(defn all-ready? [clients room-id]
  (let [players (->> @clients
                     (filter (fn [[_ c]] (= (:room-id c) room-id))))]
    ;; DEV MODE: Start game when first player is ready (normally requires 3-4 players all ready)
    (and (>= (count players) 1)
         (some (fn [[_ c]] (:ready c)) players))))

(defn handle-join [clients channel msg]
  (let [room-id (or (:room-id msg) "default")
        _ (swap! clients (fn [m]
                           (let [default-name (next-available-name m room-id)
                                 default-colour (next-available-colour m room-id)]
                             (update m channel merge
                                     {:room-id room-id
                                      :name default-name
                                      :colour default-colour}))))
        new-client (get @clients channel)]
    (utils/send-msg! channel {:type msg/joined
                              :room-id room-id
                              :player-id (game/player-id-from-channel channel)
                              :name (:name new-client)
                              :colour (:colour new-client)})
    (broadcast-players! clients room-id)
    ;; Send current game options to the new player
    (utils/send-msg! channel {:type msg/options
                              :options (get-room-options room-id)})))

(defn handle-set-name [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])]
    (swap! clients assoc-in [channel :name] (:name msg))
    (broadcast-players! clients room-id)))

(defn handle-set-colour [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        new-colour (:colour msg)
        taken (get-taken-colours @clients room-id)]
    (if (contains? taken new-colour)
      (utils/send-msg! channel {:type msg/error :message "That colour is already taken"})
      (do
        (swap! clients assoc-in [channel :colour] new-colour)
        (broadcast-players! clients room-id)))))

(defn handle-ready [clients channel]
  (let [room-id (get-in @clients [channel :room-id])]
    (swap! clients update-in [channel :ready] not)
    (broadcast-players! clients room-id)
    (when (all-ready? clients room-id)
      (let [players (get-room-players @clients room-id)
            options (get-room-options room-id)]
        (game/start-game! room-id players options)
        (utils/broadcast-room! clients room-id {:type msg/game-start
                                                :game (get @game/games room-id)})))))

(defn handle-disconnect [clients channel]
  (let [room-id (get-in @clients [channel :room-id])]
    (when room-id
      (broadcast-players! clients room-id))))

(defn handle-set-option [clients channel msg]
  "Set a game option for the room. Any player can change options before game starts."
  (let [room-id (get-in @clients [channel :room-id])
        option-key (keyword (:key msg))
        option-value (:value msg)]
    (when (and room-id (contains? default-options option-key))
      (swap! room-options update room-id
             (fn [opts]
               (assoc (or opts default-options) option-key option-value)))
      (broadcast-options! clients room-id))))
