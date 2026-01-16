(ns icehouse.lobby
  (:require [icehouse.game :as game]
            [icehouse.utils :as utils]))

(defn get-room-players [clients room-id]
  (->> @clients
       (filter (fn [[_ c]] (= (:room-id c) room-id)))
       (map (fn [[ch c]] {:id (str (hash ch))
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
  (let [room-id (or (:room-id msg) "default")]
    (swap! clients assoc-in [channel :room-id] room-id)
    (utils/send-msg! channel {:type "joined" :room-id room-id :player-id (str (hash channel))})
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
