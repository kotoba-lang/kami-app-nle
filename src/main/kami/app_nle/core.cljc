(ns kami.app-nle.core (:require [clojure.string :as str]))
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
  {:review {:profile/name "Review VP8 WebM" :profile/container :webm :profile/extension "webm"
            :profile/mime "video/webm;codecs=vp8,opus" :profile/mimes ["video/webm;codecs=vp8,opus"]
            :profile/video-bps 2000000 :profile/audio-bps 128000}
   :master {:profile/name "Master VP9 WebM" :profile/container :webm :profile/extension "webm"
            :profile/mime "video/webm;codecs=vp9,opus" :profile/mimes ["video/webm;codecs=vp9,opus"]
            :profile/video-bps 8000000 :profile/audio-bps 192000}
   :compact {:profile/name "Compact VP8 WebM" :profile/container :webm :profile/extension "webm"
             :profile/mime "video/webm;codecs=vp8,opus" :profile/mimes ["video/webm;codecs=vp8,opus"]
             :profile/video-bps 1000000 :profile/audio-bps 96000}
   :mp4-master {:profile/name "Master H.264 MP4" :profile/container :mp4 :profile/extension "mp4"
                :profile/mime "video/mp4;codecs=avc1.42E01E,mp4a.40.2"
                :profile/mimes ["video/mp4;codecs=avc1.42E01E,mp4a.40.2" "video/mp4;codecs=avc1,mp4a.40.2" "video/mp4"]
                :profile/video-bps 8000000 :profile/audio-bps 192000}})
(defn export-filename [profile] (str "kami-nle-master." (:profile/extension profile)))
(def proxy-profile {:profile/name "Preview proxy" :profile/mime "video/webm;codecs=vp8,opus"
                    :profile/max-width 640 :profile/max-height 360
                    :profile/video-bps 800000 :profile/audio-bps 96000})
(defn media-url-key [proxy-preview? exporting? asset]
  (if (and proxy-preview? (not exporting?) (:proxy-url asset)) :proxy-url :url))
(def flat-eq {:low-db 0.0 :mid-db 0.0 :high-db 0.0})
(def default-delivery-audio {:delivery/normalize? true :delivery/target-lufs -14.0
                             :delivery/sample-peak-ceiling-db -1.0})
(def default-color-pipeline {:color/input-space :media-native :color/output-space :srgb
                             :color/exposure-stops 0.0 :color/contrast 1.0 :color/saturation 1.0})
(def default-caption-style {:caption/position :bottom :caption/align :center :caption/font-scale 1.0})
(defn clamp-db [db] (max -12.0 (min 12.0 (or db 0.0))))
(defn normalize-eq [eq]
  {:low-db (clamp-db (:low-db eq)) :mid-db (clamp-db (:mid-db eq)) :high-db (clamp-db (:high-db eq))})
