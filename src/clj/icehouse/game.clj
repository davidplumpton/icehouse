(ns icehouse.game
  (:require [icehouse.utils :as utils]))

(defonce games (atom {}))

;; =============================================================================
;; Game Constants
;; =============================================================================

;; Points per piece size (pip values)
(def pips {:small 1 :medium 2 :large 3})

;; Piece sizes in pixels (must match frontend)
;; Sized so small height = large base, medium is halfway between
(def piece-sizes {:small 40 :medium 50 :large 60})
(def default-piece-size 40)  ;; Fallback for unknown piece sizes

;; Play area dimensions (must match frontend canvas)
(def play-area-width 1000)
(def play-area-height 750)

;; Geometry constants
(def tip-offset-ratio 0.75)       ;; Triangle tip extends 0.75 * base-size from center
(def parallel-threshold 0.0001)   ;; Threshold for detecting parallel lines in ray casting

;; Game rules constants
(def icehouse-min-pieces 8)       ;; Minimum pieces to trigger icehouse rule
(def initial-piece-counts {:small 5 :medium 5 :large 5})

;; Message types (for WebSocket communication)
(def msg-types
  {:piece-placed "piece-placed"
   :game-over "game-over"
   :piece-captured "piece-captured"
   :error "error"})

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn piece-pips
  "Get pip value for a piece (1 for small, 2 for medium, 3 for large)"
  [piece]
  (get pips (:size piece) 0))

