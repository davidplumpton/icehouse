(ns icehouse.audio)

(def ^:private placement-sounds
  {:big "/assets/audio/piece-big.mp3"
   :medium "/assets/audio/piece-medium.mp3"
   :small "/assets/audio/piece-small.mp3"})

(defonce ^:private placement-audio
  (into {}
        (for [[size src] placement-sounds]
          [size (doto (js/Audio. src)
                  (set! -preload "auto"))])))

(defn- normalize-size [size]
  (cond
    (keyword? size) size
    (string? size) (keyword size)
    :else nil))

(defn play-placement-sound
  "Play the placement sound for the given piece size."
  [size]
  (when-let [audio (get placement-audio (normalize-size size))]
    (set! (.-currentTime audio) 0)
    (let [play-result (.play audio)]
      (when (and play-result (.-catch play-result))
        (.catch play-result
                (fn [err]
                  (js/console.warn "Failed to play placement sound:" err)))))))
