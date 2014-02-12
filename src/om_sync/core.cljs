(ns om-sync.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [>! <!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn om-sync
  ([data owner] (om-sync data owner nil))
  ([{:keys [url coll]} owner {:keys [view tx-chan]}]
    (reify
      om/IInitState
      (init-state [_]
        {:kill-chan (chan)})
      om/IWillMount
      (will-mount [_]
        (go (loop []
              ))
        (go (loop []
              )))
      om/IRender
      (render [_]
        (om/build (:view opts) coll opts)))))
