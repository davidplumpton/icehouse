(ns icehouse.geometry
  "Shared geometry functions for Icehouse game.
   Used by both backend (Clojure) and frontend (ClojureScript)."
  (:require [icehouse.constants :as const]))

;; =============================================================================
;; Math Helpers (platform-specific)
;; =============================================================================

(defn cos
  "Calculate cosine of angle (radians)"
  [angle]
  #?(:clj (Math/cos angle)
     :cljs (js/Math.cos angle)))

(defn sin
  "Calculate sine of angle (radians)"
  [angle]
  #?(:clj (Math/sin angle)
     :cljs (js/Math.sin angle)))

(defn sqrt
  "Calculate square root of x"
  [x]
  #?(:clj (Math/sqrt x)
     :cljs (js/Math.sqrt x)))

(defn math-abs
  "Calculate absolute value of x"
  [x]
  #?(:clj (Math/abs x)
     :cljs (js/Math.abs x)))

(defn atan2
  "Calculate arc tangent of y/x"
  [y x]
  #?(:clj (Math/atan2 y x)
     :cljs (js/Math.atan2 y x)))

;; =============================================================================
;; Piece Helpers
;; =============================================================================

(defn piece-pips
  "Get pip value for a piece (1 for small, 2 for medium, 3 for large).
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (get const/pips (keyword (:size piece)) 0)
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
    (let [base-size (get const/piece-sizes (keyword size) const/default-piece-size)
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
                        (let [half-width (* base-size const/tip-offset-ratio)]
                          [[half-width 0]
                           [(- half-width) (- half)]
                           [(- half-width) half]]))]
      (mapv (fn [[lx ly]]
              (let [[rx ry] (rotate-point [lx ly] effective-angle)]
                [(+ x rx) (+ y ry)]))
            local-verts))))

;; ---------------------------------------------------------------------------
;; Separating Axis Theorem (SAT) Collision Detection
;;
;; SAT determines whether two convex polygons overlap by searching for a
;; "separating axis" — a line along which the projections (shadows) of the
;; two polygons do not overlap.  If such an axis exists the polygons are
;; disjoint; if no separating axis can be found, the polygons intersect.
;;
;; Key insight: for convex polygons it is sufficient to test only the axes
;; that are perpendicular (normal) to each edge of both polygons.  For a
;; triangle (3 edges) vs a square (4 edges) this means at most 7 axes.
;;
;; Algorithm steps:
;;   1. Collect candidate axes — the outward-facing normals of every edge
;;      of both polygons (polygon-axes).
;;   2. For each axis, project both polygons onto it, yielding 1-D intervals
;;      (project-polygon).
;;   3. If any axis yields non-overlapping intervals the polygons are
;;      separated and do not collide (projections-overlap?).
;;   4. If all axes show overlapping intervals, the polygons intersect.
;;
;; In Icehouse, pieces are either squares (standing) or triangles (pointing),
;; both of which are convex, so SAT applies directly.  Pieces may be rotated
;; to any angle so the test must work with arbitrary orientations.
;;
;; Edge case: when two pieces share an edge or vertex but do not overlap
;; (i.e. they are merely touching), strict inequality (< rather than <=) in
;; projections-overlap? treats them as non-colliding.  This lets players
;; place pieces right up against each other without triggering a collision.
;; ---------------------------------------------------------------------------

(defn edge-normal
  "Get the outward-facing unit normal for the edge from v1 to v2.
   The normal is computed by rotating the edge direction 90° counter-clockwise
   and normalising to unit length.  Returns [1 0] as a safe fallback for
   degenerate zero-length edges."
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        len (sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len)
      ;; Degenerate edge — return an arbitrary unit vector to avoid division
      ;; by zero.  This can only occur if two vertices are identical, which
      ;; should not happen with valid piece geometry.
      [1 0]
      [(/ (- dy) len) (/ dx len)])))

(defn polygon-axes
  "Collect the candidate separating axes for a convex polygon.
   Each axis is the outward-facing unit normal of one edge.  The function
   iterates over consecutive vertex pairs (wrapping around to close the
   polygon) and returns a vector of [nx ny] unit normals."
  [vertices]
  (let [n (count vertices)]
    (mapv (fn [i]
            (edge-normal (nth vertices i)
                         (nth vertices (mod (inc i) n))))
          (range n))))

(defn project-polygon
  "Project all vertices of a polygon onto a single axis [ax ay] using the
   dot product, and return the 1-D interval [min max] that the polygon
   occupies along that axis.  Comparing the intervals of two polygons on the
   same axis reveals whether they overlap in that direction."
  [vertices [ax ay]]
  (let [dots (map (fn [[x y]] (+ (* x ax) (* y ay))) vertices)]
    [(apply min dots) (apply max dots)]))

(defn projections-overlap?
  "Check if two 1-D intervals [min1 max1] and [min2 max2] overlap.
   Uses strict inequality (< not <=) so that intervals sharing only an
   endpoint are considered non-overlapping.  This means pieces that merely
   touch edges are allowed — only genuine area overlap counts as collision."
  [[min1 max1] [min2 max2]]
  (and (< min1 max2) (< min2 max1)))

