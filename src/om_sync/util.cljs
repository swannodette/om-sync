(ns om-sync.util
  (:require [cljs.reader :as reader]
            [goog.events :as events])
  (:import [goog.net XhrIo]
           goog.net.EventType
           [goog.events EventType]))

(defn popn [n v]
  (loop [n n res v]
    (if (pos? n)
      (recur (dec n) (pop res))
      res)))

(defn sub [p0 p1]
  (vec (drop (- (count p0) (count p1)) p0)))

(defn tx-tag [{:keys [tag] :as tx-data}]
  (if (keyword? tag)
    tag
    (first tag)))

(defn subpath? [a b]
  (= a (popn (- (count b) (count a)) b)))

(defn error? [res]
  (contains? res :error))

(def ^:private meths
  {:get "GET"
   :put "PUT"
   :post "POST"
   :delete "DELETE"})

(defn edn-xhr [{:keys [method url data on-complete on-error]}]
  (let [xhr (XhrIo.)]
    (events/listen xhr goog.net.EventType.SUCCESS
      (fn [e]
        (on-complete (reader/read-string (.getResponseText xhr)))))
    (events/listen xhr goog.net.EventType.ERROR
      (fn [e]
        (on-error {:error (.getResponseText xhr)})))
    (. xhr
      (send url (meths method) (when data (pr-str data))
        #js {"Content-Type" "application/edn" "Accept" "application/edn"}))))
