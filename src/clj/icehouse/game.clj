(ns icehouse.game
  (:require [icehouse.messages :as msg]
             [icehouse.utils :as utils]
             [icehouse.storage :as storage]
             [icehouse.schema :as schema]
             [malli.core :as m]))

(defonce games (atom {}))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-game-state
  "Validate game state against schema, returns nil if valid or error message"
  [game]
  (if (m/validate schema/GameState game)
    nil
    (str "Invalid game state: " (m/explain schema/GameState game))))

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

;; Game duration in milliseconds (random between min and max)
(def game-duration-min-ms (* 2 60 1000))  ;; 2 minutes
(def game-duration-max-ms (* 5 60 1000))  ;; 5 minutes


;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn piece-pips
  "Get pip value for a piece (1 for small, 2 for medium, 3 for large).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (get pips (:size piece) 0)
    0))

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
  "Get vertices of a piece in world coordinates.
   Returns nil if piece is nil or missing required coordinates."
  [{:keys [x y size orientation angle] :as piece}]
  (when (and piece x y)
    (let [base-size (get piece-sizes size default-piece-size)
          half (/ base-size 2)
          ;; Standing pieces don't rotate - they're viewed from above and look the same at any angle
          ;; Only pointing pieces use the angle for their attack direction
          effective-angle (if (utils/standing? piece) 0 (or angle 0))
          ;; Local vertices relative to center
          local-verts (if (utils/standing? piece)
                        ;; Standing: square (axis-aligned, no rotation)
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
              (let [[rx ry] (rotate-point [lx ly] effective-angle)]
                [(+ x rx) (+ y ry)]))
            local-verts))))

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
  "Get the center point of a piece. Returns nil if piece is nil or missing coordinates."
  [piece]
  (when (and piece (:x piece) (:y piece))
    [(:x piece) (:y piece)]))

(defn attack-direction
  "Get the unit direction vector for an attacking piece"
  [piece]
  (let [angle (or (:angle piece) 0)]
    [(Math/cos angle) (Math/sin angle)]))

(defn attacker-tip
  "Get the tip position of a pointing piece (where the attack ray originates).
   Returns nil if piece is nil or missing required coordinates."
  [piece]
  (when (and piece (:x piece) (:y piece))
    (let [base-size (get piece-sizes (:size piece) default-piece-size)
          tip-offset (* base-size tip-offset-ratio)
          angle (or (:angle piece) 0)
          [dx dy] [(Math/cos angle) (Math/sin angle)]]
      [(+ (:x piece) (* dx tip-offset))
       (+ (:y piece) (* dy tip-offset))])))

(defn ray-segment-intersection-distance
  "Get the distance (parameter t) at which a ray from origin in direction dir
   intersects line segment [p1 p2]. Returns nil if no intersection."
  [[ox oy] [dx dy] [[p1x p1y] [p2x p2y]]]
  (let [sx (- p2x p1x)
        sy (- p2y p1y)
        denom (- (* dx sy) (* dy sx))]
    (when (>= (Math/abs denom) parallel-threshold)
      (let [ox-p1x (- p1x ox)
            oy-p1y (- p1y oy)
            t (/ (- (* ox-p1x sy) (* oy-p1y sx)) denom)
            u (/ (- (* ox-p1x dy) (* oy-p1y dx)) denom)]
        (when (and (>= t 0) (>= u 0) (<= u 1))
          t)))))

(defn ray-segment-intersection?
  "Check if a ray from origin in direction dir intersects line segment [p1 p2]."
  [origin direction segment]
  (some? (ray-segment-intersection-distance origin direction segment)))

(defn ray-polygon-intersection-distance
  "Get the minimum distance at which a ray hits any edge of a polygon.
   Returns nil if no intersection."
  [origin direction vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))
        distances (keep #(ray-segment-intersection-distance origin direction %) edges)]
    (when (seq distances)
      (apply min distances))))

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

(defn clear-line-of-sight?
  "Check if there are no pieces blocking the line between attacker and target.
   The target must be the first piece hit by the attack ray."
  [attacker target board]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)
        target-dist (ray-polygon-intersection-distance tip dir target-verts)
        ;; Get all pieces except attacker and target
        other-pieces (remove #(or (= (:id %) (:id attacker))
                                  (= (:id %) (:id target)))
                             board)]
    ;; If we can't hit the target, line of sight is blocked (shouldn't happen if in-front-of? passed)
    (if (nil? target-dist)
      false
      ;; Check if any other piece is hit before the target
      (not-any? (fn [piece]
                  (let [piece-verts (piece-vertices piece)
                        piece-dist (ray-polygon-intersection-distance tip dir piece-verts)]
                    ;; If a piece is hit before the target, line of sight is blocked
                    (and piece-dist (< piece-dist target-dist))))
                other-pieces))))

