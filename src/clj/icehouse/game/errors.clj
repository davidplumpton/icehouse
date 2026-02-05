(ns icehouse.game.errors
  "Shared error construction and delivery helpers for game handlers."
  (:require [icehouse.messages :as msg]
            [icehouse.utils :as utils]))

(defn make-error
  "Create a structured error response with code, message, and rule explanation."
  [code message rule]
  {:code code
   :message message
   :rule rule})

(defn send-error!
  "Send a structured error to the client. Handles both old string errors and new structured errors."
  [channel error]
  (if (map? error)
    (utils/send-msg! channel {:type msg/error
                              :code (:code error)
                              :message (:message error)
                              :rule (:rule error)})
    (utils/send-msg! channel {:type msg/error :message (or error "Unknown error")})))
