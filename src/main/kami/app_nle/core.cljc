(ns kami.app-nle.core)
(def schema "kami.eizo-project/v1")
(def history-limit 50)
(def empty-history {:history/past [] :history/future []})
(defn record-history [history previous]
  {:history/past (->> (conj (vec (:history/past history)) previous) (take-last history-limit) vec)
   :history/future []})
(defn undo-project [current history]
  (if-let [previous (peek (:history/past history))]
    {:project previous :history {:history/past (pop (:history/past history))
                                 :history/future (->> (conj (vec (:history/future history)) current)
                                                      (take-last history-limit) vec)}}
    {:project current :history history}))
(defn redo-project [current history]
  (if-let [next-project (peek (:history/future history))]
    {:project next-project :history {:history/past (->> (conj (vec (:history/past history)) current)
                                                        (take-last history-limit) vec)
                                     :history/future (pop (:history/future history))}}
    {:project current :history history}))
(def export-profiles
  {:review {:profile/name "Review VP8" :profile/mime "video/webm;codecs=vp8,opus" :profile/video-bps 2000000 :profile/audio-bps 128000}
   :master {:profile/name "Master VP9" :profile/mime "video/webm;codecs=vp9,opus" :profile/video-bps 8000000 :profile/audio-bps 192000}
   :compact {:profile/name "Compact VP8" :profile/mime "video/webm;codecs=vp8,opus" :profile/video-bps 1000000 :profile/audio-bps 96000}})
(def proxy-profile {:profile/name "Preview proxy" :profile/mime "video/webm;codecs=vp8,opus"
                    :profile/max-width 640 :profile/max-height 360
                    :profile/video-bps 800000 :profile/audio-bps 96000})
(defn media-url-key [proxy-preview? exporting? asset]
  (if (and proxy-preview? (not exporting?) (:proxy-url asset)) :proxy-url :url))
(def flat-eq {:low-db 0.0 :mid-db 0.0 :high-db 0.0})
(defn clamp-db [db] (max -12.0 (min 12.0 (or db 0.0))))
(defn normalize-eq [eq]
  {:low-db (clamp-db (:low-db eq)) :mid-db (clamp-db (:mid-db eq)) :high-db (clamp-db (:high-db eq))})