(defn attack-range
  "Get the attack range for a piece (its height/length, not base width).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (let [base-size (get piece-sizes (:size piece) default-piece-size)]
      ;; Height = 2 * tip-offset-ratio * base-size (tip extends 0.75 * base from center in each direction)
      (* 2 tip-offset-ratio base-size))
    0))

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
   Returns true if the target is a different-coloured standing piece in the attack path.
   Uses colour-based validation so captured pieces attack based on their original colour.
   Falls back to player-id comparison if colours aren't set."
  [attacker target]
  (let [;; Use colour comparison if both pieces have colours, otherwise fall back to player-id
        is-opponent (if (and (:colour attacker) (:colour target))
                      (not= (:colour target) (:colour attacker))
                      (not= (utils/normalize-player-id (:player-id target))
                            (utils/normalize-player-id (:player-id attacker))))
        is-standing (utils/standing? target)]
    (if (and is-opponent is-standing)
      ;; Only check in-front-of? for standing opponent pieces
      (in-front-of? attacker target)
      false)))

(defn valid-target?
  "Check if target is a valid attack target for the attacker.
   Per Icehouse rules, can only target standing (defending) opponent pieces.
   Also checks that no other pieces are blocking the line of sight."
  [attacker target board]
  (and (potential-target? attacker target)
       (within-range? attacker target)
       (clear-line-of-sight? attacker target board)))

