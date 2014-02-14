(ns om-sync.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [put! chan alts!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-sync.util :refer [edn-xhr popn sub tx-tag subpath? error?]]))

(def ^:private type->method
  {:create :post
   :update :put
   :delete :delete})

(defn ^:private sync-server [url type edn]
  (let [res-chan (chan)]
    (edn-xhr
      {:method (type->method type)
       :url url
       :data edn
       :on-error (fn [err] (put! res-chan err))
       :on-complete (fn [res] (put! res-chan res))})
    res-chan))

(defn om-sync
  "ALPHA: Creates a reusable sync componet. Data must be a map containing
  :url and :coll keys. :url must identify a server endpoint that can
  takes EDN data via POST for create, PUT for update, and DELETE for
  delete. :coll must be a cursor into the application state. Note the
  first argument could of course just be cursor itself.

  In order to function you must provide a subscribeable core.async
  channel that will stream all :tx-listen events. This channel must be
  called :tx-chan and provided via the :share option to om.core/root.

  Once built om-sync will act on any transactions to the :coll value
  regardless of depth. In order to identiy which transactions to act
  on these transactions must be labeled as :create, :update, or
  :delete.

  om-sync takes a variety of options via the :opts passed to
  om.core/build:

  :view - a required Om component function to render the collection.

  :id-key - which property represents the server key for a changed
    item in the collection.

  :filter - a function of which transaction tags to actually sync.

  :tag-fn - not all components you might want to interact with may
    have properly tagged their transactions. This function will
    receive the transaction data and return the determined tag.

  :on-success - a callback function that will receive the server
    response and the transaction data on success.

  :on-error - a callback function that will receive the server error
    and the transaction data on failure. The transaction data can
    easily be leveraged to roll back the application state."
  ([data owner] (om-sync data owner nil))
  ([{:keys [url coll] :as data} owner opts]
    (assert (not (nil? url)) "om-sync component not given url")
    (reify
      om/IInitState
      (init-state [_]
        {:kill-chan (chan)})
      om/IWillMount
      (will-mount [_]
        (let [{:keys [id-key filter tag-fn]} opts
              kill-chan (om/get-state owner :kill-chan)
              tx-chan (om/get-shared owner :tx-chan)
              txs (chan)]
          (assert (not (nil? tx-chan))
            "om-sync requires shared :tx-chan pub channel with :txs topic")
          (async/sub tx-chan :txs txs)
          (om/set-state! owner :txs txs)
          (go (loop []
                (let [dpath (om/path coll)
                      [{:keys [path new-value new-state] :as tx-data} _] (<! txs)
                      ppath (popn (- (count path) (inc (count dpath))) path)]
                  (when (and (subpath? dpath path)
                             (or (nil? filter) (filter tx-data)))
                    (let [tag (if-not (nil? tag-fn)
                                (tag-fn tx-data)
                                (tx-tag tx-data))
                          edn (condp = tag
                                :create new-value
                                :update (let [m (select-keys (get-in new-state ppath) [id-key])
                                              rel (sub path dpath)]
                                          (assoc-in m (rest rel) new-value))
                                :delete (-> tx-data :old-value id-key)
                                nil)]
                      (let [res (<! (sync-server url tag edn))]
                        (if (error? res)
                          ((:on-error opts) res (assoc tx-data ::tag tag))
                          ((:on-success opts) res (assoc tx-data ::tag tag))))))
                  (recur))))))
      om/IWillUnmount
      (will-unmount [_]
        (let [{:keys [kill-chan txs]} (om/get-state owner)]
          (when kill-chan
            (put! kill-chan (js/Date.)))
          (when txs
            (async/unsub (om/get-shared owner :tx-chan) :txs txs))))
      om/IRender
      (render [_]
        (om/build (:view opts) coll)))))
