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

(defn ^:private sync-server [url tag edn]
  (let [res-chan (chan)]
    (edn-xhr
      {:method (type->method tag)
       :url url
       :data edn
       :on-error (fn [err] (put! res-chan err))
       :on-complete (fn [res] (put! res-chan res))})
    res-chan))

(defn ^:private tag-and-edn [coll-path path tag-fn id-key tx-data]
  (let [tag (if-not (nil? tag-fn)
              (tag-fn tx-data)
              (tx-tag tx-data))
        edn (condp = tag
              :create (:new-value tx-data)
              :update (let [ppath (popn (- (count path) (inc (count coll-path))) path)
                            m (select-keys (get-in (:new-state tx-data) ppath) [id-key])
                            rel (sub path coll-path)]
                        (assoc-in m (rest rel) (:new-value tx-data)))
              :delete (-> tx-data :old-value id-key)
              nil)]
    [tag edn]))

(defn om-sync
  "ALPHA: Creates a reusable sync componet. Data must be a map containing
  :url and :coll keys. :url must identify a server endpoint that can
  takes EDN data via POST for create, PUT for update, and DELETE for
  delete. :coll must be a cursor into the application state. Note the
  first argument could of course just be a cursor itself.

  In order to function you must provide a subscribeable core.async
  channel that will stream all :tx-listen events. This channel must be
  called :tx-chan and provided via the :share option to om.core/root. It
  must be a channel constructed with cljs.core.async/pub with the topic
  :txs.

  Once built om-sync will act on any transactions to the :coll value
  regardless of depth. In order to identiy which transactions to act
  on these transactions must be labeled as :create, :update, or
  :delete.

  om-sync takes a variety of options via the :opts passed to
  om.core/build:

  :view - a required Om component function to render the collection.

  :id-key - which property represents the server id for a item in the
    collection.

  :filter - a function which filters which tagged transaction to actually sync.

  :tag-fn - not all components you might want to interact with may
    have properly tagged their transactions. This function will
    receive the transaction data and return the determined tag.

  :on-success - a callback function that will receive the server
    response and the transaction data on success.

  :on-error - a callback function that will receive the server error
    and the transaction data on failure. The transaction data can
    easily be leveraged to rollback the application state.

  :sync-chan - if given this option, om-sync will not invoke
    sync-server instead it will put a map containing the :listen-path,
    :url, :tag, :edn, :on-success, :on-error, and :tx-data on the
    provided channel. This higher level operations such as server
    request batching and multiple om-sync component coordination."
  ([data owner] (om-sync data owner nil))
  ([{:keys [url coll] :as data} owner opts]
    (assert (not (nil? url)) "om-sync component not given url")
    (reify
      om/IInitState
      (init-state [_]
        {:kill-chan (chan)})
      om/IWillMount
      (will-mount [_]
        (let [{:keys [id-key filter tag-fn sync-chan]} opts
              {:keys [on-success on-error]} opts
              kill-chan (om/get-state owner :kill-chan)
              tx-chan (om/get-shared owner :tx-chan)
              txs (chan)
              coll-path (om/path coll)]
          (assert (not (nil? tx-chan))
            "om-sync requires shared :tx-chan pub channel with :txs topic")
          (async/sub tx-chan :txs txs)
          (om/set-state! owner :txs txs)
          (go (loop []
                (let [[v c] (alts! [txs kill-chan])]
                  (if (= c kill-chan)
                    :done
                    (let [[{:keys [path new-value new-state] :as tx-data} _] v]
                      (when (and (subpath? coll-path path)
                              (or (nil? filter) (filter tx-data)))
                        (let [[tag edn] (tag-and-edn coll-path path tag-fn id-key tx-data)
                              tx-data (assoc tx-data ::tag tag)]
                          (if-not (nil? sync-chan)
                            (>! sync-chan
                              {:url url :tag tag :edn edn
                               :listen-path coll-path
                               :on-success on-success
                               :on-error on-error
                               :tx-data tx-data})
                            (let [res (<! (sync-server url tag edn))]
                              (if (error? res)
                                (on-error res tx-data)
                                (on-success res tx-data))))))
                      (recur))))))))
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
