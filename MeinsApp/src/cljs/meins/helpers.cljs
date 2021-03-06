(ns meins.helpers
  (:require ["@react-native-community/geolocation" :as Geolocation]
            ["buffer" :refer [Buffer]]
            ["intl" :as intl]
            ["intl/locale-data/jsonp/en"]
            ["moment" :as moment]
            [clojure.walk :as walk]
            [goog.dom.Range]
            [matthiasn.systems-toolbox.component :as st]
            [meins.common.utils.parse :as p]
            [meins.ui.shared :as shared]))

(set! js/moment moment)
(set! js/Buffer Buffer)

(defn send-w-geolocation
  "Calls geolocation, sends entry enriched by geo information inside the
  callback function"
  [ts put-fn]
  (try
    (.getCurrentPosition
      Geolocation
      (fn [pos]
        (let [loc (js->clj (.-coords pos) :keywordize-keys true)]
          (put-fn [:entry/persist {:timestamp ts
                                   :location  loc
                                   :latitude  (:latitude loc)
                                   :longitude (:longitude loc)}])))
      (fn [err] (prn err)))
    (catch :default e (shared/alert (str "geolocation not available: " e)))))

(defn parse-entry
  "Parses entry for hashtags and mentions by calling shared function, plus setting the
   #import tag."
  [text]
  (update (p/parse-entry text) :tags conj "#import") )

(defn new-entry-fn [put-fn opts]
  (let [ts (st/now)
        dtf (new intl/DateTimeFormat)
        timezone (or (when-let [resolved (.-resolved dtf)]
                       (.-timeZone resolved))
                     (when-let [resolved (.resolvedOptions dtf)]
                       (.-timeZone resolved)))
        entry (merge (parse-entry "")
                     {:timestamp  ts
                      :new-entry  true
                      :timezone   timezone
                      :utc-offset (.getTimezoneOffset (new js/Date))}
                     opts)]
    (put-fn [:entry/new entry])
    (send-w-geolocation ts put-fn)))

(defn prevent-default [ev] (.preventDefault ev))

(defn add [x y] (+ (or x 0) (or y 0)))

(defn update-numeric [entry path put-fn]
  (fn [ev]
    (let [v (.. ev -target -value)
          parsed (when (seq v) (js/parseFloat v))
          updated (assoc-in entry path parsed)]
      (when parsed
        (put-fn [:entry/update updated])))))

(def ymd-format "YYYY-MM-DD")
(defn n-days-ago [n] (.subtract (js/moment.) n "d"))
(defn n-days-ago-fmt [n] (.format (n-days-ago n) ymd-format))
(defn format-time [m] (.format (js/moment m) "YYYY-MM-DD HH:mm"))
(defn img-fmt [m] (.format (js/moment m) "YYYYMMDD_HHmmss_SSS"))

(defn entry-date-fmt [ts]
  (.format (js/moment ts) "ddd DD MMM YYYY"))

(defn hh-mm [m] (.format (js/moment m) "HH:mm"))
(defn mm-ss [m] (.format (js/moment m) "mm:ss"))
(defn ymd [m] (.format (js/moment m) ymd-format))
(defn iso8601-to-millis [m] (js/parseInt (.format (js/moment m) "x")))

(defn m-to-hh-mm [m]
  (let [t (js/moment (* m 60 1000))]
    (.format (.utc t) "HH:mm")))
(defn s-to-hh-mm [s]
  (let [t (js/moment (* s 1000))]
    (.format (.utc t) "HH:mm")))

(defn get-stats
  "Retrieves stats for the last n days."
  [stats-key n m put-fn]
  (let [days (map n-days-ago-fmt (reverse (range n)))]
    (put-fn (with-meta
              [:stats/get {:days (mapv (fn [d] {:date-string d}) days)
                           :type stats-key}]
              m))))

;; from https://stackoverflow.com/a/34221816
(defn remove-nils [m]
  (let [f (fn [x]
            (if (map? x)
              (let [kvs (filter (comp not nil? second) x)]
                (if (empty? kvs) nil (into {} kvs)))
              x))]
    (walk/postwalk f m)))
