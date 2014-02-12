(ns om-sync.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [put! chan alt!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-sync.util :refer [edn-xhr]]))

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
          (go (loop []
                (let [[v c] (alt! [kill-chan tx-chan])]
                  (if (= c kill-chan)
                    :done
                    (do
                      (if-let [hdlr (get hdlrs (first v))]
                        (apply hdlr v))
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
            :hdlrs {:class/edit-title 1
                    :class/create 2
                    :on-error 3}}})
  )
