(ns icehouse.utils)

(defn format-time
  "Format milliseconds as MM:SS"
  [ms]
  (let [total-seconds (quot ms 1000)
        minutes (quot total-seconds 60)
        seconds (mod total-seconds 60)]
    (str (when (< minutes 10) "0") minutes
         ":"
         (when (< seconds 10) "0") seconds)))
