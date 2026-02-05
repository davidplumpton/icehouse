(ns icehouse.logging
  "Centralized logging configuration for backend services."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private default-level :info)

(defn- parse-level
  "Parse and sanitize log level from environment."
  [value]
  (let [parsed (some-> value str/lower-case keyword)]
    (if (contains? #{:trace :debug :info :warn :error :fatal :report} parsed)
      parsed
      default-level)))

(defn min-level
  "Resolve minimum log level from ICEHOUSE_LOG_LEVEL env var."
  []
  (parse-level (System/getenv "ICEHOUSE_LOG_LEVEL")))

(defn init!
  "Configure Timbre once for the process."
  []
  (log/merge-config! {:min-level (min-level)})
  (log/info "Logging initialized" {:min-level (name (min-level))}))
