(ns icehouse.lobby
  (:require [icehouse.game :as game]
            [icehouse.utils :as utils]))

(def default-names
  ["Alice" "Bob" "Charles" "Dave" "Eve" "Frank" "Grace" "Henry"])

(def colours
  ["#ff6b6b"   ; red
   "#4ecdc4"   ; teal
   "#ffe66d"   ; yellow
   "#95e1d3"   ; mint
   "#f38181"   ; coral
   "#aa96da"   ; lavender
   "#fcbad3"   ; pink
   "#a8d8ea"]) ; sky blue

(defn get-taken-names [clients room-id]
  "Returns set of names already taken in the room"
  (->> @clients
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[_ c]] (:name c)))
       (remove nil?)
       set))

(defn get-taken-colours [clients room-id]
  "Returns set of colours already taken in the room"
  (->> @clients
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[_ c]] (:colour c)))
       (remove nil?)
       set))

(defn next-available-name [clients room-id]
  "Returns the first available default name for the room"
  (let [taken (get-taken-names clients room-id)]
    (first (remove taken default-names))))

(defn next-available-colour [clients room-id]
  "Returns the first available colour for the room"
  (let [taken (get-taken-colours clients room-id)]
    (first (remove taken colours))))

(defn get-room-players [clients room-id]
  (->> @clients
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[ch c]] {:id (game/player-id-from-channel ch)
                          :name (:name c)
                          :colour (:colour c)
                          :ready (:ready c)}))))

(defn broadcast-players! [clients room-id]
  (let [players (get-room-players clients room-id)]
    (utils/broadcast-room! clients room-id
                           {:type "players"
                            :players players})))

(defn all-ready? [clients room-id]
  (let [players (->> @clients
                     (filter (fn [[_ c]] (= (:room-id c) room-id))))]
    ;; DEV MODE: Start game when first player is ready (normally requires 3-4 players all ready)
    (and (>= (count players) 1)
         (some (fn [[_ c]] (:ready c)) players))))

(defn handle-join [clients channel msg]
  (let [room-id (or (:room-id msg) "default")
        default-name (next-available-name clients room-id)
        default-colour (next-available-colour clients room-id)]
    (swap! clients update channel merge
           {:room-id room-id
            :name default-name
            :colour default-colour})
    (utils/send-msg! channel {:type "joined"
                              :room-id room-id
                              :player-id (game/player-id-from-channel channel)
                              :name default-name
                              :colour default-colour})
    (broadcast-players! clients room-id)))

(defn handle-set-name [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])]
    (swap! clients assoc-in [channel :name] (:name msg))
    (broadcast-players! clients room-id)))

(defn handle-set-colour [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])]
    (swap! clients assoc-in [channel :colour] (:colour msg))
    (broadcast-players! clients room-id)))

(defn handle-ready [clients channel]
  (let [room-id (get-in @clients [channel :room-id])]
    (swap! clients update-in [channel :ready] not)
    (let [players (get-room-players clients room-id)]
      (println "Ready toggled. Players:" (count players) "All ready?" (all-ready? clients room-id))
      (doseq [p players]
        (println "  -" (:name p) "ready:" (:ready p))))
    (broadcast-players! clients room-id)
    (when (all-ready? clients room-id)
      (println "All ready! Starting game for room:" room-id)
      (let [players (get-room-players clients room-id)]
        (game/start-game! room-id players)
        (utils/broadcast-room! clients room-id {:type "game-start"
                                                :game (get @game/games room-id)})))))

(defn handle-disconnect [clients channel]
  (let [room-id (get-in @clients [channel :room-id])]
    (when room-id
      (broadcast-players! clients room-id))))
