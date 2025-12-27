(ns icehouse.game
  (:require [icehouse.utils :as utils]))

(defonce games (atom {}))

;; Points per piece size
(def pips {:small 1 :medium 2 :large 3})

;; Piece sizes in pixels (must match frontend)
(def piece-sizes {:small 30 :medium 50 :large 70})

;; Collision detection using Separating Axis Theorem (SAT)

(defn rotate-point
  "Rotate point [x y] around origin by angle (radians)"
  [[x y] angle]
  (let [cos-a (Math/cos angle)
        sin-a (Math/sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn piece-vertices
  "Get vertices of a piece in world coordinates"
  [{:keys [x y size orientation angle]}]
  (let [base-size (get piece-sizes size 30)
        half (/ base-size 2)
        angle (or angle 0)
        ;; Local vertices relative to center
        local-verts (if (= orientation :standing)
                      ;; Standing: square
                      [[(- half) (- half)]
                       [half (- half)]
                       [half half]
                       [(- half) half]]
                      ;; Pointing: triangle (3:2 length:base ratio to match frontend)
                      (let [half-width (* base-size 0.75)]
                        [[half-width 0]
                         [(- half-width) (- half)]
                         [(- half-width) half]]))]
    ;; Rotate and translate to world coordinates
    (mapv (fn [[lx ly]]
            (let [[rx ry] (rotate-point [lx ly] angle)]
              [(+ x rx) (+ y ry)]))
          local-verts)))

(defn edge-normal
  "Get perpendicular normal vector for edge from v1 to v2"
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len)
      [1 0]
      [(/ (- dy) len) (/ dx len)])))

(defn polygon-axes
  "Get all edge normals for a polygon (for SAT)"
  [vertices]
  (let [n (count vertices)]
    (mapv (fn [i]
            (edge-normal (nth vertices i)
                         (nth vertices (mod (inc i) n))))
          (range n))))

(defn project-polygon
  "Project polygon vertices onto an axis, return [min max]"
  [vertices [ax ay]]
  (let [dots (map (fn [[x y]] (+ (* x ax) (* y ay))) vertices)]
    [(apply min dots) (apply max dots)]))

(defn projections-overlap?
  "Check if two 1D projections [min1 max1] and [min2 max2] overlap"
  [[min1 max1] [min2 max2]]
  (and (<= min1 max2) (<= min2 max1)))

(defn polygons-intersect?
  "Check if two convex polygons intersect using SAT"
  [verts1 verts2]
  (let [axes (concat (polygon-axes verts1) (polygon-axes verts2))]
    ;; Polygons intersect if projections overlap on ALL axes
    (every? (fn [axis]
              (projections-overlap?
               (project-polygon verts1 axis)
               (project-polygon verts2 axis)))
            axes)))

(defn pieces-intersect?
  "Check if two pieces intersect"
  [piece1 piece2]
  (polygons-intersect?
   (piece-vertices piece1)
   (piece-vertices piece2)))

(defn intersects-any-piece?
  "Check if a piece intersects any piece on the board"
  [piece board]
  (some #(pieces-intersect? piece %) board))

;; Attacking piece validation

(defn distance
  "Calculate distance between two points"
  [[x1 y1] [x2 y2]]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1)))))

(defn piece-center
  "Get the center point of a piece"
  [piece]
  [(:x piece) (:y piece)])

(defn attack-direction
  "Get the unit direction vector for an attacking piece"
  [piece]
  (let [angle (or (:angle piece) 0)]
    [(Math/cos angle) (Math/sin angle)]))

(defn attacker-tip
  "Get the tip position of a pointing piece (where the attack ray originates)"
  [piece]
  (let [base-size (get piece-sizes (:size piece) 30)
        tip-offset (* base-size 0.75)  ;; Tip is at 3/4 of base-size from center
        angle (or (:angle piece) 0)
        [dx dy] [(Math/cos angle) (Math/sin angle)]]
    [(+ (:x piece) (* dx tip-offset))
     (+ (:y piece) (* dy tip-offset))]))

(defn ray-segment-intersection?
  "Check if a ray from origin in direction dir intersects line segment [p1 p2].
   Uses parametric line intersection."
  [[ox oy] [dx dy] [[p1x p1y] [p2x p2y]]]
  (let [;; Segment direction
        sx (- p2x p1x)
        sy (- p2y p1y)
        ;; Cross product of directions
        denom (- (* dx sy) (* dy sx))]
    (if (< (Math/abs denom) 0.0001)
      false  ;; Parallel lines
      (let [;; Vector from ray origin to segment start
            ox-p1x (- p1x ox)
            oy-p1y (- p1y oy)
            ;; Parameter t for ray (distance along ray direction)
            t (/ (- (* ox-p1x sy) (* oy-p1y sx)) denom)
            ;; Parameter u for segment (0-1 means on segment)
            u (/ (- (* ox-p1x dy) (* oy-p1y dx)) denom)]
        ;; Ray hits segment if t >= 0 (forward direction) and 0 <= u <= 1 (on segment)
        (and (>= t 0) (>= u 0) (<= u 1))))))