(defn find-potential-targets
  "Find all targets in the attack trajectory (ignoring range)"
  [attacker board]
  (filter #(potential-target? attacker %) board))

(defn has-potential-target?
  "Check if an attacking piece has at least one target in its trajectory"
  [piece board]
  (seq (find-potential-targets piece board)))

(defn find-valid-targets
  "Find all valid targets for an attacking piece"
  [attacker board]
  (filter #(valid-target? attacker % board) board))

(defn has-valid-target?
  "Check if an attacking piece has at least one valid target"
  [piece board]
  (seq (find-valid-targets piece board)))

(defn find-targets-in-range
  "Find all targets that are in trajectory and range (ignoring line of sight)"
  [attacker board]
  (filter #(and (potential-target? attacker %)
                (within-range? attacker %))
          board))

(defn has-target-in-range?
  "Check if an attacking piece has at least one target in range (ignoring line of sight)"
  [piece board]
  (seq (find-targets-in-range piece board)))

(defn has-blocked-target?
  "Check if an attacking piece has a target in range that is blocked by another piece"
  [piece board]
  (let [in-range (find-targets-in-range piece board)]
    (and (seq in-range)
         (not-any? #(clear-line-of-sight? piece % board) in-range))))

(defn find-closest-target
  "Find the closest valid target for an attacking piece"
  [piece board]
  (let [targets (find-valid-targets piece board)]
    (when (seq targets)
      (->> targets
           (map (fn [t] {:target t
                         :dist (distance (piece-center piece) (piece-center t))}))
           (sort-by :dist)
           first
           :target))))

(defn random-game-duration
  "Generate a random game duration between min and max"
  []
  (+ game-duration-min-ms
     (rand-int (- game-duration-max-ms game-duration-min-ms))))

(defn create-game
  "Create a new game with the given options.
   Options: {:icehouse-rule bool, :timer-enabled bool, :timer-duration :random|ms}"
  ([room-id players]
   (create-game room-id players {}))
  ([room-id players options]
   (let [now (System/currentTimeMillis)
         timer-enabled (get options :timer-enabled true)
         timer-duration (get options :timer-duration :random)
         duration (cond
                    (not timer-enabled) nil
                    (= timer-duration :random) (random-game-duration)
                    (number? timer-duration) timer-duration
                    :else (random-game-duration))]
     {:game-id (str (java.util.UUID/randomUUID))
      :room-id room-id
      :players (into {} (map (fn [p] [(:id p) {:name (:name p)
                                               :colour (:colour p)
                                               :pieces initial-piece-counts
                                               :captured []}])
                             players))
      :board []
      :moves []
      :options options  ;; Store options for later use (e.g., icehouse rule)
      :started-at now
      :ends-at (when duration (+ now duration))})))

(defn record-move!
  "Append a move to the game's move history"
  [room-id move]
  (when-let [game (get @games room-id)]
    (let [elapsed (- (System/currentTimeMillis) (:started-at game))]
      (swap! games update-in [room-id :moves] conj
             (assoc move
                    :timestamp (System/currentTimeMillis)
                    :elapsed-ms elapsed)))))

(defn count-captured-by-size
  "Count captured pieces of a given size"
  [captured size]
  (count (filter (utils/by-size size) captured)))

(defn get-first-captured-by-size
  "Get the first captured piece of a given size, or nil if none"
  [captured size]
  (first (filter (utils/by-size size) captured)))

(defn remove-first-captured
  "Remove the first captured piece of the given size from the list"
  [captured size]
  (let [idx (first (keep-indexed #(when ((utils/by-size size) %2) %1) captured))]
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
         is-attacking? (utils/pointing? piece)
         ;; Ensure piece has player-id and colour for validation
         ;; For captured pieces, get the original colour; otherwise use player's colour
         piece-colour (if using-captured?
                        (let [cap-piece (get-first-captured-by-size (:captured player) size)]
                          (or (:colour cap-piece) (:colour player)))
                        (:colour player))
         piece-with-owner (assoc piece
                                 :player-id player-id
                                 :colour (or (:colour piece) piece-colour))]
     (cond
       (not (pos? remaining))
       (if using-captured?
         "No captured pieces of that size remaining"
         "No pieces of that size remaining")

       (not (within-play-area? piece-with-owner))
       "Piece must be placed within the play area"

       (intersects-any-piece? piece-with-owner board)
       "Piece would overlap with existing piece"

       (and is-attacking? (not (has-potential-target? piece-with-owner board)))
       "Attacking piece must be pointed at an opponent's piece"

       (and is-attacking? (not (has-target-in-range? piece-with-owner board)))
       "Target is out of range"

       (and is-attacking? (has-blocked-target? piece-with-owner board))
       "Another piece is blocking the line of attack"

       :else nil))))

(defn valid-placement? [game player-id piece]
  (nil? (validate-placement game player-id piece)))

(defn attackers-by-target
  "Returns a map of target-id -> list of attackers targeting that piece"
  [board]
  (let [pointing-pieces (filter #(and (utils/pointing? %)
                                      (:target-id %))
                                board)]
    (group-by :target-id pointing-pieces)))

(defn attack-strength
  "Sum of pip values of all attackers targeting a piece"
  [attackers]
  (reduce + (map piece-pips attackers)))

(defn find-piece-by-id [board id]
  (first (filter (utils/by-id id) board)))

(defn calculate-attack-stats
  "Calculate attacker statistics for all targets on the board.
   Returns map of target-id -> {:defender piece :attackers [...] :attacker-pips sum}"
  [board]
  (let [attacks (attackers-by-target board)]
    (reduce-kv
     (fn [stats target-id attackers]
       (if-let [defender (find-piece-by-id board target-id)]
         (assoc stats target-id {:defender defender
                                 :attackers attackers
                                 :attacker-pips (attack-strength attackers)})
         stats))
     {}
     attacks)))

(defn calculate-iced-pieces
  "Returns set of piece IDs that are successfully iced.
   Per Icehouse rules: a defender is iced when total attacker pips > defender pips"
  [board]
  (let [stats (calculate-attack-stats board)]
    (reduce-kv
     (fn [iced target-id {:keys [defender attacker-pips]}]
       (if (> attacker-pips (piece-pips defender))
         (conj iced target-id)
         iced))
     #{}
     stats)))

(defn calculate-over-ice
  "Returns a map of defender-id -> {:excess pips :attackers [...] :defender-owner player-id}
   for each over-iced defender. Excess = attacker-pips - (defender-pips + 1)"
  [board]
  (let [stats (calculate-attack-stats board)]
    (reduce-kv
     (fn [result target-id {:keys [defender attackers attacker-pips]}]
       (let [defender-pips (piece-pips defender)
             ;; Minimum to ice is defender-pips + 1, excess is anything beyond that
             excess (- attacker-pips (+ defender-pips 1))]
         (if (and (> attacker-pips defender-pips) (pos? excess))
           (assoc result target-id {:excess excess
                                    :attackers attackers
                                    :defender-owner (utils/normalize-player-id (:player-id defender))})
           result)))
     {}
     stats)))

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
  (count (filter (utils/by-player-id player-id) board)))

(defn player-defenders
  "Get all standing (defender) pieces for a player"
  [board player-id]
  (filter #(and ((utils/by-player-id player-id) %)
                (utils/standing? %))
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
  "Returns set of player-ids who are 'in the Icehouse'.
   If icehouse-rule option is false, returns empty set."
  ([board]
   (calculate-icehouse-players board {}))
  ([board options]
   (if (false? (get options :icehouse-rule))
     #{}  ;; Icehouse rule disabled
     (let [iced (calculate-iced-pieces board)
           player-ids (distinct (map #(utils/normalize-player-id (:player-id %)) board))]
       (set (filter #(in-icehouse? board iced %) player-ids))))))

(defn calculate-scores [game]
  (let [board (:board game)
        options (get game :options {})
        iced (calculate-iced-pieces board)
        icehouse-players (calculate-icehouse-players board options)]
    (reduce
     (fn [scores piece]
       (let [player-id (utils/normalize-player-id (:player-id piece))]
         ;; Players in the Icehouse get zero (handled by not adding points)
         (if (contains? icehouse-players player-id)
           (assoc scores player-id 0)
           ;; Only standing (defending) pieces that aren't iced score points
           (if (or (utils/pointing? piece)
                   (contains? iced (:id piece)))
             scores
             (let [points (piece-pips piece)]
               (update scores player-id (fnil + 0) points))))))
     {}
     board)))

(defn all-pieces-placed? [game]
  "Check if all players have placed all their pieces"
  (every? (fn [[_ player]]
            (every? zero? (vals (:pieces player))))
          (:players game)))

(defn time-up? [game]
  "Check if the game time has expired"
  (when-let [ends-at (:ends-at game)]
    (>= (System/currentTimeMillis) ends-at)))

(defn game-over? [game]
  "Game ends when all pieces are placed OR time runs out"
  (or (all-pieces-placed? game)
      (time-up? game)))

(defn build-game-record
  "Build a complete game record from current game state for persistence"
  [game end-reason]
  (let [now (System/currentTimeMillis)
        options (get game :options {})
        scores (calculate-scores game)
        icehouse-players (calculate-icehouse-players (:board game) options)
        winner (when (seq scores)
                 (key (apply max-key val scores)))]
    {:version 1
     :game-id (:game-id game)
     :room-id (:room-id game)
     :players (into {}
                    (map (fn [[pid pdata]]
                           [pid (select-keys pdata [:name :colour])])
                         (:players game)))
     :started-at (:started-at game)
     :ended-at now
     :duration-ms (- now (:started-at game))
     :end-reason end-reason
     :moves (:moves game)
     :final-board (:board game)
     :final-scores scores
     :icehouse-players (vec icehouse-players)
     :winner winner}))

(defn construct-piece-for-placement
  "Construct the piece map for placement, handling auto-targeting and colour assignment"
  [game player-id msg]
  (let [player-colour (get-in game [:players player-id :colour])
        using-captured? (boolean (:captured msg))
        piece-size (keyword (:size msg))
        ;; For captured pieces, use the original colour; for regular pieces, use player's colour
        piece-colour (if using-captured?
                       (let [captured (get-in game [:players player-id :captured])
                             cap-piece (get-first-captured-by-size captured piece-size)]
                         (or (:colour cap-piece) player-colour))
                       player-colour)
        base-piece {:id (str (java.util.UUID/randomUUID))
                    :player-id player-id
                    :colour piece-colour
                    :x (:x msg)
                    :y (:y msg)
                    :size piece-size
                    :orientation (keyword (:orientation msg))
                    :angle (:angle msg)
                    :target-id (:target-id msg)}]
    ;; Auto-assign target-id for attacking pieces if not provided
    (if (and (utils/pointing? base-piece)
             (nil? (:target-id base-piece))
             game)
      (if-let [target (find-closest-target base-piece (:board game))]
        (assoc base-piece :target-id (:id target))
        base-piece)
      base-piece)))

(defn apply-placement!
  "Update game state with placed piece and decrement stash/captured counts"
  [room-id player-id piece using-captured?]
  (swap! games (fn [games-map]
                 (let [game-update (-> games-map
                                       (update-in [room-id :board] conj piece))]
                   (if using-captured?
                     (update-in game-update [room-id :players player-id :captured]
                                remove-first-captured (:size piece))
                     (update-in game-update [room-id :players player-id :pieces (:size piece)] dec))))))

(defn handle-post-placement!
  "Handle side effects after placement: recording, broadcasting, game over check"
  [clients room-id player-id piece using-captured?]
  ;; Record the move for replay
  (record-move! room-id {:type :place-piece
                         :player-id player-id
                         :piece piece
                         :using-captured? using-captured?})
  (let [updated-game (get @games room-id)]
    (utils/broadcast-room! clients room-id
                           {:type msg/piece-placed
                            :piece piece
                            :game updated-game})
    (when (game-over? updated-game)
      (let [board (:board updated-game)
            options (get updated-game :options {})
            over-ice (calculate-over-ice board)
            icehouse-players (calculate-icehouse-players board options)
            end-reason (if (all-pieces-placed? updated-game)
                         :all-pieces-placed
                         :time-up)
            record (build-game-record updated-game end-reason)]
        ;; Save the game record
        (storage/save-game-record! record)
        (utils/broadcast-room! clients room-id
                               {:type msg/game-over
                                :game-id (:game-id updated-game)
                                :scores (calculate-scores updated-game)
                                :over-ice over-ice
                                :icehouse-players (vec icehouse-players)})))))

(defn handle-place-piece [clients channel msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)
        using-captured? (boolean (:captured msg))
        piece (when game (construct-piece-for-placement game player-id msg))
        error (when piece (validate-placement game player-id piece using-captured?))]
    (if (and piece (nil? error))
      (do
        (apply-placement! room-id player-id piece using-captured?)
        (handle-post-placement! clients room-id player-id piece using-captured?))
      (utils/send-msg! channel {:type msg/error :message (or error "Invalid game state")}))))

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

      (not (utils/pointing? piece))
      "Can only capture attacking pieces"

      (not (:target-id piece))
      "Piece has no target"

      (nil? (get over-ice (:target-id piece)))
      "Target is not over-iced"

      (not= (utils/normalize-player-id (:defender-owner (get over-ice (:target-id piece))))
            (utils/normalize-player-id player-id))
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
               (fn [board] (vec (remove (utils/by-id piece-id) board))))
        ;; Add to capturing player's captured stash with original colour
        (swap! games update-in [room-id :players player-id :captured]
               conj {:size piece-size :colour original-colour})
        ;; Record the capture move for replay
        (record-move! room-id {:type :capture-piece
                               :player-id player-id
                               :piece-id piece-id
                               :captured-piece {:size piece-size :colour original-colour}})
        ;; Broadcast updated game state
        (let [updated-game (get @games room-id)]
          (utils/broadcast-room! clients room-id
                                 {:type msg/piece-captured
                                  :piece-id piece-id
                                  :captured-by player-id
                                  :game updated-game})))
      (utils/send-msg! channel {:type msg/error :message (or error "Invalid capture")}))))

(defn all-players-finished?
  "Check if all players have pressed finish"
  [game]
  (let [player-ids (set (keys (:players game)))
        finished (or (:finished game) #{})]
    (and (seq player-ids)
         (= player-ids finished))))

(defn end-game!
  "End the game, calculate scores, save record, and broadcast game-over"
  [clients room-id end-reason]
  (when-let [game (get @games room-id)]
    (let [board (:board game)
          options (get game :options {})
          over-ice (calculate-over-ice board)
          icehouse-players (calculate-icehouse-players board options)
          record (build-game-record game end-reason)]
      (storage/save-game-record! record)
      (utils/broadcast-room! clients room-id
                             {:type msg/game-over
                              :game-id (:game-id game)
                              :scores (calculate-scores game)
                              :over-ice over-ice
                              :icehouse-players (vec icehouse-players)}))))

(defn handle-finish
  "Handle a player pressing the finish button"
  [clients channel _msg]
  (let [room-id (get-in @clients [channel :room-id])
        player-id (player-id-from-channel channel)
        game (get @games room-id)]
    (when game
      ;; Add player to finished set
      (swap! games update-in [room-id :finished] (fnil conj #{}) player-id)
      (let [updated-game (get @games room-id)]
        ;; Broadcast that this player finished
        (utils/broadcast-room! clients room-id
                               {:type msg/player-finished
                                :player-id player-id
                                :game updated-game})
        ;; Check if all players have finished
        (when (all-players-finished? updated-game)
          (end-game! clients room-id :all-players-finished))))))

(defn start-game!
  "Start a new game with the given players and options"
  ([room-id players]
   (start-game! room-id players {}))
  ([room-id players options]
   (swap! games assoc room-id (create-game room-id players options))))

;; =============================================================================
;; Replay Handlers
;; =============================================================================

(defn handle-list-games
  "Send list of saved game record IDs to client"
  [channel]
  (utils/send-msg! channel
                   {:type msg/game-list
                    :games (storage/list-game-records)}))

(defn handle-load-game
  "Load and send a game record to client"
  [channel msg]
  (if-let [record (storage/load-game-record (:game-id msg))]
    (utils/send-msg! channel
                     {:type msg/game-record
                      :record record})
    (utils/send-msg! channel
                     {:type msg/error
                      :message "Game not found"})))