(defn valid-eq? [eq]
  (and (map? eq) (every? #(and (number? %) (<= -12 % 12)) ((juxt :low-db :mid-db :high-db) eq))))
(defn project [m] (merge {:project/schema schema :project/fps 30 :project/export-profile :review
                           :project/master-eq flat-eq :project/assets {} :project/tracks []} m))
(defn register-asset
  ([p asset-id name] (register-asset p asset-id name nil))
  ([p asset-id name sha256]
   (assoc-in p [:project/assets asset-id] (cond-> {:asset/name name} sha256 (assoc :asset/sha256 sha256)))))
(defn asset-id-by-name [p name]
  (some (fn [[asset-id asset]] (when (= name (:asset/name asset)) asset-id)) (:project/assets p)))
(defn asset-id-by-signature [p {:keys [name sha256]}]
  (or (when sha256 (some (fn [[asset-id asset]] (when (= sha256 (:asset/sha256 asset)) asset-id)) (:project/assets p)))
      (some (fn [[asset-id asset]] (when (and (= name (:asset/name asset)) (nil? (:asset/sha256 asset))) asset-id))
            (:project/assets p))))
(defn directory-relink-plan [p candidates]
  (let [ordered (sort-by (juxt :file/path :file/name :file/index) candidates)
        matches (->> (:project/assets p)
                     (sort-by key)
                     (keep (fn [[asset-id asset]]
                             (when-let [candidate
                                        (if-let [expected (:asset/sha256 asset)]
                                          (first (filter #(= expected (:file/sha256 %)) ordered))
                                          (first (filter #(= (:asset/name asset) (:file/name %)) ordered)))]
                               {:asset/id asset-id :candidate candidate})))
                     vec)
        matched-ids (set (map :asset/id matches))
        used-indexes (set (map (comp :file/index :candidate) matches))]
    {:relink/matches matches
     :relink/missing (->> (keys (:project/assets p)) (remove matched-ids) sort vec)
     :relink/ignored-paths (->> ordered (remove #(contains? used-indexes (:file/index %))) (map :file/path) vec)}))
(defn missing-asset-ids [p loaded-ids]
  (->> (keys (:project/assets p)) (remove (set loaded-ids)) sort vec))
(defn cache-requests [p]
  (->> (:project/assets p)
       (keep (fn [[asset-id asset]]
               (when-let [sha256 (:asset/sha256 asset)]
                 {:asset/id asset-id :asset/name (:asset/name asset) :asset/sha256 sha256})))
       (sort-by :asset/id) vec))
(defn accept-cache-hit [p asset-id sha256]
  (= sha256 (get-in p [:project/assets asset-id :asset/sha256])))
(defn next-asset-id [p]
  (loop [index 0]
    (let [asset-id (str "asset:" index)]
      (if (contains? (:project/assets p) asset-id) (recur (inc index)) asset-id))))
(defn export-profile [p] (get export-profiles (:project/export-profile p) (:review export-profiles)))
(defn clip-end [c] (+ (:clip/start-frame c) (- (:clip/out-frame c) (:clip/in-frame c))))
(defn duration-frames [p] (reduce max 0 (map clip-end (mapcat :track/clips (:project/tracks p)))))
(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))
(defn timecode [frame fps]
  (let [seconds-total (quot frame fps)
        f (mod frame fps)
        minutes-total (quot seconds-total 60)
        s (mod seconds-total 60)
        h (quot minutes-total 60)
        m (mod minutes-total 60)]
    (str (pad2 h) ":" (pad2 m) ":" (pad2 s) ":" (pad2 f))))
(defn update-clip [p id f] (update p :project/tracks #(mapv (fn [t] (update t :track/clips (fn [cs] (mapv (fn [c] (if (= id (:clip/id c)) (f c) c)) cs)))) %)))
(defn move-clip [p id frame] (update-clip p id #(assoc % :clip/start-frame (max 0 frame))))
(defn trim-clip [p id in-frame out-frame] (if (< in-frame out-frame) (update-clip p id #(assoc % :clip/in-frame in-frame :clip/out-frame out-frame)) p))
(defn trim-edge [p id edge delta]
  (update-clip p id
               (fn [clip]
                 (case edge
                   :in (let [new-in (+ (:clip/in-frame clip) delta)
                             new-start (+ (:clip/start-frame clip) delta)]
                         (if (and (<= 0 new-in) (< new-in (:clip/out-frame clip)) (<= 0 new-start))
                           (assoc clip :clip/in-frame new-in :clip/start-frame new-start) clip))
                   :out (let [new-out (+ (:clip/out-frame clip) delta)]
                          (if (> new-out (:clip/in-frame clip)) (assoc clip :clip/out-frame new-out) clip))
                   clip))))
(defn set-transition [p id transition-type duration-frames]
  (update-clip p id #(assoc % :clip/transition-out {:transition/type transition-type
                                                     :transition/duration-frames (max 0 duration-frames)})))
(defn set-clip-audio-gain [p id gain]
  (update-clip p id #(assoc % :clip/audio-gain (max 0 (min 2 gain)))))
(defn set-clip-eq [p id band db]
  (update-clip p id #(assoc % :clip/audio-eq (assoc (normalize-eq (:clip/audio-eq %)) band (clamp-db db)))))
(defn set-master-eq [p band db]
  (assoc p :project/master-eq (assoc (normalize-eq (:project/master-eq p)) band (clamp-db db))))
(defn ripple-trim-out [p id new-out]
  (let [target (some #(when (= id (:clip/id %)) %) (mapcat :track/clips (:project/tracks p)))
        delta (- new-out (:clip/out-frame target))]
    (if (or (nil? target) (<= new-out (:clip/in-frame target))) p
        (update p :project/tracks
                #(mapv (fn [track]
                         (if (some (fn [clip] (= id (:clip/id clip))) (:track/clips track))
                           (update track :track/clips
                                   (fn [clips] (mapv (fn [clip]
                                                      (cond (= id (:clip/id clip)) (assoc clip :clip/out-frame new-out)
                                                            (> (:clip/start-frame clip) (:clip/start-frame target)) (update clip :clip/start-frame + delta)
                                                            :else clip)) clips))) track)) %)))))
(defn slip-clip [p id delta]
  (update-clip p id (fn [clip] (let [new-in (+ (:clip/in-frame clip) delta) new-out (+ (:clip/out-frame clip) delta)]
                                 (if (neg? new-in) clip (assoc clip :clip/in-frame new-in :clip/out-frame new-out))))))
(defn roll-cut [p left-id right-id delta]
  (let [left (some #(when (= left-id (:clip/id %)) %) (mapcat :track/clips (:project/tracks p)))
        right (some #(when (= right-id (:clip/id %)) %) (mapcat :track/clips (:project/tracks p)))]
    (if (or (nil? left) (nil? right) (<= (+ (:clip/out-frame left) delta) (:clip/in-frame left))
            (neg? (+ (:clip/in-frame right) delta))) p
        (-> p (update-clip left-id #(update % :clip/out-frame + delta))
            (update-clip right-id #(-> % (update :clip/in-frame + delta) (update :clip/start-frame + delta)))))))
(defn split-clip [p id frame new-id]
  (update p :project/tracks
          (fn [tracks]
            (mapv (fn [track]
                    (update track :track/clips
                            (fn [clips]
                              (vec
                               (mapcat
                                (fn [clip]
                                  (let [offset (- frame (:clip/start-frame clip))
                                        duration (- (:clip/out-frame clip) (:clip/in-frame clip))]
                                    (if (and (= id (:clip/id clip)) (pos? offset) (< offset duration))
                                      [(assoc clip :clip/out-frame (+ (:clip/in-frame clip) offset))
                                       (assoc clip :clip/id new-id
                                                   :clip/name (str (:clip/name clip) " B")
                                                   :clip/start-frame frame
                                                   :clip/in-frame (+ (:clip/in-frame clip) offset))]
                                      [clip])))
                                clips)))))
                  tracks))))
(defn validate-project [p] (vec (concat (when-not (= schema (:project/schema p)) [:unsupported-schema]) (when-not (pos-int? (:project/fps p)) [:invalid-fps])
  (when (and (:project/master-eq p) (not (valid-eq? (:project/master-eq p)))) [:invalid-master-eq])
  (for [c (mapcat :track/clips (:project/tracks p)) :when (or (neg? (:clip/start-frame c)) (>= (:clip/in-frame c) (:clip/out-frame c)))] [:invalid-clip (:clip/id c)])
  (for [c (mapcat :track/clips (:project/tracks p)) :when (and (:clip/audio-eq c) (not (valid-eq? (:clip/audio-eq c))))] [:invalid-clip-eq (:clip/id c)]))))
(defn accept-project [value]
  (when (and (map? value) (empty? (validate-project value))) value))
(def recovery-version 2)
(defn valid-history [history]
  (when (and (map? history)
             (vector? (:history/past history))
             (vector? (:history/future history))
             (every? accept-project (concat (:history/past history) (:history/future history))))
    {:history/past (->> (:history/past history) (take-last history-limit) vec)
     :history/future (->> (:history/future history) (take-last history-limit) vec)}))
(defn recovery-envelope
  ([p] (recovery-envelope p empty-history))
  ([p history] {:recovery/version recovery-version :recovery/project p :recovery/history history}))
(defn recover-workspace [value]
  (when (map? value)
    (case (:recovery/version value)
      1 (when-let [p (accept-project (:recovery/project value))] {:project p :history empty-history})
      2 (when-let [p (accept-project (:recovery/project value))]
          (when-let [history (valid-history (:recovery/history value))]
            {:project p :history history}))
      nil)))
(defn recover-project [value]
  (:project (recover-workspace value)))
(def package-version 1)
(def package-max-bytes (* 512 1024 1024))
(defn package-entry-name [index] (str "media/" index))
(defn package-manifest [p media]
  {:package/version package-version :package/project-schema (:project/schema p) :package/media media})
(defn accept-package [p manifest entry-names]
  (let [media (:package/media manifest)
        asset-ids (set (keys (:project/assets p)))]
    (when (and (accept-project p)
               (= package-version (:package/version manifest))
               (= schema (:package/project-schema manifest))
               (map? media)
               (= asset-ids (set (keys media)))
               (= (count media) (count (set (map (comp :entry/name val) media))))
               (every? (fn [[asset-id descriptor]]
                         (and (boolean (re-matches #"media/[0-9]+" (:entry/name descriptor)))
                              (contains? entry-names (:entry/name descriptor))
                              (string? (:media/name descriptor))
                              (string? (:media/type descriptor))
                              (boolean (re-matches #"[0-9a-f]{64}" (:media/sha256 descriptor)))
                              (let [expected (get-in p [:project/assets asset-id :asset/sha256])]
                                (or (nil? expected) (= expected (:media/sha256 descriptor))))))
                       media))
      {:project p :media media})))

(defn video-clips [p]
  (->> (:project/tracks p) (filter #(= :video (:track/type %))) (mapcat :track/clips)
       (filter :clip/source-id) (sort-by :clip/start-frame) vec))

(defn clip-at-frame [p frame]
  (last (filter (fn [clip] (<= (:clip/start-frame clip) frame (dec (clip-end clip)))) (video-clips p))))

(defn render-segments [p]
  (let [fps (:project/fps p)]
    (mapv (fn [clip]
            {:segment/clip-id (:clip/id clip) :segment/source-id (:clip/source-id clip)
             :segment/timeline-start-sec (/ (:clip/start-frame clip) fps)
             :segment/source-start-sec (/ (:clip/in-frame clip) fps)
             :segment/duration-sec (/ (- (:clip/out-frame clip) (:clip/in-frame clip)) fps)
             :segment/audio-gain (or (:clip/audio-gain clip) 1.0)
             :segment/audio-eq (normalize-eq (:clip/audio-eq clip))
             :segment/transition-out (:clip/transition-out clip)})
          (video-clips p))))

(defn timeline-relations [p]
  (mapv (fn [[left right]]
          (let [delta (- (:clip/start-frame right) (clip-end left))]
            {:left (:clip/id left) :right (:clip/id right)
             :relation (cond (pos? delta) :gap (neg? delta) :overlap :else :contiguous)
             :frames (abs delta)}))
        (partition 2 1 (video-clips p))))