(defn valid-eq? [eq]
  (and (map? eq) (every? #(and (number? %) (<= -12 % 12)) ((juxt :low-db :mid-db :high-db) eq))))
(defn project [m] (merge {:project/schema schema :project/fps 30 :project/export-profile :review
                           :project/master-eq flat-eq :project/master-gain 0.9
                           :project/delivery-audio default-delivery-audio
                           :project/color-pipeline default-color-pipeline
                           :project/captions []
                           :project/caption-language "en"
                           :project/assets {} :project/tracks []} m))
(defn delivery-audio [p] (merge default-delivery-audio (:project/delivery-audio p)))
(defn color-pipeline [p] (merge default-color-pipeline (:project/color-pipeline p)))
(defn clamp [minimum maximum value] (max minimum (min maximum value)))
(defn set-delivery-audio [p key value]
  (let [p (assoc p :project/delivery-audio (delivery-audio p))]
    (case key
      :delivery/normalize? (assoc-in p [:project/delivery-audio key] (boolean value))
      :delivery/target-lufs (assoc-in p [:project/delivery-audio key] (clamp -30.0 -5.0 value))
      :delivery/sample-peak-ceiling-db (assoc-in p [:project/delivery-audio key] (clamp -12.0 0.0 value))
      p)))
(defn power->lufs [power]
  (if (pos? power)
    (+ -0.691 (* 10 (#?(:clj Math/log10 :cljs js/Math.log10) power)))
    -96.0))
(defn integrated-lufs [block-powers]
  (let [absolute (filter #(> (power->lufs %) -70) block-powers)]
    (if (empty? absolute) -96.0
        (let [preliminary (power->lufs (/ (reduce + absolute) (count absolute)))
              relative-gate (- preliminary 10)
              gated (filter #(> (power->lufs %) relative-gate) absolute)]
          (if (seq gated) (power->lufs (/ (reduce + gated) (count gated))) -96.0)))))
(defn normalization-gain-db [measured-lufs sample-peak-db target-lufs ceiling-db]
  (if (<= measured-lufs -95)
    0.0
    (min (- target-lufs measured-lufs) (- ceiling-db sample-peak-db))))
(defn set-color-pipeline [p key value]
  (let [p (assoc p :project/color-pipeline (color-pipeline p))]
    (case key
      :color/input-space (if (= :media-native value) (assoc-in p [:project/color-pipeline key] value) p)
      :color/output-space (if (contains? #{:srgb :display-p3} value)
                            (assoc-in p [:project/color-pipeline key] value) p)
      :color/exposure-stops (assoc-in p [:project/color-pipeline key] (clamp -5.0 5.0 value))
      :color/contrast (assoc-in p [:project/color-pipeline key] (clamp 0.0 3.0 value))
      :color/saturation (assoc-in p [:project/color-pipeline key] (clamp 0.0 3.0 value))
      p)))
(defn normalize-caption-style [style]
  {:caption/position (if (contains? #{:top :bottom} (:caption/position style)) (:caption/position style) :bottom)
   :caption/align (if (contains? #{:left :center :right} (:caption/align style)) (:caption/align style) :center)
   :caption/font-scale (clamp 0.5 2.0 (or (:caption/font-scale style) 1.0))})
(def language-pattern #"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
(defn normalize-language [language] (if (and (string? language) (re-matches language-pattern language)) language "en"))
(defn caption-language [caption] (normalize-language (:caption/language caption)))
(defn caption-languages [p] (->> (:project/captions p) (map caption-language) set sort vec))
(defn set-caption-language [p language]
  (if (and (string? language) (re-matches language-pattern language))
    (assoc p :project/caption-language language) p))
(defn add-caption
  ([p caption-id start-frame end-frame text]
   (add-caption p caption-id start-frame end-frame text "en" default-caption-style))
  ([p caption-id start-frame end-frame text style]
   (add-caption p caption-id start-frame end-frame text "en" style))
  ([p caption-id start-frame end-frame text language style]
  (if (some #(= caption-id (:caption/id %)) (:project/captions p))
    p
    (update p :project/captions
            #(->> (conj (vec %) {:caption/id caption-id :caption/start-frame (max 0 start-frame)
                                 :caption/end-frame (max (inc (max 0 start-frame)) end-frame)
                                 :caption/text (str text) :caption/language (normalize-language language)
                                 :caption/style (normalize-caption-style style)})
                  (sort-by (juxt :caption/start-frame :caption/id)) vec)))))
(defn update-caption [p caption-id changes]
  (update p :project/captions
          #(->> % (mapv (fn [caption]
                          (if (= caption-id (:caption/id caption))
                            (let [candidate (merge caption changes) start (max 0 (:caption/start-frame candidate))]
                              (assoc candidate :caption/start-frame start
                                               :caption/end-frame (max (inc start) (:caption/end-frame candidate))
                                               :caption/text (str (:caption/text candidate))
                                               :caption/language (caption-language candidate)
                                               :caption/style (normalize-caption-style (:caption/style candidate))))
                            caption)))
                (sort-by (juxt :caption/start-frame :caption/id)) vec)))
(defn captions-at-frame [p frame]
  (->> (:project/captions p)
       (filter #(and (= (normalize-language (:project/caption-language p)) (caption-language %))
                     (<= (:caption/start-frame %) frame (dec (:caption/end-frame %))))) vec))
(declare pad2)
(defn- pad3 [n] (cond (< n 10) (str "00" n) (< n 100) (str "0" n) :else (str n)))
(defn frame->vtt-time [frame fps]
  (let [raw (* 1000 (/ frame fps))
        millis (long #?(:clj (Math/round (double raw)) :cljs (js/Math.round raw)))
        ms (mod millis 1000) seconds-total (quot millis 1000) seconds (mod seconds-total 60)
        minutes-total (quot seconds-total 60) minutes (mod minutes-total 60) hours (quot minutes-total 60)]
    (str (pad2 hours) ":" (pad2 minutes) ":" (pad2 seconds) "." (pad3 ms))))
(defn- parse-int [value] #?(:clj (Long/parseLong value) :cljs (js/parseInt value 10)))
(defn vtt-time->frame [timecode fps]
  (when-let [[_ hours minutes seconds millis] (re-matches #"^(\d+):(\d{2}):(\d{2})\.(\d{3})$" timecode)]
    (let [total-ms (+ (* (parse-int hours) 3600000) (* (parse-int minutes) 60000)
                      (* (parse-int seconds) 1000) (parse-int millis))]
      (long #?(:clj (Math/round (double (* fps (/ total-ms 1000.0))))
               :cljs (js/Math.round (* fps (/ total-ms 1000.0))))))))
(defn parse-webvtt [text fps language]
  (let [normalized (-> (str text) (str/replace "\r\n" "\n") (str/replace-first #"^\uFEFF" ""))
        blocks (str/split normalized #"\n\s*\n")
        header (some-> (first blocks) str/split-lines first str/trim)
        cue-blocks (remove #(re-find #"^(NOTE|STYLE|REGION)(?:\s|$)" (str/trim %)) (rest blocks))]
    (when (and (pos-int? fps) (boolean (re-find #"^WEBVTT(?:\s|$)" header)))
      (let [cues
            (map-indexed
             (fn [index block]
               (let [lines (str/split-lines block) timing-index (if (str/includes? (first lines) "-->") 0 1)
                     timing (get lines timing-index) [start-token end-side] (when timing (str/split timing #"\s+-->\s+" 2))
                     end-token (some-> end-side (str/split #"\s+" 2) first)
                     start (when start-token (vtt-time->frame start-token fps)) end (when end-token (vtt-time->frame end-token fps))
                     content (str/join "\n" (drop (inc timing-index) lines))]
                 (when (and start end (< start end) (not (str/blank? content)))
                   {:caption/id (str "vtt:" (normalize-language language) ":" index)
                    :caption/start-frame start :caption/end-frame end :caption/text content
                    :caption/language (normalize-language language) :caption/style default-caption-style})))
             cue-blocks)]
        (when (and (seq cues) (every? some? cues)) (vec cues))))))
(defn import-webvtt [p text language]
  (when-let [cues (parse-webvtt text (:project/fps p) language)]
    (let [language (normalize-language language)]
      (-> p
          (update :project/captions #(->> % (remove (fn [caption] (= language (caption-language caption)))) vec))
          (update :project/captions #(->> (concat % cues) (sort-by (juxt :caption/start-frame :caption/id)) vec))
          (assoc :project/caption-language language)))))
(defn webvtt
  ([p] (webvtt p (normalize-language (:project/caption-language p))))
  ([p language]
  (str "WEBVTT\n\n"
       (str/join "\n\n"
                 (map (fn [caption]
                        (str (:caption/id caption) "\n"
                             (frame->vtt-time (:caption/start-frame caption) (:project/fps p)) " --> "
                             (frame->vtt-time (:caption/end-frame caption) (:project/fps p)) "\n"
                             (:caption/text caption)))
                      (filter #(= (normalize-language language) (caption-language %)) (:project/captions p))))
       (when (seq (filter #(= (normalize-language language) (caption-language %)) (:project/captions p))) "\n"))))
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
(defn bind-audio-asset [p asset-id name duration-frames]
  (if (some #(= asset-id (:clip/source-id %))
            (mapcat :track/clips (filter #(= :audio (:track/type %)) (:project/tracks p))))
    p
    (let [duration (max 1 duration-frames)
        audio-index (first (keep-indexed #(when (= :audio (:track/type %2)) %1) (:project/tracks p)))]
    (if (nil? audio-index)
      (update p :project/tracks conj
              {:track/id (str "audio:" (count (:project/tracks p))) :track/name "Audio" :track/type :audio
               :track/clips [{:clip/id (str "audio-clip:" asset-id) :clip/name name :clip/source-id asset-id
                              :clip/start-frame 0 :clip/in-frame 0 :clip/out-frame duration :clip/audio-gain 1.0}]})
      (let [clips (get-in p [:project/tracks audio-index :track/clips])
            missing-index (first (keep-indexed #(when (nil? (:clip/source-id %2)) %1) clips))]
        (if (some? missing-index)
          (update-in p [:project/tracks audio-index :track/clips missing-index]
                     #(assoc % :clip/name (or (:clip/name %) name) :clip/source-id asset-id
                               :clip/in-frame 0 :clip/out-frame duration))
          (update-in p [:project/tracks audio-index :track/clips] conj
                     {:clip/id (str "audio-clip:" asset-id) :clip/name name :clip/source-id asset-id
                      :clip/start-frame 0 :clip/in-frame 0 :clip/out-frame duration :clip/audio-gain 1.0})))))))
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
(defn set-audio-fades [p id fade-in-sec fade-out-sec]
  (update-clip p id #(assoc % :clip/fade-in-sec (max 0 (or fade-in-sec 0))
                              :clip/fade-out-sec (max 0 (or fade-out-sec 0)))))
(defn set-audio-gain-automation [p id points]
  (update-clip p id #(assoc % :clip/audio-gain-automation
                            (->> points
                                 (mapv (fn [{:keys [sec gain]}]
                                         {:automation/sec (max 0 sec) :automation/gain (max 0 (min 2 gain))}))
                                 (sort-by :automation/sec) vec))))
(defn automation-value-at [base points sec]
  (let [ordered (sort-by :automation/sec points)
        before (last (filter #(<= (:automation/sec %) sec) ordered))
        after (first (filter #(> (:automation/sec %) sec) ordered))]
    (cond
      (and before after) (let [span (- (:automation/sec after) (:automation/sec before))
                               ratio (if (pos? span) (/ (- sec (:automation/sec before)) span) 0)]
                           (+ (:automation/gain before) (* ratio (- (:automation/gain after) (:automation/gain before)))))
      before (:automation/gain before)
      after (let [span (:automation/sec after) ratio (if (pos? span) (/ sec span) 0)]
              (+ base (* ratio (- (:automation/gain after) base))))
      :else base)))
(defn fade-value-at [duration fade-in fade-out sec]
  (let [fade-in (min duration (max 0 fade-in)) fade-out (min duration (max 0 fade-out))
        in-value (if (pos? fade-in) (min 1 (/ sec fade-in)) 1)
        out-start (- duration fade-out)
        out-value (if (and (pos? fade-out) (> sec out-start)) (max 0 (/ (- duration sec) fade-out)) 1)]
    (min in-value out-value)))
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
(declare audio-clips)
(defn validate-project [p] (vec (concat (when-not (= schema (:project/schema p)) [:unsupported-schema]) (when-not (pos-int? (:project/fps p)) [:invalid-fps])
  (when (and (:project/master-eq p) (not (valid-eq? (:project/master-eq p)))) [:invalid-master-eq])
  (when (and (:project/master-gain p) (not (<= 0 (:project/master-gain p) 2))) [:invalid-master-gain])
  (when-let [delivery (:project/delivery-audio p)]
    (when-not (and (boolean? (:delivery/normalize? delivery))
                   (<= -30 (:delivery/target-lufs delivery) -5)
                   (<= -12 (:delivery/sample-peak-ceiling-db delivery) 0))
      [:invalid-delivery-audio]))
  (when-let [color (:project/color-pipeline p)]
    (when-not (and (= :media-native (:color/input-space color))
                   (contains? #{:srgb :display-p3} (:color/output-space color))
                   (<= -5 (:color/exposure-stops color) 5)
                   (<= 0 (:color/contrast color) 3)
                   (<= 0 (:color/saturation color) 3))
      [:invalid-color-pipeline]))
  (for [caption (:project/captions p)
        :when (or (not (string? (:caption/id caption))) (str/blank? (:caption/id caption))
                  (boolean (re-find #"[\r\n]" (:caption/id caption)))
                  (not (string? (:caption/text caption))) (str/blank? (:caption/text caption))
                  (and (:caption/language caption)
                       (not (re-matches language-pattern (:caption/language caption))))
                  (and (:caption/style caption)
                       (not= (:caption/style caption) (normalize-caption-style (:caption/style caption))))
                  (neg? (:caption/start-frame caption))
                  (not (< (:caption/start-frame caption) (:caption/end-frame caption))))]
    [:invalid-caption (:caption/id caption)])
  (when (and (:project/caption-language p)
             (not (re-matches language-pattern (:project/caption-language p))))
    [:invalid-caption-language])
  (when (not= (count (:project/captions p)) (count (set (map :caption/id (:project/captions p)))))
    [:duplicate-caption-id])
  (for [c (mapcat :track/clips (:project/tracks p)) :when (or (neg? (:clip/start-frame c)) (>= (:clip/in-frame c) (:clip/out-frame c)))] [:invalid-clip (:clip/id c)])
  (for [c (mapcat :track/clips (:project/tracks p)) :when (and (:clip/audio-eq c) (not (valid-eq? (:clip/audio-eq c))))] [:invalid-clip-eq (:clip/id c)])
  (for [c (audio-clips p) :when (or (neg? (or (:clip/fade-in-sec c) 0)) (neg? (or (:clip/fade-out-sec c) 0)))] [:invalid-audio-fade (:clip/id c)])
  (for [c (audio-clips p) :let [points (:clip/audio-gain-automation c)]
        :when (and (seq points)
                   (or (not (apply <= (map :automation/sec points)))
                       (some #(or (neg? (:automation/sec %)) (not (<= 0 (:automation/gain %) 2))) points)))]
    [:invalid-audio-automation (:clip/id c)]))))
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

(defn audio-clips [p]
  (->> (:project/tracks p) (filter #(= :audio (:track/type %))) (mapcat :track/clips)
       (filter :clip/source-id) (sort-by :clip/start-frame) vec))

(defn audio-segments [p]
  (let [fps (:project/fps p)]
    (mapv (fn [clip]
            {:segment/clip-id (:clip/id clip) :segment/source-id (:clip/source-id clip)
             :segment/timeline-start-sec (/ (:clip/start-frame clip) fps)
             :segment/source-start-sec (/ (:clip/in-frame clip) fps)
             :segment/duration-sec (/ (- (:clip/out-frame clip) (:clip/in-frame clip)) fps)
             :segment/audio-gain (or (:clip/audio-gain clip) 1.0)
             :segment/audio-eq (normalize-eq (:clip/audio-eq clip))
             :segment/fade-in-sec (or (:clip/fade-in-sec clip) 0)
             :segment/fade-out-sec (or (:clip/fade-out-sec clip) 0)
             :segment/gain-automation (vec (:clip/audio-gain-automation clip))})
          (audio-clips p))))

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