(defn player-id-from-channel
  "Extract player ID from a WebSocket channel"
  [channel]
  (str (hash channel)))

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
  (let [base-size (get piece-sizes size default-piece-size)
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
                      (let [half-width (* base-size tip-offset-ratio)]
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
  "Check if two 1D projections [min1 max1] and [min2 max2] overlap.
   Uses strict inequality so pieces that are merely touching are allowed."
  [[min1 max1] [min2 max2]]
  (and (< min1 max2) (< min2 max1)))

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

(defn within-play-area?
  "Check if all vertices of a piece are within the play area bounds.
   Returns true if piece has no position (for backwards compatibility with tests)."
  [piece]
  (if (and (:x piece) (:y piece))
    (let [vertices (piece-vertices piece)]
      (every? (fn [[x y]]
                (and (>= x 0) (<= x play-area-width)
                     (>= y 0) (<= y play-area-height)))
              vertices))
    true))

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
  (let [base-size (get piece-sizes (:size piece) default-piece-size)
        tip-offset (* base-size tip-offset-ratio)
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
    (if (< (Math/abs denom) parallel-threshold)
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
  (get piece-sizes (:size piece) default-piece-size))

(defn point-to-segment-distance
  "Calculate minimum distance from point p to line segment [a b]"
  [[px py] [[ax ay] [bx by]]]
  (let [;; Vector from a to b
        abx (- bx ax)
        aby (- by ay)
        ;; Vector from a to p
        apx (- px ax)
        apy (- py ay)
        ;; Project ap onto ab, clamped to [0,1]
        ab-len-sq (+ (* abx abx) (* aby aby))
        t (if (zero? ab-len-sq)
            0
            (max 0 (min 1 (/ (+ (* apx abx) (* apy aby)) ab-len-sq))))
        ;; Closest point on segment
        cx (+ ax (* t abx))
        cy (+ ay (* t aby))]
    (Math/sqrt (+ (* (- px cx) (- px cx))
                  (* (- py cy) (- py cy))))))

(defn point-to-polygon-distance
  "Calculate minimum distance from point to polygon (nearest edge)"
  [point vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (apply min (map #(point-to-segment-distance point %) edges))))

(defn within-range?
  "Check if target is within attack range of attacker.
   Per Icehouse rules, range is measured from the attacker's tip to the nearest
   point on the target piece."
  [attacker target]
  (let [tip (attacker-tip attacker)
        target-verts (piece-vertices target)
        dist (point-to-polygon-distance tip target-verts)
        range (attack-range attacker)]
    (<= dist range)))

(defn potential-target?
  "Check if target could be attacked based on trajectory only (ignoring range).
   Returns true if the target is an opponent's standing piece in the attack path."
  [attacker target attacker-player-id]
  (and (not= (:player-id target) attacker-player-id)  ;; Different player
       (= (:orientation target) :standing)             ;; Must be a defender (standing)
       (in-front-of? attacker target)))                ;; In trajectory

(defn valid-target?
  "Check if target is a valid attack target for the attacker.
   Per Icehouse rules, can only target standing (defending) opponent pieces."
  [attacker target attacker-player-id]
  (and (potential-target? attacker target attacker-player-id)
       (within-range? attacker target)))               ;; Within range

(defn find-potential-targets
  "Find all targets in the attack trajectory (ignoring range)"
  [attacker player-id board]
  (filter #(potential-target? attacker % player-id) board))

(defn has-potential-target?
  "Check if an attacking piece has at least one target in its trajectory"
  [piece player-id board]
  (seq (find-potential-targets piece player-id board)))

(defn find-valid-targets
  "Find all valid targets for an attacking piece"
  [attacker player-id board]
  (filter #(valid-target? attacker % player-id) board))

(defn has-valid-target?
  "Check if an attacking piece has at least one valid target"
  [piece player-id board]
  (seq (find-valid-targets piece player-id board)))

(defn find-closest-target
  "Find the closest valid target for an attacking piece"
  [piece player-id board]
  (let [targets (find-valid-targets piece player-id board)]
    (when (seq targets)
      (->> targets
           (map (fn [t] {:target t
                         :dist (distance (piece-center piece) (piece-center t))}))
           (sort-by :dist)
           first
           :target))))

(defn create-game [room-id players]
  {:room-id room-id
   :players (into {} (map (fn [p] [(:id p) {:name (:name p)
                                            :colour (:colour p)
                                            :pieces initial-piece-counts
                                            :captured []}])  ;; List of {:size :colour}
                          players))
   :board []
   :started-at (System/currentTimeMillis)})

(defn count-captured-by-size
  "Count captured pieces of a given size"
  [captured size]
  (count (filter #(= (:size %) size) captured)))

(defn remove-first-captured
  "Remove the first captured piece of the given size from the list"
  [captured size]
  (let [idx (first (keep-indexed #(when (= (:size %2) size) %1) captured))]
    (if idx
      (vec (concat (subvec captured 0 idx) (subvec captured (inc idx))))
      captured)))

(defn validate-placement
  "Validate piece placement, returns nil if valid or error message if invalid.
   If using-captured? is true, checks captured pieces instead of regular pieces."
  ([game player-id piece]
   (validate-placement game player-id piece false))
  ([game player-id piece using-captured?]
   (let [player (get-in game [:players player-id])
         size (:size piece)
         remaining (if using-captured?
                     (count-captured-by-size (:captured player) size)
                     (get-in player [:pieces size] 0))
         board (:board game)
         is-attacking? (= (:orientation piece) :pointing)]
     (cond
       (not (pos? remaining))
       (if using-captured?
         "No captured pieces of that size remaining"
         "No pieces of that size remaining")

       (not (within-play-area? piece))
       "Piece must be placed within the play area"

       (intersects-any-piece? piece board)
       "Piece would overlap with existing piece"

       (and is-attacking? (not (has-potential-target? piece player-id board)))
       "Attacking piece must be pointed at an opponent's piece"

       (and is-attacking? (not (has-valid-target? piece player-id board)))
       "Target is out of range"

       :else nil))))

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
  (reduce + (map piece-pips attackers)))

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
             defender-pips (piece-pips defender)
             attacker-pips (attack-strength attackers)]
         (if (and defender (> attacker-pips defender-pips))
           (conj iced target-id)
           iced)))
     #{}
     attacks)))

(defn calculate-over-ice
  "Returns a map of defender-id -> {:excess pips :attackers [...] :defender-owner player-id}
   for each over-iced defender. Excess = attacker-pips - (defender-pips + 1)"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [result target-id attackers]
       (let [defender (find-piece-by-id board target-id)
             defender-pips (piece-pips defender)
             attacker-pips (attack-strength attackers)
             ;; Minimum to ice is defender-pips + 1, excess is anything beyond that
             excess (- attacker-pips (+ defender-pips 1))]
         (if (and defender (> attacker-pips defender-pips) (pos? excess))
           (assoc result target-id {:excess excess
                                    :attackers attackers
                                    :defender-owner (:player-id defender)})
           result)))
     {}
     attacks)))

(defn capturable-attackers
  "Given over-ice info for a defender, returns attackers that could be captured.
   An attacker can be captured if its pip value <= remaining excess."
  [over-ice-info]
  (let [{:keys [excess attackers]} over-ice-info]
    ;; Sort by pip value ascending so smaller pieces can be captured first
    (->> attackers
         (map (fn [a] {:attacker a :pips (piece-pips a)}))
         (filter #(<= (:pips %) excess))
         (sort-by :pips))))

(defn pieces-placed-by-player
  "Count pieces placed by a player"
  [board player-id]
  (count (filter #(= (:player-id %) player-id) board)))

(defn player-defenders
  "Get all standing (defender) pieces for a player"
  [board player-id]
  (filter #(and (= (:player-id %) player-id)
                (= (:orientation %) :standing))
          board))

(defn in-icehouse?
  "Check if a player is 'in the Icehouse' - all defenders iced after playing 8+ pieces.
   Per official rules, this is an instant loss with zero score."
  [board iced-set player-id]
  (let [pieces-placed (pieces-placed-by-player board player-id)
        defenders (player-defenders board player-id)]
    (and (>= pieces-placed icehouse-min-pieces)            ;; Must have placed at least 8 pieces
         (seq defenders)                                    ;; Must have at least one defender
         (every? #(contains? iced-set (:id %)) defenders)))) ;; All defenders are iced

(defn calculate-icehouse-players
  "Returns set of player-ids who are 'in the Icehouse'"
  [board]
  (let [iced (calculate-iced-pieces board)
        player-ids (distinct (map :player-id board))]
    (set (filter #(in-icehouse? board iced %) player-ids))))

(defn calculate-scores [game]
  (let [board (:board game)
        iced (calculate-iced-pieces board)
        icehouse-players (calculate-icehouse-players board)]
    (reduce
     (fn [scores piece]
       (let [player-id (:player-id piece)]
         ;; Players in the Icehouse get zero (handled by not adding points)
         (if (contains? icehouse-players player-id)
           (assoc scores player-id 0)
           ;; Only standing (defending) pieces that aren't iced score points
           (if (or (= (:orientation piece) :pointing)
                   (contains? iced (:id piece)))
             scores
             (let [points (piece-pips piece)]
               (update scores player-id (fnil + 0) points))))))
     {}
     board)))

(defn game-over? [game]
  (every? (fn [[_ player]]
            (every? zero? (vals (:pieces player))))
          (:players game)))

(defn handle-place-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        player-colour (get-in game [:players player-id :colour])
        using-captured? (boolean (:captured msg))
        base-piece {:id (str (java.util.UUID/randomUUID))
                    :player-id player-id
                    :colour player-colour
                    :x (:x msg)
                    :y (:y msg)
                    :size (keyword (:size msg))
                    :orientation (keyword (:orientation msg))
                    :angle (:angle msg)
                    :target-id (:target-id msg)}
        ;; Auto-assign target-id for attacking pieces if not provided
        piece (if (and (= (:orientation base-piece) :pointing)
                       (nil? (:target-id base-piece))
                       game)
                (if-let [target (find-closest-target base-piece player-id (:board game))]
                  (assoc base-piece :target-id (:id target))
                  base-piece)
                base-piece)
        error (when game (validate-placement game player-id piece using-captured?))]
    (if (and game (nil? error))
      (do
        (swap! games update-in [room-id :board] conj piece)
        ;; Decrement from captured or regular pieces
        (if using-captured?
          (swap! games update-in [room-id :players player-id :captured]
                 remove-first-captured (:size piece))
          (swap! games update-in [room-id :players player-id :pieces (:size piece)] dec))
        (let [updated-game (get @games room-id)]
          (utils/broadcast-room! clients room-id
                                 {:type (:piece-placed msg-types)
                                  :piece piece
                                  :game updated-game})
          (when (game-over? updated-game)
            (let [board (:board updated-game)
                  over-ice (calculate-over-ice board)
                  icehouse-players (calculate-icehouse-players board)]
              (utils/broadcast-room! clients room-id
                                     {:type (:game-over msg-types)
                                      :scores (calculate-scores updated-game)
                                      :over-ice over-ice
                                      :icehouse-players (vec icehouse-players)})))))
      (utils/send-msg! channel {:type (:error msg-types) :message (or error "Invalid game state")}))))

(defn validate-capture
  "Validate that a piece can be captured by the player.
   Returns nil if valid, or error message if invalid."
  [game player-id piece-id]
  (let [board (:board game)
        piece (find-piece-by-id board piece-id)
        over-ice (calculate-over-ice board)]
    (cond
      (nil? piece)
      "Piece not found"

      (not= (:orientation piece) :pointing)
      "Can only capture attacking pieces"

      (not (:target-id piece))
      "Piece has no target"

      (nil? (get over-ice (:target-id piece)))
      "Target is not over-iced"

      (not= (:defender-owner (get over-ice (:target-id piece))) player-id)
      "You can only capture attackers targeting your own pieces"

      (> (piece-pips piece) (:excess (get over-ice (:target-id piece))))
      "Attacker's pip value exceeds remaining excess"

      :else nil)))

(defn handle-capture-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        piece-id (:piece-id msg)
        error (when game (validate-capture game player-id piece-id))]
    (if (and game (nil? error))
      (let [piece (find-piece-by-id (:board game) piece-id)
            piece-size (:size piece)
            ;; Get the original owner's colour
            original-owner (:player-id piece)
            original-colour (get-in game [:players original-owner :colour])]
        ;; Remove piece from board
        (swap! games update-in [room-id :board]
               (fn [board] (vec (remove #(= (:id %) piece-id) board))))
        ;; Add to capturing player's captured stash with original colour
        (swap! games update-in [room-id :players player-id :captured]
               conj {:size piece-size :colour original-colour})
        ;; Broadcast updated game state
        (let [updated-game (get @games room-id)]
          (utils/broadcast-room! clients room-id
                                 {:type (:piece-captured msg-types)
                                  :piece-id piece-id
                                  :captured-by player-id
                                  :game updated-game})))
      (utils/send-msg! channel {:type (:error msg-types) :message (or error "Invalid capture")}))))

(defn start-game! [room-id players]
  (swap! games assoc room-id (create-game room-id players)))
