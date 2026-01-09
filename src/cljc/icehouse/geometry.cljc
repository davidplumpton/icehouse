(ns icehouse.geometry
  "Shared geometry functions for Icehouse game.
   Used by both backend (Clojure) and frontend (ClojureScript).")

;; =============================================================================
;; Math Helpers (platform-specific)
;; =============================================================================

(defn cos [angle]
  #?(:clj (Math/cos angle)
     :cljs (js/Math.cos angle)))

(defn sin [angle]
  #?(:clj (Math/sin angle)
     :cljs (js/Math.sin angle)))

(defn sqrt [x]
  #?(:clj (Math/sqrt x)
     :cljs (js/Math.sqrt x)))

(defn math-abs [x]
  #?(:clj (Math/abs x)
     :cljs (js/Math.abs x)))

(defn atan2 [y x]
  #?(:clj (Math/atan2 y x)
     :cljs (js/Math.atan2 y x)))

;; =============================================================================
;; Constants
;; =============================================================================

(def pips
  "Points per piece size (pip values)"
  {:small 1 :medium 2 :large 3})

(def piece-sizes
  "Piece sizes in pixels (base width). Small height = large base."
  {:small 40 :medium 50 :large 60})

(def default-piece-size
  "Fallback for unknown piece sizes"
  40)

(def tip-offset-ratio
  "Triangle tip extends this ratio * base-size from center"
  0.75)

(def parallel-threshold
  "Threshold for detecting parallel lines in ray casting"
  0.0001)

;; =============================================================================
;; Piece Helpers
;; =============================================================================

(defn piece-pips
  "Get pip value for a piece (1 for small, 2 for medium, 3 for large).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (get pips (keyword (:size piece)) 0)
    0))

(defn standing?
  "Check if a piece's orientation is :standing"
  [piece]
  (= (keyword (:orientation piece)) :standing))

(defn pointing?
  "Check if a piece's orientation is :pointing"
  [piece]
  (= (keyword (:orientation piece)) :pointing))

;; =============================================================================
;; Core Geometry Functions
;; =============================================================================

