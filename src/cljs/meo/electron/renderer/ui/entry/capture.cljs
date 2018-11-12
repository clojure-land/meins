(ns meo.electron.renderer.ui.entry.capture
  (:require [re-frame.core :refer [subscribe]]
            [meo.electron.renderer.ui.questionnaires :as q]
            [meo.electron.renderer.helpers :as h]
            [taoensso.timbre :refer-macros [info error debug]]
            [reagent.ratom :refer-macros [reaction]]
            [clojure.string :as s]
            [reagent.core :as r]
            [moment]))

(defn field-input [entry edit-mode? field tag k put-fn]
  (let [input-cfg (:cfg field)
        input-type (:type input-cfg)
        path [:custom_fields tag k]
        value (get-in entry path)
        value (if (and value (= :time input-type))
                (h/m-to-hh-mm value)
                value)
        on-change-fn
        (fn [ev]
          (let [v (.. ev -target -value)
                parsed (case input-type
                         :number (when (seq v) (js/parseFloat v))
                         :time (when (seq v)
                                 (.asMinutes (.duration moment v)))
                         v)
                updated (assoc-in entry path parsed)]
            (put-fn [:entry/update-local updated])))
        input-cfg (merge
                    input-cfg
                    {:on-change   on-change-fn
                     :class       (when (= input-type :time) "time")
                     :type        input-type
                     :on-key-down (h/key-down-save entry put-fn)
                     :value       value})]
    (when-not value
      (when (and (= input-type :number) edit-mode?)
        (let [p1 (-> (:md entry) (s/split tag) first)
              last-n (last (re-seq #"[0-9]*\.?[0-9]+" p1))]
          (when last-n
            (let [updated (assoc-in entry path (js/parseFloat last-n))]
              (put-fn [:entry/update-local updated])))))
      (when (and (= input-type :time) edit-mode?)
        (let [p1 (-> (:md entry) (s/split tag) first)
              v (last (re-seq #"\d+:\d{2}" p1))]
          (when v
            (let [m (.asMinutes (.duration moment v))
                  updated (assoc-in entry path m)]
              (put-fn [:entry/update-local updated]))))))
    [:tr
     [:td [:label (:label field)]]
     [:td [:input input-cfg]]]))

(defn custom-fields-div
  "In edit mode, allow editing of custom fields, otherwise show a summary."
  [entry put-fn edit-mode?]
  (let [options (subscribe [:options])
        custom-fields (reaction (:custom-fields @options))]
    (fn custom-fields-render [entry put-fn edit-mode?]
      (when-let [custom-fields @custom-fields]
        (let [ts (:timestamp entry)
              entry-field-tags (select-keys custom-fields (:tags entry))
              default-story (->> entry-field-tags
                                 (map (fn [[k v]] (:default-story v)))
                                 (filter identity)
                                 first)]
          (when (and edit-mode? default-story (not (:primary_story entry)))
            (put-fn [:entry/update-local (merge entry
                                                {:primary_story  default-story
                                                 :linked-stories #{default-story}})]))
          [:form.custom-fields
           (for [[tag conf] (sort-by first entry-field-tags)]
             ^{:key (str "cf" ts tag)}
             [:div
              [:h2 tag]
              [:table
               [:tbody
                (for [[k field] (:fields conf)]
                  ^{:key (str "cf" ts tag k)}
                  [field-input entry edit-mode? field tag k put-fn])]]])])))))

(defn questionnaire-div
  "In edit mode, allow editing of questionnaire, otherwise show a summary."
  [entry put-fn edit-mode?]
  (let [options (subscribe [:options])
        local (r/atom {:expanded false})
        questionnaires (reaction (:questionnaires @options))]
    (fn questionnaire-render [entry put-fn edit-mode?]
      (when-let [questionnaires @questionnaires]
        (let [ts (:timestamp entry)
              questionnaire-tags (:mapping questionnaires)
              q-tags (select-keys questionnaire-tags (:tags entry))
              q-mapper (fn [[t k]] [k (get-in questionnaires [:items k])])
              pomo-q [:pomo1 (get-in questionnaires [:items :pomo1])]
              entry-questionnaires (map q-mapper q-tags)
              completed-pomodoro (and (>= (:completed_time entry)
                                          (:planned_dur entry 1500))
                                      (= (:entry_type entry) :pomodoro)
                                      (> ts 1505770346000))
              entry-questionnaires (into {} (if completed-pomodoro
                                              (conj entry-questionnaires pomo-q)
                                              entry-questionnaires))
              expanded (or edit-mode? (:expanded @local))
              expand-toggle #(swap! local update-in [:expanded] not)]
          [:div
           (for [[k conf] entry-questionnaires]
             (let [q-path [:questionnaires k]
                   scores (q/scores entry q-path conf)
                   reference (:reference conf)]
               ^{:key (str "cf" ts k)}
               [:form.questionnaire
                [:h3 (:header conf)
                 (when-not edit-mode?
                   [:span.fa.expand-toggle
                    {:on-click expand-toggle
                     :class    (if expanded "fa-compress" "fa-expand")}])]
                (when expanded
                  (:desc conf))
                (when expanded
                  [:ol
                   (for [{:keys [type path label one-line]} (:fields conf)]
                     (let [path (concat q-path path)
                           value (get-in entry path)
                           items (get-in questionnaires [:types type :items])]
                       ^{:key (str "q" ts k path)}
                       [:li
                        [:label {:class (str (when-not value "missing ")
                                             (when-not one-line "multi-line"))}
                         [:strong label]]
                        (when-not one-line [:br])
                        [:span.range
                         (for [item items]
                           (let [v (:value item)
                                 item-label (or (:label item) v)
                                 click (fn [_ev]
                                         (let [new-val (when-not (= value v) v)
                                               updated (assoc-in entry path new-val)]
                                           (put-fn [:entry/update-local updated])))]
                             ^{:key (str "q" ts k path v)}
                             [:span.opt.tooltip
                              {:on-click click
                               :class    (when (= value v) "sel")} item-label]))]]))])
                [:div.agg
                 (for [[k res] scores]
                   ^{:key k}
                   [:div
                    [:span (:label res)]
                    [:span.res (.toFixed (:score res) 2)]])]
                (when expanded
                  (if (string? reference)
                    [:cite reference]
                    reference))]))])))))