(defn polygons-intersect?
  "Test whether two convex polygons intersect using SAT.
   Collects candidate separating axes from both polygons and checks every
   axis for projection overlap.  Returns true only when no separating axis
   exists (i.e. projections overlap on every tested axis)."
  [verts1 verts2]
  (let [axes (concat (polygon-axes verts1) (polygon-axes verts2))]
    (every? (fn [axis]
              (projections-overlap?
               (project-polygon verts1 axis)
               (project-polygon verts2 axis)))
            axes)))

(defn pieces-intersect?
  "Check if two game pieces overlap.  Converts each piece to its world-space
   polygon (square for standing, triangle for pointing) via piece-vertices,
   then delegates to SAT-based polygons-intersect?."
  [piece1 piece2]
  (polygons-intersect?
   (piece-vertices piece1)
   (piece-vertices piece2)))

(defn point-in-polygon?
  "Check if point [px py] is inside polygon using the ray casting algorithm.
   Works for any convex or concave polygon."
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
  "Calculate the minimum Euclidean distance from point p to line segment [a b]."
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
  "Calculate the minimum Euclidean distance from a point to the nearest edge 
   of a polygon."
  [point vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (apply min (map #(point-to-segment-distance point %) edges))))

;; =============================================================================
;; Attack Geometry
;; =============================================================================

(defn attack-direction
  "Get the unit direction vector for an attacking piece based on its angle."
  [piece]
  (let [angle (or (:angle piece) 0)]
    [(cos angle) (sin angle)]))

(defn attacker-tip
  "Get the tip position of a pointing piece (where the attack ray originates).
   Returns nil if piece is nil or missing required coordinates."
  [piece]
  (when (and piece (:x piece) (:y piece))
    (let [base-size (get const/piece-sizes (keyword (:size piece)) const/default-piece-size)
          tip-offset (* base-size const/tip-offset-ratio)
          angle (or (:angle piece) 0)
          [dx dy] [(cos angle) (sin angle)]]
      [(+ (:x piece) (* dx tip-offset))
       (+ (:y piece) (* dy tip-offset))])))

(defn attack-range
  "Get the attack range for a piece (its height/length, not base width).
   Attack range is twice the tip offset ratio times the base size.
   Returns 0 if piece is nil."
  [piece]
  (if piece
    (let [base-size (get const/piece-sizes (keyword (:size piece)) const/default-piece-size)]
      (* 2 const/tip-offset-ratio base-size))
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
    (when (>= (math-abs denom) const/parallel-threshold)
      (let [ox-p1x (- p1x ox)
            oy-p1y (- p1y oy)
            t (/ (- (* ox-p1x sy) (* oy-p1y sx)) denom)
            u (/ (- (* ox-p1x dy) (* oy-p1y dx)) denom)]
        (when (and (>= t 0) (>= u 0) (<= u 1))
          t)))))

(defn ray-segment-intersection?
  "Check if a ray from origin in direction dir intersects line segment [p1 p2].
   Returns true if the ray hits the segment."
  [origin direction segment]
  (some? (ray-segment-intersection-distance origin direction segment)))

(defn ray-polygon-intersection-distance
  "Get the minimum distance at which a ray hits any edge of a polygon.
   Returns nil if the ray does not intersect the polygon."
  [origin direction vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))
        distances (keep #(ray-segment-intersection-distance origin direction %) edges)]
    (when (seq distances)
      (apply min distances))))

(defn ray-intersects-polygon?
  "Check if a ray from origin in direction dir intersects any edge of a polygon."
  [origin direction vertices]
  (let [n (count vertices)
        edges (map (fn [i] [(nth vertices i) (nth vertices (mod (inc i) n))])
                   (range n))]
    (some #(ray-segment-intersection? origin direction %) edges)))

;; =============================================================================
;; Target Detection
;; =============================================================================

(defn in-front-of?
  "Check if an attacker's pointing direction ray intersects the target piece.
   This determines if the target is within the attacker's trajectory."
  [attacker target]
  (let [tip (attacker-tip attacker)
        dir (attack-direction attacker)
        target-verts (piece-vertices target)]
    (ray-intersects-polygon? tip dir target-verts)))

(defn within-range?
  "Check if target is within attack range of attacker.
   Per Icehouse rules, range is measured from the attacker's tip to the nearest
   point on the target piece (the nearest edge)."
  [attacker target]
  (let [tip (attacker-tip attacker)
        target-verts (piece-vertices target)
        dist (point-to-polygon-distance tip target-verts)
        rng (attack-range attacker)]
    (<= dist rng)))

(defn clear-line-of-sight?
  "Check if there are no pieces blocking the line between attacker and target.
   The target must be the first piece hit by the attack ray. Any piece between
   the attacker's tip and the target's nearest edge blocks the attack."
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
  "Calculate the angle in radians from point (x1,y1) to (x2,y2).
   Returns values in the range [-PI, PI]."
  [x1 y1 x2 y2]
  (atan2 (- y2 y1) (- x2 x1)))
