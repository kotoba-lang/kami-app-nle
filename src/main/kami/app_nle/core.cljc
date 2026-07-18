(ns kami.app-nle.core)
(def schema "kami.eizo-project/v1")
(defn project [m] (merge {:project/schema schema :project/fps 30 :project/tracks []} m))
(defn clip-end [c] (+ (:clip/start-frame c) (- (:clip/out-frame c) (:clip/in-frame c))))
(defn duration-frames [p] (reduce max 0 (map clip-end (mapcat :track/clips (:project/tracks p)))))
(defn timecode [frame fps] (let [s (quot frame fps) f (mod frame fps) m (quot s 60) s (mod s 60) h (quot m 60) m (mod m 60)]
  (format "%02d:%02d:%02d:%02d" h m s f)))
(defn update-clip [p id f] (update p :project/tracks #(mapv (fn [t] (update t :track/clips (fn [cs] (mapv (fn [c] (if (= id (:clip/id c)) (f c) c)) cs)))) %)))
(defn move-clip [p id frame] (update-clip p id #(assoc % :clip/start-frame (max 0 frame))))
(defn trim-clip [p id in-frame out-frame] (if (< in-frame out-frame) (update-clip p id #(assoc % :clip/in-frame in-frame :clip/out-frame out-frame)) p))
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
