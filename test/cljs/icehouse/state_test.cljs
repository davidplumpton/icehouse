(ns icehouse.state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [icehouse.state :as state]
            [icehouse.schema :as schema]
            [malli.core :as m]))

(deftest initial-state-test
  (testing "initial state matches schema"
    (is (m/validate schema/UIState @state/ui-state) "UI state should be valid initially")))

(deftest colors-constant-test
  (testing "colours vector contains valid hex colors"
    (is (every? #(re-matches #"^#[0-9a-fA-F]{6}$" %) state/colours) "All colors should match hex format")))

(deftest leave-game-to-lobby-transition-test
  (testing "leave-game-to-lobby! clears game-specific state and returns to lobby"
    (reset! state/game-state {:players {} :board []})
    (reset! state/game-result {:scores {"p1" 1}})
    (reset! state/current-view :game)

    (state/leave-game-to-lobby!)

    (is (nil? @state/game-state))
    (is (nil? @state/game-result))
    (is (= :lobby @state/current-view))))

(deftest leave-replay-to-lobby-transition-test
  (testing "leave-replay-to-lobby! clears replay/list state and returns to lobby"
    (reset! state/replay-state {:record {:moves []}
                                :current-move 0
                                :playing? false
                                :speed 1})
    (reset! state/game-list ["game-1"])
    (reset! state/current-view :replay)

    (state/leave-replay-to-lobby!)

    (is (nil? @state/replay-state))
    (is (nil? @state/game-list))
    (is (= :lobby @state/current-view))))
