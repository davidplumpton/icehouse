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
