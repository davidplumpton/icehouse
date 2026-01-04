(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'icehouse/icehouse)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[icehouse.server]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'icehouse.server})
  (println "Built:" uber-file))
