(ns iwaswhere-web.core
  "In this namespace, the individual components are initialized and wired
  together to form the backend system."
  (:gen-class)
  (:require [matthiasn.systems-toolbox.switchboard :as sb]
            [matthiasn.systems-toolbox-sente.server :as sente]
            [iwaswhere-web.index :as idx]
            [iwaswhere-web.specs]
            [clojure.tools.logging :as log]
            [clj-pid.core :as pid]
            [matthiasn.systems-toolbox-kafka.kafka-producer2 :as kp2]
            [iwaswhere-web.store :as st]
            [iwaswhere-web.fulltext-search :as ft]
            [iwaswhere-web.upload :as up]
            [iwaswhere-web.blink :as bl]
            [iwaswhere-web.imports :as i]
            [iwaswhere-web.export :as e]
            [matthiasn.systems-toolbox.scheduler :as sched]
            [matthiasn.systems-toolbox-zipkin.core :as z]))

(defonce switchboard (sb/component :server/switchboard))

(defn make-observable
  [components]
  (if (System/getenv "OBSERVER")
    (let [cfg {:cfg         {:bootstrap-servers "localhost:9092"
                             :auto-offset-reset "latest"
                             :topic             "iww-firehose"}
               :relay-types #{:firehose/cmp-put
                              :firehose/cmp-recv}}
          mapper #(assoc-in % [:opts :msgs-on-firehose] true)
          components (set (mapv mapper components))
          firehose-kafka (kp2/cmp-map :server/kafka-firehose cfg)]
      (conj components firehose-kafka))
    components))

(defn restart!
  "Starts or restarts system by asking switchboard to fire up the ws-cmp for
   serving the client side application and providing bi-directional
   communication with the client, plus the store and imports components.
   Then, routes messages to the store and imports components for which those
   have a handler function. Also route messages from imports to store component.
   Finally, sends all messages from store component to client via the ws
   component."
  [switchboard]
  (let [components #{(sente/cmp-map :server/ws-cmp idx/sente-map)
                     (sched/cmp-map :server/scheduler-cmp)
                     (i/cmp-map :server/imports-cmp)
                     (e/cmp-map :server/export-cmp)
                     (st/cmp-map :server/store-cmp)
                     (up/cmp-map :server/upload-cmp)
                     (bl/cmp-map :server/blink-cmp)
                     (ft/cmp-map :server/ft-cmp)}
        components (if (System/getenv "ZIPKIN")
                     (let [reporter (z/mk-reporter "http://localhost:9411")
                           trace-cmp (z/trace-cmp reporter)]
                       (set (mapv trace-cmp components)))
                     components)
        components (make-observable components)]
    (sb/send-mult-cmd
      switchboard
      [[:cmd/init-comp components]

       [:cmd/route {:from :server/ws-cmp
                    :to   #{:server/store-cmp
                            :server/blink-cmp
                            :server/export-cmp
                            :server/upload-cmp
                            :server/imports-cmp}}]

       [:cmd/route {:from :server/imports-cmp
                    :to   :server/store-cmp}]

       [:cmd/route {:from :server/upload-cmp
                    :to   #{:server/store-cmp
                            :server/ws-cmp}}]

       [:cmd/route {:from :server/store-cmp
                    :to   #{:server/ws-cmp
                            :server/ft-cmp}}]

       [:cmd/route {:from :server/scheduler-cmp
                    :to   #{:server/store-cmp
                            :server/blink-cmp
                            :server/imports-cmp
                            :server/upload-cmp
                            :server/ws-cmp}}]

       [:cmd/route {:from #{:server/store-cmp
                            :server/blink-cmp
                            :server/upload-cmp
                            :server/imports-cmp}
                    :to   :server/scheduler-cmp}]

       (when (System/getenv "OBSERVER")
         [:cmd/attach-to-firehose :server/kafka-firehose])

       [:cmd/send {:to  :server/scheduler-cmp
                   :msg [:cmd/schedule-new {:timeout (* 5 60 1000)
                                            :message [:import/spotify]
                                            :repeat  true
                                            :initial true}]}]])))

(defn -main
  "Starts the application from command line, saves and logs process ID. The
   system that is fired up when restart! is called proceeds in core.async's
   thread pool. Since we don't want the application to exit when the current
   thread is out of work, we just put it to sleep."
  [& _args]
  (pid/save "iwaswhere.pid")
  (pid/delete-on-shutdown! "iwaswhere.pid")
  (log/info "Application started, PID" (pid/current))
  (restart! switchboard)
  (Thread/sleep Long/MAX_VALUE))
