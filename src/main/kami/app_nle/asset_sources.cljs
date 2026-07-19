(ns kami.app-nle.asset-sources
  (:require [cljs.reader :as reader]))

(def network-isekai-catalog-url "https://isekai.network/assets/index.edn")
(def kotobase-pins-url "https://kotobase.net/pins")
(def ipfs-gateway "https://ipfs.gftd.ai/ipfs/")
(defn- fetch-edn! [url]
  (-> (js/fetch url #js {:headers #js {"accept" "application/edn, text/plain"}})
      (.then (fn [r] (if (.-ok r) (.text r) (throw (js/Error. (str "source returned HTTP " (.-status r)))))))
      (.then #(vec (reader/read-string %)))))
(defn network-isekai! []
  (-> (fetch-edn! network-isekai-catalog-url)
      (.then #(hash-map :source/id :network-isekai :source/name "network-isekai"
                        :source/url network-isekai-catalog-url :source/items %))))
(defn- jwt [] (or (.getItem js/localStorage "kotobase.jwt") (.getItem js/sessionStorage "kotobase.jwt")))
(defn- pin->asset [pin]
  (let [cid (or (get pin "cid") (get pin "key"))]
    (when (and (string? cid) (not (empty? cid)))
      {:asset/id (str "kotobase:" cid) :asset/name (or (get pin "name") cid)
       :asset/source :kotobase :asset/cid cid :asset/uri (str ipfs-gateway cid) :asset/mime (get pin "mime")})))
(defn kotobase! []
  (if-let [token (jwt)]
    (-> (js/fetch kotobase-pins-url #js {:headers #js {"accept" "application/json" "authorization" (str "Bearer " token)}})
        (.then (fn [r] (if (.-ok r) (.json r) (throw (js/Error. (str "Kotobase returned HTTP " (.-status r)))))))
        (.then (fn [body] (let [pins (or (aget body "pins") body)]
                            (hash-map :source/id :kotobase :source/name "kotobase.net"
                                      :source/url kotobase-pins-url :source/items (vec (keep pin->asset (array-seq pins))))))))
    (js/Promise.reject (js/Error. "Kotobase JWT missing; set localStorage.kotobase.jwt"))))
(defn load-all! [] (js/Promise.all #js [(network-isekai!) (kotobase!)]))
