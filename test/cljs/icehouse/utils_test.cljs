(ns icehouse.utils-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.utils :as utils]))

(deftest normalize-player-id-test
  (testing "normalize-player-id returns keywords in CLJS"
    (is (= :p1 (utils/normalize-player-id "p1")))
    (is (= :p1 (utils/normalize-player-id :p1)))
    (is (nil? (utils/normalize-player-id nil)))))

(deftest captured-piece-helpers-test
  (let [captured [{:size :small :colour "#ff0000"}
                  {:size :medium :colour "#00ff00"}
                  {:size :small :colour "#0000ff"}]]
    (testing "count-captured-by-size"
      (is (= 2 (utils/count-captured-by-size captured :small)))
      (is (= 1 (utils/count-captured-by-size captured :medium)))
      (is (= 0 (utils/count-captured-by-size captured :large))))

    (testing "get-captured-piece"
      (is (= {:size :small :colour "#ff0000"} (utils/get-captured-piece captured :small))))

    (testing "remove-first-captured"
      (let [result (utils/remove-first-captured captured :small)]
        (is (= 2 (count result)))
        (is (= {:size :medium :colour "#00ff00"} (first result)))
        (is (= {:size :small :colour "#0000ff"} (second result)))))))

(deftest format-time-test
  (testing "format-time formats milliseconds correctly"
    (is (= "00:00" (utils/format-time 0)))
    (is (= "00:05" (utils/format-time 5000)))
    (is (= "01:00" (utils/format-time 60000)))
    (is (= "10:30" (utils/format-time 630000)))))
