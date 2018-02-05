(ns meo.jvm.upload
  "Provides upload via REST call."
  (:require [ring.adapter.jetty :as j]
            [compojure.core :refer [routes POST PUT]]
            [clojure.java.io :as io]
            [hiccup.page :refer [html5]]
            [meo.jvm.imports.entries :as ie]
            [image-resizer.util :refer :all]
            [clojure.tools.logging :as log]
            [meo.jvm.file-utils :as fu]
            [matthiasn.systems-toolbox.switchboard :as sb]
            [matthiasn.systems-toolbox-sente.server :as sente])
  (:import (java.net ServerSocket)))

(def upload-port (atom nil))
(def sync-ws-port (atom nil))

(defn get-free-port []
  (let [socket (ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn start-server
  "Fires up REST endpoint that accepts import files:
    - /upload/text-entry.json
    - /upload/visits.json

   Then schedules shutdown."
  [{:keys [put-fn cmp-state current-state msg-meta]}]
  (when-let [server (:server current-state)]
    (log/info "Stopping Upload Server")
    (.stop server))
  (reset! upload-port (get-free-port))
  (log/info "Starting Upload Server on port" @upload-port)
  (let [post-fn (fn [filename req put-fn]
                  (with-open [rdr (io/reader (:body req))]
                    (case filename
                      "text-entries.json" (ie/import-text-entries-fn
                                            rdr put-fn {} filename)
                      "visits.json" (ie/import-visits-fn rdr put-fn {} filename)
                      (log/info :server/upload-cmp :text req))
                    "OK"))
        binary-post-fn (fn [dir filename req]
                         (let [filename (str fu/data-path "/" dir "/" filename)
                               file (java.io.File. filename)]
                           (io/make-parents file)
                           (log/info :server/upload-cmp :binary req)
                           (io/copy (:body req) file))
                         "OK")
        app (routes
              (PUT "/upload/:dir/:file" [dir file :as r]
                (binary-post-fn dir file r))
              (POST "/upload/:filename" [filename :as r]
                (post-fn filename r put-fn)))
        server (j/run-jetty app {:port @upload-port :join? false})
        new-meta (assoc-in msg-meta [:sente-uid] :broadcast)]
    {:new-state (assoc-in current-state [:server] server)
     :emit-msg  [[:cmd/schedule-new
                  {:timeout 120000
                   :message [:import/stop-server]}]
                 (with-meta [:cfg/show-qr] new-meta)]}))

(defn stop-server [{:keys [current-state]}]
  (log/info "Stopping upload server")
  (.stop (:server current-state))
  {:new-state (assoc-in current-state [:server] nil)})

(def qr-reset-msg
  (with-meta
    [:cmd/toggle-key {:path     [:cfg :ws-qr-code]
                      :reset-to false}]
    {:sente-uid :broadcast}))

(defn ws-opts [port]
  {:port          port
   :index-page-fn (fn [_] "hello world")
   :sente-opts    {:ws-kalive-ms 2000}
   :host          "0.0.0.0"
   :relay-types   #{:sync/start :sync/progress
                    :sync/entry :ws/ping}
   :opts          (:reload-cmp true)})

(defn start-ws-server [{:keys [current-state]}]
  (let [switchboard (:switchboard current-state)
        ws-port (get-free-port)
        server-name :server/sync-ws-cmp
        opts (ws-opts ws-port)
        new-state (assoc-in current-state [:ws-port] ws-port)
        new-state (assoc-in new-state [:server-name] server-name)]
    (reset! sync-ws-port ws-port)
    (log/info "Starting" server-name)
    (sb/send-mult-cmd
      switchboard
      [[:cmd/init-comp (sente/cmp-map server-name opts)]
       [:cmd/route {:from server-name
                    :to   #{:server/store-cmp
                            :server/upload-cmp}}]
       [:cmd/route {:from :server/store-cmp
                    :to   server-name}]])
    {:new-state new-state
     :emit-msg  [[:cmd/schedule-new {:message [:sync/stop-server server-name]
                                     :timeout 120000}]
                 [:cmd/schedule-new {:message qr-reset-msg
                                     :timeout 15000}]]}))

(defn stop-ws-server [{:keys [current-state msg-payload]}]
  (let [switchboard (:switchboard current-state)
        server-name (or msg-payload (:server-name current-state))
        new-state (assoc-in current-state [:ws-port] nil)]
    (when server-name
      (log/info "Stopping" server-name)
      (sb/send-mult-cmd
        switchboard
        [[:cmd/shutdown server-name]])
      {:new-state new-state})))

(defn state-fn [switchboard]
  (fn [put-fn]
    (log/info "Starting upload component")
    {:state (atom {:switchboard switchboard})}))

(defn cmp-map [cmp-id switchboard]
  {:cmp-id      cmp-id
   :state-fn    (state-fn switchboard)
   :handler-map {:import/listen      start-server
                 :import/stop-server stop-server
                 :sync/start-server  start-ws-server
                 :sync/stop-server   stop-ws-server}})