(defn rotate-point
  "Rotate point [x y] around origin by angle (radians)"
  [[x y] angle]
  (let [cos-a (cos angle)
        sin-a (sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn piece-vertices
  "Get vertices of a piece in world coordinates.
   Returns nil if piece is nil or missing required coordinates."
  [{:keys [x y size orientation angle] :as piece}]
  (when (and piece x y)
    (let [base-size (get piece-sizes (keyword size) default-piece-size)
          half (/ base-size 2)
          ;; All pieces can rotate - the angle affects collision detection
          effective-angle (or angle 0)
          local-verts (if (standing? piece)
                        ;; Standing: square (axis-aligned)
                        [[(- half) (- half)]
                         [half (- half)]
                         [half half]
                         [(- half) half]]
                        ;; Pointing: triangle (3:2 length:base ratio)
                        (let [half-width (* base-size tip-offset-ratio)]
                          [[half-width 0]
                           [(- half-width) (- half)]
                           [(- half-width) half]]))]
      (mapv (fn [[lx ly]]
              (let [[rx ry] (rotate-point [lx ly] effective-angle)]
                [(+ x rx) (+ y ry)]))
            local-verts))))

(defn edge-normal
  "Get perpendicular normal vector for edge from v1 to v2"
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        len (sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len)
      [1 0]
      [(/ (- dy) len) (/ dx len)])))

(defn polygon-axes
  "Get all edge normals for a polygon (for SAT collision)"
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

(defn point-in-polygon?
  "Check if point [px py] is inside polygon (ray casting algorithm)"
  [[px py] vertices]
  (let [n (count vertices)]
    (loop [i 0
           inside false]
      (if (>= i n)
        inside
        (let [j (mod (dec (+ i n)) n)
              [xi yi] (nth vertices i)
              [xj yj] (nth vertices j)
              intersect (and (not= (> yi py) (> yj py))
                             (< px (+ (/ (* (- xj xi) (- py yi))
                                         (- yj yi))
                                      xi)))]
          (recur (inc i) (if intersect (not inside) inside)))))))

;; =============================================================================
;; Distance Functions
;; =============================================================================

(defn distance
  "Calculate distance between two points"
  [[x1 y1] [x2 y2]]
  (sqrt (+ (* (- x2 x1) (- x2 x1))
           (* (- y2 y1) (- y2 y1)))))

(defn piece-center
  "Get the center point of a piece. Returns nil if piece is nil or missing coordinates."
  [piece]
  (when (and piece (:x piece) (:y piece))
    [(:x piece) (:y piece)]))

(defn point-to-segment-distance
  "Calculate minimum distance from point p to line segment [a b]"
  [[px py] [[ax ay] [bx by]]]
  (let [abx (- bx ax)
        aby (- by ay)
        apx (- px ax)
        apy (- py ay)
        ab-len-sq (+ (* abx abx) (* aby aby))
        t (if (zero? ab-len-sq)
            0
            (max 0 (min 1 (/ (+ (* apx abx) (* apy aby)) ab-len-sq))))
        cx (+ ax (* t abx))
        cy (+ ay (* t aby))]
    (sqrt (+ (* (- px cx) (- px cx))
             (* (- py cy) (- py cy))))))

(defn point-to-polygon-distance
  "Calculate minimum distance from point to polygon (nearest edge)"
  [point vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (apply min (map #(point-to-segment-distance point %) edges))))

;; =============================================================================
;; Attack Geometry
;; =============================================================================

(defn attack-direction
  "Get the unit direction vector for an attacking piece"
  [piece]
  (let [angle (or (:angle piece) 0)]
    [(cos angle) (sin angle)]))

(defn attacker-tip
  "Get the tip position of a pointing piece (where the attack ray originates).
   Returns nil if piece is nil or missing required coordinates."
  [piece]
  (when (and piece (:x piece) (:y piece))
    (let [base-size (get piece-sizes (keyword (:size piece)) default-piece-size)
          tip-offset (* base-size tip-offset-ratio)
          angle (or (:angle piece) 0)
          [dx dy] [(cos angle) (sin angle)]]
      [(+ (:x piece) (* dx tip-offset))
       (+ (:y piece) (* dy tip-offset))])))

(defn attack-range
  "Get the attack range for a piece (its height/length, not base width).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (let [base-size (get piece-sizes (keyword (:size piece)) default-piece-size)]
      (* 2 tip-offset-ratio base-size))
    0))

;; =============================================================================
;; Ray Casting
;; =============================================================================

(defn ray-segment-intersection-distance
  "Get the distance (parameter t) at which a ray from origin in direction dir
   intersects line segment [p1 p2]. Returns nil if no intersection."
  [[ox oy] [dx dy] [[p1x p1y] [p2x p2y]]]
  (let [sx (- p2x p1x)
        sy (- p2y p1y)
        denom (- (* dx sy) (* dy sx))]
    (when (>= (math-abs denom) parallel-threshold)
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

;; =============================================================================
;; Target Detection
;; =============================================================================

(defn in-front-of?
  "Check if attacker's pointing direction ray intersects the target piece"
  [attacker target]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)]
    (ray-intersects-polygon? tip dir target-verts)))

(defn within-range?
  "Check if target is within attack range of attacker.
   Per Icehouse rules, range is measured from the attacker's tip to the nearest
   point on the target piece."
  [attacker target]
  (let [tip (attacker-tip attacker)
        target-verts (piece-vertices target)
        dist (point-to-polygon-distance tip target-verts)
        rng (attack-range attacker)]
    (<= dist rng)))

(defn clear-line-of-sight?
  "Check if there are no pieces blocking the line between attacker and target.
   The target must be the first piece hit by the attack ray."
  [attacker target board]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)
        target-dist (ray-polygon-intersection-distance tip dir target-verts)
        other-pieces (remove #(or (= (:id %) (:id attacker))
                                  (= (:id %) (:id target)))
                             board)]
    (if (nil? target-dist)
      false
      (not-any? (fn [piece]
                  (let [piece-verts (piece-vertices piece)
                        piece-dist (ray-polygon-intersection-distance tip dir piece-verts)]
                    (and piece-dist (< piece-dist target-dist))))
                other-pieces))))

(defn calculate-angle
  "Calculate angle in radians from point (x1,y1) to (x2,y2)"
  [x1 y1 x2 y2]
  (atan2 (- y2 y1) (- x2 x1)))
