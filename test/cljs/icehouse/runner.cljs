(ns icehouse.runner
  (:require [cljs.test :refer [run-tests]]
            [icehouse.geometry-test]
            [icehouse.game-test]
            [icehouse.utils-test]
            [icehouse.state-test]
            [icehouse.websocket-test]))

(defn init []
  (run-tests 'icehouse.geometry-test
             'icehouse.game-test
             'icehouse.utils-test
             'icehouse.state-test
             'icehouse.websocket-test))