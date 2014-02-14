# om-sync

A reusable synchronization component for
[Om](http://github.com/swannodette/om).

## Example

`om-sync` leverages the new `:tx-listen` option allowed by
`om.core/root`. For example you can imagine setting up your
application like so:

```clj
(let [tx-chan (chan)
      tx-pub-chan (async/pub tx-chan (fn [_] :txs))]
  (om-sync.util/edn-xhr
    {:method :get
     :url "/init"
     :on-complete
     (fn [res]
       (reset! app-state res)
       (om/root app-view app-state
         {:target (gdom/getElement "app")
          :shared {:tx-chan tx-pub-chan}
          :tx-listen
          (fn [tx-data root-cursor]
            (put! tx-chan [tx-data root-cursor]))}))}))
```

We publish a transaction queue channel `:tx-chan` as a global service
via `:shared` so that `om-sync` instances can listen in.

We can now take any application data and wrap it in an `om-sync`
instance. Whenever application data changes `om-sync` will synchronize
those changes via [EDN](http://github.com/edn-format/edn) requests to
a server.

Notice in the following that `om-sync` can take an `:on-error`
handler. This error handler will be given `tx-data` which contains
enough information to roll back the entire application state if
something goes wrong.

```clj
(defn app-view [app owner]
  (reify
    om/IWillUpdate
    (will-update [_ next-props next-state]
      (when (:err-msg next-state)
        (js/setTimeout #(om/set-state! owner :err-msg nil) 5000)))
    om/IRenderState
    (render-state [_ {:keys [err-msg]}]
      (dom/div nil
        (om/build om-sync (:items app)
          {:opts {:view items-view
                  :filter (comp #{:create :update :delete} tx-tag)
                  :id-key :some/id
                  :on-success (fn [res tx-data] (println res))
                  :on-error
                  (fn [err tx-data]
                    (reset! app-state (:old-state tx-data))
                    (om/set-state! owner :err-msg
                      "Ooops! Sorry something went wrong try again later."))}})
         (when err-msg
           (dom/div nil err-msg))))))
```

## Usage

You must install Om master.

`om.core/transact!` and `om.core/update!` now support tagging
transactions with a keyword or a vector that starts with a
keyword. `om-sync` listens in on transactions labeled `:create`,
`:update`, and `:delete`.

```clj
(defn foo [some-data owner]
  (om.core/transact! some-data :foo (fn [_] :bar) :update))
```

If you are given some component that does not tag its transactions or
the tags do not correspond to `:create`, `:update`, and `:delete` you
can provide a `:tag-fn` via `:opts` to `om-sync` so that you can
classify the transaction yourself.

## Contributions

Pull requests welcome.

## License

Copyright © 2014 David Nolen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