(defn ray-intersects-polygon?
  "Check if a ray from origin in direction dir intersects any edge of polygon"
  [origin direction vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (some #(ray-segment-intersection? origin direction %) edges)))

(defn in-front-of?
  "Check if attacker's pointing direction ray intersects the target piece"
  [attacker target]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)]
    (ray-intersects-polygon? tip dir target-verts)))

(defn attack-range
  "Get the attack range for a piece (its own length)"
  [piece]
  (get piece-sizes (:size piece) 30))

(defn within-range?
  "Check if target is within attack range of attacker"
  [attacker target]
  (let [dist (distance (piece-center attacker) (piece-center target))
        range (attack-range attacker)]
    (<= dist range)))

(defn valid-target?
  "Check if target is a valid attack target for the attacker.
   Per Icehouse rules, can only target standing (defending) opponent pieces."
  [attacker target attacker-player-id]
  (and (not= (:player-id target) attacker-player-id)  ;; Different player
       (= (:orientation target) :standing)             ;; Must be a defender (standing)
       (in-front-of? attacker target)                  ;; In front
       (within-range? attacker target)))               ;; Within range

(defn find-valid-targets
  "Find all valid targets for an attacking piece"
  [attacker player-id board]
  (filter #(valid-target? attacker % player-id) board))

(defn has-valid-target?
  "Check if an attacking piece has at least one valid target"
  [piece player-id board]
  (seq (find-valid-targets piece player-id board)))

;; Starting piece counts per player
(def initial-piece-counts {:small 5 :medium 5 :large 5})

(defn initial-pieces []
  initial-piece-counts)

(defn create-game [room-id players]
  {:room-id room-id
   :players (into {} (map (fn [p] [(:id p) {:name (:name p)
                                            :colour (:colour p)
                                            :pieces (initial-pieces)}])
                          players))
   :board []
   :started-at (System/currentTimeMillis)})

(defn validate-placement
  "Validate piece placement, returns nil if valid or error message if invalid"
  [game player-id piece]
  (let [player (get-in game [:players player-id])
        size (:size piece)
        remaining (get-in player [:pieces size] 0)
        board (:board game)
        is-attacking? (= (:orientation piece) :pointing)]
    (cond
      (not (pos? remaining))
      "No pieces of that size remaining"

      (intersects-any-piece? piece board)
      "Piece would overlap with existing piece"

      (and is-attacking? (not (has-valid-target? piece player-id board)))
      "Attacking piece must target an opponent's piece within range"

      :else nil)))

(defn valid-placement? [game player-id piece]
  (nil? (validate-placement game player-id piece)))

(defn attackers-by-target
  "Returns a map of target-id -> list of attackers targeting that piece"
  [board]
  (let [pointing-pieces (filter #(and (= (:orientation %) :pointing)
                                      (:target-id %))
                                board)]
    (group-by :target-id pointing-pieces)))

(defn attack-strength
  "Sum of pip values of all attackers targeting a piece"
  [attackers]
  (reduce + (map #(get pips (:size %) 0) attackers)))

(defn find-piece-by-id [board id]
  (first (filter #(= (:id %) id) board)))

(defn calculate-iced-pieces
  "Returns set of piece IDs that are successfully iced.
   Per Icehouse rules: a defender is iced when total attacker pips > defender pips"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [iced target-id attackers]
       (let [defender (find-piece-by-id board target-id)
             defender-pips (get pips (:size defender) 0)
             attacker-pips (attack-strength attackers)]
         (if (and defender (> attacker-pips defender-pips))
           (conj iced target-id)
           iced)))
     #{}
     attacks)))

(defn calculate-scores [game]
  (let [board (:board game)
        iced (calculate-iced-pieces board)]
    (reduce
     (fn [scores piece]
       ;; Only standing (defending) pieces that aren't iced score points
       (if (or (= (:orientation piece) :pointing)
               (contains? iced (:id piece)))
         scores
         (let [points (get pips (:size piece) 0)]
           (update scores (:player-id piece) (fnil + 0) points))))
     {}
     board)))

(defn game-over? [game]
  (every? (fn [[_ player]]
            (every? zero? (vals (:pieces player))))
          (:players game)))

(defn handle-place-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (str (hash channel))
        game (get @games room-id)
        player-colour (get-in game [:players player-id :colour])
        piece {:id (str (java.util.UUID/randomUUID))
               :player-id player-id
               :colour player-colour
               :x (:x msg)
               :y (:y msg)
               :size (keyword (:size msg))
               :orientation (keyword (:orientation msg))
               :angle (:angle msg)
               :target-id (:target-id msg)}
        error (when game (validate-placement game player-id piece))]
    (if (and game (nil? error))
      (do
        (swap! games update-in [room-id :board] conj piece)
        (swap! games update-in [room-id :players player-id :pieces (:size piece)] dec)
        (let [updated-game (get @games room-id)]
          (utils/broadcast-room! clients room-id
                                 {:type "piece-placed"
                                  :piece piece
                                  :game updated-game})
          (when (game-over? updated-game)
            (utils/broadcast-room! clients room-id
                                   {:type "game-over"
                                    :scores (calculate-scores updated-game)}))))
      (utils/send-msg! channel {:type "error" :message (or error "Invalid game state")}))))

(defn start-game! [room-id players]
  (swap! games assoc room-id (create-game room-id players)))
