(ns om-sync.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [put! chan alt!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-sync.util :refer [edn-xhr]]))

(defn error? [v]
  )

(def type->method
  {::create :post
   ::update :put
   ::delete :delete})

(defn sync-server [url type {:keys [new-data]}]
  (let [res-chan (chan)]
    (edn-xhr
      {:method (type->method type)
       :url url
       :data new-data
       :on-error (fn [err] (put! res-chan err))
       :on-complete (fn [res] (put! res-chan res))})
    res-chan))

(defn om-sync
  ([data owner] (om-sync data owner nil))
  ([{:keys [url coll]} owner {:keys [view hdlrs] :as opts}]
    (assert (not (nil? url)) "om-sync component not given url")
    (reify
      om/IInitState
      (init-state [_]
        {:kill-chan (chan)})
      om/IWillMount
      (will-mount [_]
        (let [kill-chan (om/get-state owner :kill-chan)
              tx-chan   (om/get-shared owner :tx-chan)]
          (assert (not (nil? tx-chan)) "om-sync requires shared :tx-chan")
          (go (loop []
                (let [[v c] (alt! [kill-chan tx-chan])]
                  (if (= c kill-chan)
                    :done
                    (do
                      (if-let [hdlr (get hdlrs (first v))]
                        (let [res (<! (sync-server url hdlr v))]
                          (if (error? res)
                            ((:on-error opts) res v)
                            ((:on-success opts) res v))))
                      (recur))))))))
      om/IWillUnmount
      (will-unmount [_]
        (let [kill-chan (om/get-state owner :kill-chan)]
          (put! kill-chan (js/Date.))))
      om/IRender
      (render [_]
        (om/build view coll opts)))))

(comment
  (om/build om-sync (:classes app)
    {:opts {:view classes-view
            :hdlrs {:class/create ::create
                    :class/delete ::delete
                    :class/title  ::update}
            :on-success (fn [_])
            :on-error (fn [_])}})
  )
