(ns user
  "Development namespace with hot reload support.

   Usage:
     clojure -M:dev -m nrepl.cmdline --interactive

   Then in REPL:
     (start)   ; Start the server
     (stop)    ; Stop the server
     (reset)   ; Reload changed namespaces and restart"
  (:require [clojure.tools.namespace.repl :as repl]))

(repl/set-refresh-dirs "src/clj")

(def default-port 3000)

(defn start
  "Start the server on the specified port (default 3000)."
  ([] (start default-port))
  ([port]
   (require 'icehouse.server)
   ((resolve 'icehouse.server/start!) port)))

(defn stop
  "Stop the running server."
  []
  (when (find-ns 'icehouse.server)
    ((resolve 'icehouse.server/stop!))))

(defn reset
  "Stop the server, reload changed namespaces, and restart."
  []
  (stop)
  (repl/refresh :after 'user/start))

(println "Dev environment loaded. Available commands: (start) (stop) (reset)")
