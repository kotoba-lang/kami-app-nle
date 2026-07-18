(ns kami.app-nle.core)
(def schema "kami.eizo-project/v1")
(def export-profiles
  {:review {:profile/name "Review VP8" :profile/mime "video/webm;codecs=vp8,opus" :profile/video-bps 2000000 :profile/audio-bps 128000}
   :master {:profile/name "Master VP9" :profile/mime "video/webm;codecs=vp9,opus" :profile/video-bps 8000000 :profile/audio-bps 192000}
   :compact {:profile/name "Compact VP8" :profile/mime "video/webm;codecs=vp8,opus" :profile/video-bps 1000000 :profile/audio-bps 96000}})
(defn project [m] (merge {:project/schema schema :project/fps 30 :project/export-profile :review :project/tracks []} m))
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
(defn set-transition [p id transition-type duration-frames]
  (update-clip p id #(assoc % :clip/transition-out {:transition/type transition-type
                                                     :transition/duration-frames (max 0 duration-frames)})))
(defn set-clip-audio-gain [p id gain]
  (update-clip p id #(assoc % :clip/audio-gain (max 0 (min 2 gain)))))
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
  (for [c (mapcat :track/clips (:project/tracks p)) :when (or (neg? (:clip/start-frame c)) (>= (:clip/in-frame c) (:clip/out-frame c)))] [:invalid-clip (:clip/id c)]))))

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
             :segment/transition-out (:clip/transition-out clip)})
          (video-clips p))))

(defn timeline-relations [p]
  (mapv (fn [[left right]]
          (let [delta (- (:clip/start-frame right) (clip-end left))]
            {:left (:clip/id left) :right (:clip/id right)
             :relation (cond (pos? delta) :gap (neg? delta) :overlap :else :contiguous)
             :frames (abs delta)}))
        (partition 2 1 (video-clips p))))
