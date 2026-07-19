(ns kami.app-nle.cache)

(def database-name "kami-app-nle-media")
(def database-version 1)
(def store-name "media-v1")

(defn open! []
  (js/Promise.
   (fn [resolve reject]
     (let [request (.open js/indexedDB database-name database-version)]
       (set! (.-onupgradeneeded request)
             (fn [_]
               (let [db (.-result request)]
                 (when-not (.contains (.-objectStoreNames db) store-name)
                   (.createObjectStore db store-name #js {:keyPath "sha256"})))))
       (set! (.-onsuccess request) #(resolve (.-result request)))
       (set! (.-onerror request) #(reject (.-error request)))))))

(defn request-promise [request]
  (js/Promise.
   (fn [resolve reject]
     (set! (.-onsuccess request) #(resolve (.-result request)))
     (set! (.-onerror request) #(reject (.-error request))))))

(defn transact! [mode operation]
  (-> (open!)
      (.then (fn [db]
               (let [transaction (.transaction db #js [store-name] mode)
                     store (.objectStore transaction store-name)
                     request (operation store)]
                 (-> (request-promise request)
                     (.finally #(.close db))))))))

(defn put! [{:keys [sha256 name type blob]}]
  (transact! "readwrite"
             #(.put % #js {:sha256 sha256 :name name :type type :blob blob :cachedAt (.now js/Date)})))

(defn get! [sha256]
  (transact! "readonly" #(.get % sha256)))

(defn delete! [sha256]
  (transact! "readwrite" #(.delete % sha256)))

(defn clear! []
  (transact! "readwrite" #(.clear %)))
