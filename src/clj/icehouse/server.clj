(ns icehouse.server
  (:gen-class)
  (:require [org.httpkit.server :as http]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :as response]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [icehouse.websocket :as ws]))

(defroutes routes
  (GET "/ws" req (ws/handler req))
  (GET "/" [] (-> (response/resource-response "index.html" {:root "public"})
                  (response/content-type "text/html")))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> routes
      (wrap-resource "public")
      wrap-content-type
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))))

(defonce server (atom nil))

(defn start! [port]
  (println (str "Starting server on port " port))
  (reset! server (http/run-server app {:port port})))

(defn stop! []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)))

(defn reset-all! []
  (reset! ws/clients {})
  (reset! icehouse.game/games {}))

(defn -main [& args]
  (let [port (Integer/parseInt (or (first args) "3000"))]
    (start! port)))
