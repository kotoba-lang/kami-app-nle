(ns kami.app-nle.ui
  (:require [reagent.core :as r] [reagent.dom.client :as rdom] [cljs.reader :as reader] [clojure.string :as str]
            [kami.app-nle.core :as nle]
            [kami.app-nle.cache :as cache]
            ["fflate" :refer [zipSync unzipSync strToU8 strFromU8]]))

(def sample (nle/project {:project/id "demo-cut" :project/name "海辺の予告編" :project/fps 30
 :project/tracks [{:track/id "v2" :track/name "V2 • Titles" :track/type :video :track/clips [{:clip/id "title" :clip/name "OPENING" :clip/start-frame 45 :clip/in-frame 0 :clip/out-frame 75 :clip/color "#fbbf24"}]}
 {:track/id "v1" :track/name "V1 • Picture" :track/type :video :track/clips [{:clip/id "wide" :clip/name "Wide shot" :clip/source-id "asset:0" :clip/start-frame 0 :clip/in-frame 20 :clip/out-frame 170 :clip/color "#38bdf8"} {:clip/id "close" :clip/name "Close up" :clip/source-id "asset:1" :clip/start-frame 150 :clip/in-frame 10 :clip/out-frame 130 :clip/color "#a78bfa"}]}
 {:track/id "a1" :track/name "A1 • Dialogue" :track/type :audio :track/clips [{:clip/id "dialogue" :clip/name "Dialogue.wav" :clip/start-frame 30 :clip/in-frame 0 :clip/out-frame 240 :clip/color "#34d399"}]}]}))
(defonce state (r/atom {:project sample :history nle/empty-history :history-replaying? false :trim-drag nil :trim-preview nil :frame 105 :playing? false :selected "wide" :assets {} :audio-buffers {} :cache-restoring? false :cache-restored-count 0 :directory-searching? false :directory-result nil :proxy-preview? true :proxy-generating nil :proxy-error nil :active-source nil :pending-source-frame 0 :decoded? false :effect :none :exporting? false :analyzing-delivery? false :delivery-report nil :caption-text "" :caption-duration-frames 60 :caption-language "en" :caption-position :bottom :caption-align :center :caption-font-scale 1.0 :review-author "editor" :caption-review-drafts {} :project-error nil :recovered? false :primary-slot :a :audio-meter-db -96}))
(defonce video-a-node (atom nil)) (defonce video-b-node (atom nil))
(defonce canvas-node (atom nil)) (defonce media-url (atom nil))
(defonce export-audio (atom nil))
(defonce lane-audio-runtime (atom []))
(defonce shortcuts-installed? (atom false))
(defonce cache-restore-started? (atom false))
(def recovery-key "kami-app-nle/recovery/v1")
(defn sha256-file! [file]
  (-> (.arrayBuffer file)
      (.then #(.digest (.-subtle js/crypto) "SHA-256" %))
      (.then (fn [digest]
               (apply str (map #(.padStart (.toString % 16) 2 "0")
                               (array-seq (js/Uint8Array. digest))))))))
(defn decode-audio-blob! [blob]
  (let [Ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window)) ctx (new Ctor)]
    (-> (.arrayBuffer blob) (.then #(.decodeAudioData ctx %))
        (.then (fn [buffer] (.close ctx) buffer))
        (.catch (fn [error] (.close ctx) (throw error))))))
(defn install-audio-buffer! [asset-id blob]
  (-> (decode-audio-blob! blob)
      (.then #(do (swap! state assoc-in [:audio-buffers asset-id] %) %))
      (.catch #(swap! state assoc :project-error (str "Audio decode failed: " (.-message %))))))
(defn cache-media! [sha256 name type blob]
  (-> (cache/put! {:sha256 sha256 :name name :type type :blob blob})
      (.catch #(js/console.warn "NLE media cache write failed" %))))
(defn clear-media-cache! []
  (-> (cache/clear!)
      (.then #(swap! state assoc :cache-restored-count 0))
      (.catch #(swap! state assoc :project-error (str "Media cache clear failed: " (.-message %))))))
(declare seek-frame!)
(defn restore-cached-media! []
  (when-not @cache-restore-started?
    (reset! cache-restore-started? true)
    (let [requests (nle/cache-requests (:project @state))]
      (swap! state assoc :cache-restoring? (boolean (seq requests)) :cache-restored-count 0)
      (-> (.all js/Promise
                (clj->js
                 (map (fn [{:asset/keys [id name sha256]}]
                        (-> (cache/get! sha256)
                            (.then (fn [record]
                                     (when record
                                       (let [blob (.-blob record)]
                                         (-> (sha256-file! blob)
                                             (.then (fn [actual]
                                                      (if (and (= sha256 actual)
                                                               (nle/accept-cache-hit (:project @state) id actual))
                                                        {:asset-id id :name name :type (or (.-type record) (.-type blob))
                                                         :sha256 actual :blob blob}
                                                        (-> (cache/delete! sha256) (.then (fn [_] nil)))))))))))
                            (.catch (fn [error] (js/console.warn "NLE media cache read failed" error) nil))))
                      requests)))
          (.then (fn [values]
                   (let [hits (vec (keep identity (array-seq values)))
                         assets (into {} (map (fn [{:keys [asset-id name type sha256 blob]}]
                                                [asset-id {:name name :type type :sha256 sha256 :blob blob
                                                           :url (js/URL.createObjectURL blob)}]) hits))]
                     (swap! state update :assets merge assets)
                     (doseq [{:keys [asset-id type blob]} hits :when (.startsWith (or type "") "audio/")]
                       (install-audio-buffer! asset-id blob))
                     (swap! state assoc :cache-restoring? false :cache-restored-count (count hits))
                     (when (seq hits) (js/setTimeout #(seek-frame! (:frame @state)) 0)))))
          (.catch (fn [error]
                    (js/console.warn "NLE media cache restore failed" error)
                    (swap! state assoc :cache-restoring? false)))))))
(def filters {:none "none" :cinema "contrast(1.18) saturate(1.22)" :mono "grayscale(1) contrast(1.12)" :dream "saturate(1.35) brightness(1.08) blur(1px)"})
(declare primary-video)
(defn color-filter [project]
  (let [{:color/keys [exposure-stops contrast saturation]} (nle/color-pipeline project)]
    (str "brightness(" (js/Math.pow 2 exposure-stops) ") contrast(" contrast ") saturate(" saturation ")")))
(defn canvas-context [canvas project]
  (.getContext canvas "2d" #js {:colorSpace (if (= :display-p3 (:color/output-space (nle/color-pipeline project)))
                                               "display-p3" "srgb")}))
(defn set-canvas-node! [node]
  (reset! canvas-node node)
  (when (and node (primary-video))
    (set! (.-width node) (or (.-videoWidth (primary-video)) 1280))
    (set! (.-height node) (or (.-videoHeight (primary-video)) 720))))
(defn primary-video [] (if (= :a (:primary-slot @state)) @video-a-node @video-b-node))
(defn secondary-video [] (if (= :a (:primary-slot @state)) @video-b-node @video-a-node))
(defn audio-meter-db []
  (if-let [analyser (:analyser @export-audio)]
    (let [samples (js/Float32Array. (.-fftSize analyser))]
      (.getFloatTimeDomainData analyser samples)
      (let [sum (reduce (fn [acc x] (+ acc (* x x))) 0 (array-seq samples))
            rms (js/Math.sqrt (/ sum (.-length samples)))]
        (if (pos? rms) (* 20 (/ (js/Math.log rms) (js/Math.log 10))) -96))) -96))
(defn draw-frame! []
  (when (and (primary-video) @canvas-node)
    (let [v (primary-video) overlay (secondary-video) c @canvas-node ctx (canvas-context c (:project @state))
          segment (:export-segment @state) transition (:segment/transition-out segment)
          elapsed (when segment (- (.-currentTime v) (:segment/source-start-sec segment)))
          remaining (when segment (- (:segment/duration-sec segment) elapsed))
          fade-sec (when transition (/ (:transition/duration-frames transition) (get-in @state [:project :project/fps])))
          fade-alpha (if (and (= :fade (:transition/type transition)) fade-sec (pos? fade-sec) remaining (< remaining fade-sec)) (max 0 (/ remaining fade-sec)) 1)
          dissolve (:dissolve @state)
          progress (when dissolve (min 1 (max 0 (/ (- (js/performance.now) (:started-ms dissolve)) (:duration-ms dissolve)))))
          fps (get-in @state [:project :project/fps])
          caption-frame (if (and segment elapsed)
                          (js/Math.floor (* (+ (:segment/timeline-start-sec segment) elapsed) fps))
                          (:frame @state))]
      (when (>= (.-readyState v) 2)
        (let [creative (get filters (:effect @state) "none")]
          (set! (.-filter ctx) (str (color-filter (:project @state)) (when-not (= "none" creative) (str " " creative)))))
        (set! (.-globalAlpha ctx) 1) (set! (.-fillStyle ctx) "black") (.fillRect ctx 0 0 (.-width c) (.-height c))
        (set! (.-globalAlpha ctx) (if progress (- 1 progress) fade-alpha)) (.drawImage ctx v 0 0 (.-width c) (.-height c))
        (when (and progress overlay (>= (.-readyState overlay) 2))
          (set! (.-globalAlpha ctx) progress) (.drawImage ctx overlay 0 0 (.-width c) (.-height c)))
        (set! (.-filter ctx) "none") (set! (.-globalAlpha ctx) 1)
        (doseq [[index caption] (map-indexed vector (nle/captions-at-frame (:project @state) caption-frame))]
          (let [style (nle/normalize-caption-style (:caption/style caption))
                lines (str/split (:caption/text caption) #"\r?\n")
                font-size (max 18 (js/Math.round (* (.-height c) 0.055 (:caption/font-scale style))))
                line-height (* font-size 1.25) safe-x (* (.-width c) 0.05) safe-y (* (.-height c) 0.05)
                align (:caption/align style) x (case align :left safe-x :right (- (.-width c) safe-x) (/ (.-width c) 2))
                block-height (* (count lines) line-height)
                base-y (if (= :top (:caption/position style))
                         (+ safe-y font-size (* index (+ block-height (* font-size 0.5))))
                         (- (.-height c) safe-y block-height (* index (+ block-height (* font-size 0.5)))))]
            (set! (.-font ctx) (str "600 " font-size "px sans-serif"))
            (set! (.-textAlign ctx) (name align))
            (let [widths (mapv #(.-width (.measureText ctx %)) lines) width (+ 24 (apply max 0 widths))
                  box-x (case align :left (- x 12) :right (- x width -12) (- x (/ width 2)))]
              (set! (.-fillStyle ctx) "rgba(0,0,0,0.72)")
              (.fillRect ctx box-x (- base-y font-size) width (+ block-height (* font-size 0.25)))
              (set! (.-fillStyle ctx) "white")
              (doseq [[line-index line] (map-indexed vector lines)]
                (.fillText ctx line x (+ base-y (* line-index line-height)))))))
        (set! (.-globalAlpha ctx) 1)
        (swap! state assoc :frame caption-frame
               :audio-meter-db (audio-meter-db)))
      (js/requestAnimationFrame draw-frame!))))
(defn active-asset-url [asset]
  (get asset (nle/media-url-key (:proxy-preview? @state) (:exporting? @state) asset)))
(defn activate-source! [source-id source-frame]
  (when-let [asset (get-in @state [:assets source-id])]
    (let [url (active-asset-url asset) video (primary-video)]
      (swap! state assoc :pending-source-frame source-frame :active-source source-id)
      (if (not= url (.-src video)) (do (set! (.-src video) url) (.load video))
          (set! (.-currentTime video) (/ source-frame (get-in @state [:project :project/fps])))))))
(defn seek-frame! [frame] (when-let [clip (nle/clip-at-frame (:project @state) frame)] (activate-source! (:clip/source-id clip) (+ (:clip/in-frame clip) (- frame (:clip/start-frame clip))))))
(defn load-media! [e]
  (let [files (array-seq (.. e -target -files))]
    (-> (reduce (fn [promise file]
                  (.then promise
                         (fn []
                           (-> (sha256-file! file)
                               (.then (fn [sha256]
                                        (let [source-id (or (nle/asset-id-by-signature (:project @state) {:name (.-name file) :sha256 sha256})
                                                            (nle/next-asset-id (:project @state)))
                                              url (js/URL.createObjectURL file)]
                                          (when-let [old-url (get-in @state [:assets source-id :url])] (js/URL.revokeObjectURL old-url))
                                          (swap! state (fn [s] (-> s (assoc-in [:assets source-id] {:name (.-name file) :type (.-type file) :sha256 sha256 :blob file :url url})
                                                                    (update :project nle/register-asset source-id (.-name file) sha256))))
                                          (cache-media! sha256 (.-name file) (.-type file) file))))))))
                (js/Promise.resolve true) files)
        (.then #(when (seq files) (seek-frame! (:frame @state)))))))
(defn load-audio! [event]
  (let [files (array-seq (.. event -target -files))]
    (-> (reduce (fn [promise file]
                  (.then promise
                         (fn []
                           (-> (.all js/Promise #js [(sha256-file! file) (decode-audio-blob! file)])
                               (.then (fn [values]
                                        (let [sha256 (aget values 0) buffer (aget values 1)
                                              source-id (or (nle/asset-id-by-signature (:project @state) {:name (.-name file) :sha256 sha256})
                                                            (nle/next-asset-id (:project @state)))
                                              frames (js/Math.max 1 (js/Math.round (* (.-duration buffer) (get-in @state [:project :project/fps]))))
                                              url (js/URL.createObjectURL file)]
                                          (when-let [old-url (get-in @state [:assets source-id :url])] (js/URL.revokeObjectURL old-url))
                                          (swap! state (fn [s]
                                                         (-> s (assoc-in [:assets source-id]
                                                                         {:name (.-name file) :type (.-type file) :sha256 sha256 :blob file :url url})
                                                             (assoc-in [:audio-buffers source-id] buffer)
                                                             (update :project #(-> % (nle/register-asset source-id (.-name file) sha256)
                                                                                    (nle/bind-audio-asset source-id (.-name file) frames))))))
                                          (cache-media! sha256 (.-name file) (.-type file) file))))))))
                (js/Promise.resolve true) files)
        (.catch #(swap! state assoc :project-error (str "Audio import failed: " (.-message %)))))))
(defn proxy-recorder-options []
  (let [preferred (:profile/mime nle/proxy-profile)
        mime (if (.isTypeSupported js/MediaRecorder preferred) preferred "video/webm;codecs=vp8")]
    #js {:mimeType mime :videoBitsPerSecond (:profile/video-bps nle/proxy-profile)
         :audioBitsPerSecond (:profile/audio-bps nle/proxy-profile)}))
(defn generate-proxy! [source-id]
  (when-let [{:keys [url]} (get-in @state [:assets source-id])]
    (swap! state assoc :proxy-generating source-id :proxy-error nil)
    (let [video (.createElement js/document "video") canvas (.createElement js/document "canvas")
          ctx (.getContext canvas "2d") chunks (array)]
      (set! (.-playsInline video) true) (set! (.-muted video) true) (set! (.-preload video) "auto")
      (set! (.-onerror video) #(swap! state assoc :proxy-generating nil :proxy-error (str "Cannot decode proxy source " source-id)))
      (set! (.-onloadedmetadata video)
            (fn []
              (let [max-w (:profile/max-width nle/proxy-profile) max-h (:profile/max-height nle/proxy-profile)
                    scale (min 1 (/ max-w (.-videoWidth video)) (/ max-h (.-videoHeight video)))
                    width (max 2 (* 2 (js/Math.floor (/ (* scale (.-videoWidth video)) 2))))
                    height (max 2 (* 2 (js/Math.floor (/ (* scale (.-videoHeight video)) 2))))]
                (set! (.-width canvas) width) (set! (.-height canvas) height)
                (let [canvas-stream (.captureStream canvas 30)
                      source-stream (.captureStream video)
                      _ (doseq [track (array-seq (.getAudioTracks source-stream))] (.addTrack canvas-stream track))
                      recorder (js/MediaRecorder. canvas-stream (proxy-recorder-options))
                      drawing? (atom true)
                      started-ms (atom nil)
                      draw! (fn draw! [] (when @drawing? (.drawImage ctx video 0 0 width height) (js/requestAnimationFrame draw!)))]
                  (set! (.-ondataavailable recorder) #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
                  (set! (.-onstop recorder)
                        (fn []
                          (reset! drawing? false)
                          (doseq [track (array-seq (.getTracks canvas-stream))] (.stop track))
                          (doseq [track (array-seq (.getTracks source-stream))] (.stop track))
                          (let [blob (js/Blob. chunks #js {:type (.-mimeType recorder)})]
                            (if (pos? (.-size blob))
                              (let [proxy-url (js/URL.createObjectURL blob)]
                                (when-let [old (get-in @state [:assets source-id :proxy-url])] (js/URL.revokeObjectURL old))
                                (swap! state (fn [s] (-> s (assoc-in [:assets source-id :proxy-url] proxy-url)
                                                          (assoc-in [:assets source-id :proxy-blob] blob)
                                                          (assoc-in [:assets source-id :proxy-size] (.-size blob))
                                                          (assoc :proxy-generating nil))))
                                (seek-frame! (:frame @state)))
                              (swap! state assoc :proxy-generating nil :proxy-error "Proxy encoder produced no media")))))
                  (set! (.-onended video)
                        #(let [elapsed (- (js/performance.now) @started-ms)
                               target (+ 50 (* 1000 (.-duration video)))
                               wait-ms (max 0 (- target elapsed))]
                           (js/setTimeout (fn [] (when (= "recording" (.-state recorder)) (.stop recorder))) wait-ms)))
                  (draw!) (reset! started-ms (js/performance.now)) (.start recorder 100)
                  (-> (.play video) (.catch #(do (when (= "recording" (.-state recorder)) (.stop recorder))
                                                  (swap! state assoc :proxy-generating nil :proxy-error (.-message %)))))))))
      (set! (.-src video) url) (.load video))))
(defn toggle-proxy-preview! [enabled?]
  (swap! state assoc :proxy-preview? enabled? :decoded? false)
  (seek-frame! (:frame @state)))
(defn scan-video-directory! [event]
  (let [files (->> (array-seq (.. event -target -files))
                   (filter #(.startsWith (or (.-type %) "") "video/")) vec)]
    (swap! state assoc :directory-searching? true :directory-result nil :project-error nil)
    (-> (.all js/Promise
              (clj->js
               (map-indexed (fn [index file]
                              (-> (sha256-file! file)
                                  (.then (fn [sha256]
                                           {:file/index index :file/path (or (.-webkitRelativePath file) (.-name file))
                                            :file/name (.-name file) :file/sha256 sha256 :file/ref file})))) files)))
        (.then
         (fn [values]
           (let [plan (nle/directory-relink-plan (:project @state) (array-seq values))]
             (doseq [{:asset/keys [id] :keys [candidate]} (:relink/matches plan)]
               (let [file (:file/ref candidate) url (js/URL.createObjectURL file)]
                 (when-let [old-url (get-in @state [:assets id :url])] (js/URL.revokeObjectURL old-url))
                 (swap! state assoc-in [:assets id]
                        {:name (.-name file) :relative-path (:file/path candidate) :type (.-type file)
                         :sha256 (:file/sha256 candidate) :blob file :url url})
                 (cache-media! (:file/sha256 candidate) (.-name file) (.-type file) file)))
             (swap! state assoc :directory-searching? false
                    :directory-result {:matched (count (:relink/matches plan)) :missing (:relink/missing plan)
                                       :ignored (count (:relink/ignored-paths plan))})
             (when (seq (:relink/matches plan)) (js/setTimeout #(seek-frame! (:frame @state)) 0)))))
        (.catch #(swap! state assoc :directory-searching? false :project-error (str "Directory search failed: " (.-message %)))))))
(defn media-ready! [] (let [v (primary-video) c @canvas-node] (set! (.-width c) (or (.-videoWidth v) 1280)) (set! (.-height c) (or (.-videoHeight v) 720)) (set! (.-currentTime v) (/ (:pending-source-frame @state) (get-in @state [:project :project/fps]))) (swap! state assoc :decoded? true) (draw-frame!)))
(declare ensure-export-audio! set-primary-audio! sync-master-eq! schedule-audio-lanes! stop-lane-audio!)
(defn toggle-play! []
  (when (:decoded? @state)
    (ensure-export-audio!)
    (sync-master-eq!)
    (when-let [clip (nle/clip-at-frame (:project @state) (:frame @state))]
      (when-let [segment (some #(when (= (:clip/id clip) (:segment/clip-id %)) %) (nle/render-segments (:project @state)))]
        (set-primary-audio! segment)))
    (if (:playing? @state)
      (do (.pause (primary-video)) (stop-lane-audio!))
      (do (schedule-audio-lanes! (/ (:frame @state) (get-in @state [:project :project/fps]))) (.play (primary-video))))
    (swap! state update :playing? not)))
(defn download-blob! [blob filename] (let [url (js/URL.createObjectURL blob) a (.createElement js/document "a")] (set! (.-href a) url) (set! (.-download a) filename) (.click a) (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn download-project! []
  (let [blob (js/Blob. #js [(pr-str (:project @state))] #js {:type "application/edn"})
        url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
    (set! (.-href a) url) (set! (.-download a) "kami-nle-project.edn") (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn download-file! [blob filename]
  (let [url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
    (set! (.-href a) url) (set! (.-download a) filename) (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn add-caption-at-playhead! []
  (let [text (:caption-text @state) start (:frame @state) duration (:caption-duration-frames @state)]
    (when (seq text)
      (swap! state update :project nle/add-caption (str "caption:" (js/Date.now)) start (+ start duration) text
             (:caption-language @state)
             {:caption/position (:caption-position @state) :caption/align (:caption-align @state)
              :caption/font-scale (:caption-font-scale @state)})
      (swap! state assoc :caption-text ""))))
(defn export-webvtt! []
  (let [language (nle/normalize-language (get-in @state [:project :project/caption-language]))]
    (download-file! (js/Blob. #js [(nle/webvtt (:project @state) language)] #js {:type "text/vtt;charset=utf-8"})
                    (str "kami-nle-captions-" language ".vtt"))))
(defn export-imsc1! []
  (let [language (nle/normalize-language (get-in @state [:project :project/caption-language]))]
    (download-file! (js/Blob. #js [(nle/imsc1 (:project @state) language)]
                                  #js {:type "application/ttml+xml;charset=utf-8"})
                    (str "kami-nle-captions-" language ".imsc.xml"))))
(defn import-webvtt! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (-> (.text file)
        (.then (fn [text]
                 (if-let [project (nle/import-webvtt (:project @state) text (:caption-language @state))]
                   (swap! state assoc :project project :project-error nil)
                   (swap! state assoc :project-error "WebVTT import failed: invalid header, cue timing or text"))))
        (.catch #(swap! state assoc :project-error (str "WebVTT import failed: " (.-message %)))))))
(def ttml-namespace "http://www.w3.org/ns/ttml")
(def ttml-parameter-namespace "http://www.w3.org/ns/ttml#parameter")
(def ttml-styling-namespace "http://www.w3.org/ns/ttml#styling")
(defn imsc-node-text [node]
  (apply str
         (map (fn [child]
                (cond (= 3 (.-nodeType child)) (.-nodeValue child)
                      (= 4 (.-nodeType child)) (.-nodeValue child)
                      (= "br" (.-localName child)) "\n"
                      :else (imsc-node-text child)))
              (array-seq (.-childNodes node)))))
(defn parse-imsc-document [text fps]
  (when (and (string? text) (not (re-find #"(?i)<!DOCTYPE" text)))
    (let [document (.parseFromString (js/DOMParser.) text "application/xml")
          root (.-documentElement document)
          profile (.getAttributeNS root ttml-parameter-namespace "contentProfiles")
          language (or (.getAttribute root "xml:lang") "en")
          nodes (array-seq (.getElementsByTagNameNS document ttml-namespace "p"))]
      (when (and (zero? (.-length (.querySelectorAll document "parsererror")))
                 (= "tt" (.-localName root)) (= ttml-namespace (.-namespaceURI root))
                 (str/includes? (or profile "") "http://www.w3.org/ns/ttml/profile/imsc1.2/text")
                 (seq nodes))
        (let [cues (mapv (fn [node]
                           (let [start (nle/imsc-time->frame (.getAttribute node "begin") fps)
                                 end (nle/imsc-time->frame (.getAttribute node "end") fps)
                                 region (.getAttribute node "region")
                                 align (.getAttributeNS node ttml-styling-namespace "textAlign")
                                 font-size (.getAttributeNS node ttml-styling-namespace "fontSize")]
                             {:caption/start-frame start :caption/end-frame end
                              :caption/text (str/trim (imsc-node-text node))
                              :caption/style {:caption/position (keyword (if (= region "top") "top" "bottom"))
                                              :caption/align (keyword (if (#{"left" "center" "right"} align) align "center"))
                                              :caption/font-scale (if (and font-size (str/ends-with? font-size "%"))
                                                                    (/ (js/parseFloat font-size) 100) 1.0)}})) nodes)]
          {:language language :cues cues})))))
(defn import-imsc1! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (-> (.text file)
        (.then (fn [text]
                 (if-let [{:keys [language cues]} (parse-imsc-document text (get-in @state [:project :project/fps]))]
                   (if-let [project (nle/import-imsc-cues (:project @state) language cues)]
                     (swap! state assoc :project project :caption-language language :project-error nil)
                     (swap! state assoc :project-error "IMSC import failed: invalid cue timing or text"))
                   (swap! state assoc :project-error "IMSC import failed: require safe IMSC 1.2 Text Profile XML"))))
        (.catch #(swap! state assoc :project-error (str "IMSC import failed: " (.-message %)))))))
(defn export-package! []
  (let [project (:project @state) asset-ids (sort (keys (:project/assets project)))
        missing (vec (remove #(get-in @state [:assets % :blob]) asset-ids))
        total (reduce + 0 (keep #(some-> (get-in @state [:assets % :blob]) .-size) asset-ids))]
    (cond
      (seq missing) (swap! state assoc :project-error (str "Relink media before packaging: " (pr-str missing)))
      (> total nle/package-max-bytes) (swap! state assoc :project-error "Package media exceeds the 512 MiB browser limit")
      :else
      (-> (.all js/Promise
                (clj->js
                 (map-indexed
                  (fn [index asset-id]
                    (let [asset (get-in @state [:assets asset-id]) blob (:blob asset)]
                      (-> (.all js/Promise #js [(.arrayBuffer blob) (sha256-file! blob)])
                          (.then (fn [values]
                                   {:asset-id asset-id :entry-name (nle/package-entry-name index)
                                    :name (:name asset) :type (or (:type asset) (.-type blob) "application/octet-stream")
                                    :sha256 (aget values 1) :bytes (js/Uint8Array. (aget values 0))})))))
                  asset-ids)))
          (.then
           (fn [values]
             (let [items (array-seq values)
                   packaged-project (reduce (fn [p {:keys [asset-id sha256]}]
                                              (assoc-in p [:project/assets asset-id :asset/sha256] sha256)) project items)
                   media (into {} (map (fn [{:keys [asset-id entry-name name type sha256]}]
                                         [asset-id {:entry/name entry-name :media/name name :media/type type :media/sha256 sha256}]) items))
                   entries #js {}]
               (aset entries "project.edn" (strToU8 (pr-str packaged-project)))
               (aset entries "media.edn" (strToU8 (pr-str (nle/package-manifest packaged-project media))))
               (doseq [{:keys [entry-name bytes]} items] (aset entries entry-name bytes))
               (download-file! (js/Blob. #js [(zipSync entries #js {:level 0})] #js {:type "application/zip"})
                               "kami-nle-package.kami.zip")
               (swap! state assoc :project-error nil))))
          (.catch #(swap! state assoc :project-error (str "Package export failed: " (.-message %))))))))
(defn unzip-package [array-buffer]
  (let [expanded-bytes (atom 0)]
    (unzipSync (js/Uint8Array. array-buffer)
               #js {:filter (fn [entry]
                              (swap! expanded-bytes + (.-originalSize entry))
                              (when (> @expanded-bytes nle/package-max-bytes)
                                (throw (js/Error. "Expanded package exceeds the 512 MiB browser limit")))
                              true)})))
(defn open-package! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (if (> (.-size file) nle/package-max-bytes)
      (swap! state assoc :project-error "Package exceeds the 512 MiB browser limit")
      (-> (.arrayBuffer file)
          (.then
           (fn [array-buffer]
             (let [entries (unzip-package array-buffer) names (set (js->clj (js/Object.keys entries)))
                   project-entry (aget entries "project.edn") manifest-entry (aget entries "media.edn")]
               (when-not (and project-entry manifest-entry) (throw (js/Error. "Package metadata is missing")))
               (let [project (reader/read-string (strFromU8 project-entry))
                     manifest (reader/read-string (strFromU8 manifest-entry))
                     accepted (nle/accept-package project manifest names)]
                 (when-not accepted (throw (js/Error. "Package contract is invalid")))
                 (-> (.all js/Promise
                           (clj->js
                            (map (fn [[asset-id descriptor]]
                                   (let [blob (js/Blob. #js [(aget entries (:entry/name descriptor))]
                                                        #js {:type (:media/type descriptor)})]
                                     (-> (sha256-file! blob)
                                         (.then (fn [sha256]
                                                  (when-not (= sha256 (:media/sha256 descriptor))
                                                    (throw (js/Error. (str "Media checksum mismatch: " asset-id))))
                                                  {:asset-id asset-id :descriptor descriptor :blob blob})))))
                                 (:media accepted))))
                     (.then (fn [values] {:project (:project accepted) :items (array-seq values)})))))))
          (.then
           (fn [{:keys [project items]}]
             (doseq [[_ asset] (:assets @state) k [:url :proxy-url]] (when-let [url (get asset k)] (js/URL.revokeObjectURL url)))
             (let [first-clip (first (nle/video-clips project))
                   assets (into {} (map (fn [{:keys [asset-id descriptor blob]}]
                                          [asset-id {:name (:media/name descriptor) :type (:media/type descriptor)
                                                     :sha256 (:media/sha256 descriptor) :blob blob
                                                     :url (js/URL.createObjectURL blob)}]) items))]
               (doseq [{:keys [descriptor blob]} items]
                 (cache-media! (:media/sha256 descriptor) (:media/name descriptor) (:media/type descriptor) blob))
               (swap! state assoc :project project :selected (:clip/id first-clip)
                      :frame (or (:clip/start-frame first-clip) 0) :decoded? false :playing? false
                      :project-error nil :assets assets :audio-buffers {})
               (doseq [[asset-id asset] assets :when (.startsWith (or (:type asset) "") "audio/")]
                 (install-audio-buffer! asset-id (:blob asset)))
               (seek-frame! (:frame @state)))))
          (.catch #(swap! state assoc :project-error (str "Package import failed: " (.-message %))))))))
(defn load-project! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (-> (.text file)
        (.then (fn [text]
                 (try
                   (if-let [project (nle/accept-project (reader/read-string text))]
                     (let [first-clip (first (nle/video-clips project))]
                       (doseq [[_ asset] (:assets @state) k [:url :proxy-url]] (when-let [url (get asset k)] (js/URL.revokeObjectURL url)))
                       (swap! state assoc :project project :selected (:clip/id first-clip)
                              :frame (or (:clip/start-frame first-clip) 0) :decoded? false :playing? false :project-error nil :assets {} :audio-buffers {}))
                     (swap! state assoc :project-error "Unsupported or invalid NLE project"))
                   (catch :default error (swap! state assoc :project-error (.-message error)))))))))
(defn restore-recovery! []
  (when-let [text (.getItem js/localStorage recovery-key)]
    (try
      (if-let [{:keys [project history]} (nle/recover-workspace (reader/read-string text))]
        (let [first-clip (first (nle/video-clips project))]
          (swap! state assoc :project project :history history :selected (:clip/id first-clip)
                 :frame (or (:clip/start-frame first-clip) 0) :decoded? false :recovered? true :project-error nil))
        (do (.removeItem js/localStorage recovery-key)
            (swap! state assoc :project-error "Discarded invalid recovery data")))
      (catch :default _ (.removeItem js/localStorage recovery-key)))))
(defn install-autosave! []
  (add-watch state ::autosave
             (fn [_ _ old new]
               (when (or (not= (:project old) (:project new)) (not= (:history old) (:history new)))
                 (js/queueMicrotask
                  (fn []
                    (try (.setItem js/localStorage recovery-key
                                   (pr-str (nle/recovery-envelope (:project @state) (:history @state))))
                         (catch :default error (js/console.warn "NLE autosave failed" error)))))))))
(defn install-history! []
  (add-watch state ::history
             (fn [_ _ old new]
               (when (and (not= (:project old) (:project new)) (not (:history-replaying? new)))
                 (swap! state update :history nle/record-history (:project old))))))
(defn undo! []
  (let [{:keys [project history]} (nle/undo-project (:project @state) (:history @state))]
    (swap! state assoc :project project :history history :history-replaying? true)
    (swap! state assoc :history-replaying? false)))
(defn redo! []
  (let [{:keys [project history]} (nle/redo-project (:project @state) (:history @state))]
    (swap! state assoc :project project :history history :history-replaying? true)
    (swap! state assoc :history-replaying? false)))
(defn install-shortcuts! []
  (when-not @shortcuts-installed?
    (.addEventListener js/window "keydown"
      (fn [event]
        (when (and (or (.-metaKey event) (.-ctrlKey event)) (= "z" (.toLowerCase (.-key event))))
          (.preventDefault event) (if (.-shiftKey event) (redo!) (undo!)))))
    (reset! shortcuts-installed? true)))
(defn supported-profile-mime [profile]
  (first (filter #(.isTypeSupported js/MediaRecorder %) (:profile/mimes profile))))
(defn profile-supported? [profile] (boolean (supported-profile-mime profile)))
(defn resolved-recorder-profile [project]
  (let [profile (nle/export-profile project)]
    (when-let [mime (supported-profile-mime profile)]
      (assoc profile :profile/actual-mime mime :profile/filename (nle/export-filename profile)))))
(defn recorder-options [profile]
  #js {:mimeType (:profile/actual-mime profile) :videoBitsPerSecond (:profile/video-bps profile)
       :audioBitsPerSecond (:profile/audio-bps profile)})
(defn configure-eq-nodes! [low mid high]
  (set! (.-type low) "lowshelf") (set! (.. low -frequency -value) 120)
  (set! (.-type mid) "peaking") (set! (.. mid -frequency -value) 1000) (set! (.. mid -Q -value) 0.8)
  (set! (.-type high) "highshelf") (set! (.. high -frequency -value) 8000))
(defn apply-eq! [{:keys [low mid high]} eq]
  (let [{:keys [low-db mid-db high-db]} (nle/normalize-eq eq)]
    (set! (.. low -gain -value) low-db) (set! (.. mid -gain -value) mid-db) (set! (.. high -gain -value) high-db)))
(defn ensure-export-audio! []
  (or @export-audio
      (let [Ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window)) ctx (new Ctor)
            source-a (.createMediaElementSource ctx @video-a-node)
            source-b (.createMediaElementSource ctx @video-b-node)
            low-a (.createBiquadFilter ctx) mid-a (.createBiquadFilter ctx) high-a (.createBiquadFilter ctx)
            low-b (.createBiquadFilter ctx) mid-b (.createBiquadFilter ctx) high-b (.createBiquadFilter ctx)
            master-low (.createBiquadFilter ctx) master-mid (.createBiquadFilter ctx) master-high (.createBiquadFilter ctx)
            gain-a (.createGain ctx) gain-b (.createGain ctx) master (.createGain ctx) analyser (.createAnalyser ctx)
            loudness-high-pass (.createBiquadFilter ctx) loudness-shelf (.createBiquadFilter ctx)
            loudness-splitter (.createChannelSplitter ctx 2)
            loudness-left (.createAnalyser ctx) loudness-right (.createAnalyser ctx)
            peak-splitter (.createChannelSplitter ctx 2) peak-left (.createAnalyser ctx) peak-right (.createAnalyser ctx)
            measurement-sink (.createGain ctx)
            destination (.createMediaStreamDestination ctx)]
        (configure-eq-nodes! low-a mid-a high-a) (configure-eq-nodes! low-b mid-b high-b)
        (configure-eq-nodes! master-low master-mid master-high)
        (set! (.. gain-a -gain -value) 1) (set! (.. gain-b -gain -value) 0)
        (set! (.. master -gain -value) (or (get-in @state [:project :project/master-gain]) 0.9))
        (doseq [node [analyser loudness-left loudness-right peak-left peak-right]] (set! (.-fftSize node) 512))
        (set! (.-type loudness-high-pass) "highpass") (set! (.. loudness-high-pass -frequency -value) 38)
        (set! (.. loudness-high-pass -Q -value) 0.5)
        (set! (.-type loudness-shelf) "highshelf") (set! (.. loudness-shelf -frequency -value) 1682)
        (set! (.. loudness-shelf -gain -value) 4)
        (set! (.. measurement-sink -gain -value) 0)
        (.connect source-a low-a) (.connect low-a mid-a) (.connect mid-a high-a) (.connect high-a gain-a)
        (.connect source-b low-b) (.connect low-b mid-b) (.connect mid-b high-b) (.connect high-b gain-b)
        (.connect gain-a master-low) (.connect gain-b master-low)
        (.connect master-low master-mid) (.connect master-mid master-high) (.connect master-high master) (.connect master analyser)
        (.connect analyser destination) (.connect analyser (.-destination ctx))
        (.connect master loudness-high-pass) (.connect loudness-high-pass loudness-shelf) (.connect loudness-shelf loudness-splitter)
        (.connect loudness-splitter loudness-left 0) (.connect loudness-splitter loudness-right 1)
        (.connect master peak-splitter) (.connect peak-splitter peak-left 0) (.connect peak-splitter peak-right 1)
        (doseq [node [loudness-left loudness-right peak-left peak-right]] (.connect node measurement-sink))
        (.connect measurement-sink (.-destination ctx))
        (let [runtime {:context ctx :destination destination :node-a @video-a-node :node-b @video-b-node
                       :gain-a gain-a :gain-b gain-b :eq-a {:low low-a :mid mid-a :high high-a}
                       :eq-b {:low low-b :mid mid-b :high high-b}
                       :master-eq {:low master-low :mid master-mid :high master-high}
                       :master-input master-low :master master :analyser analyser
                       :loudness-analysers [loudness-left loudness-right] :peak-analysers [peak-left peak-right]}]
          (apply-eq! (:master-eq runtime) (get-in @state [:project :project/master-eq]))
          (reset! export-audio runtime)))))
(defn gain-for-video [video]
  (let [{:keys [node-a gain-a gain-b]} @export-audio] (if (identical? video node-a) gain-a gain-b)))
(defn eq-for-video [video]
  (let [{:keys [node-a eq-a eq-b]} @export-audio] (if (identical? video node-a) eq-a eq-b)))
(defn set-video-eq! [video eq] (when @export-audio (apply-eq! (eq-for-video video) eq)))
(defn set-master-eq! [band db]
  (swap! state update :project nle/set-master-eq band db)
  (when @export-audio (apply-eq! (:master-eq @export-audio) (get-in @state [:project :project/master-eq]))))
(defn sync-master-eq! []
  (when @export-audio (apply-eq! (:master-eq @export-audio) (get-in @state [:project :project/master-eq]))))
(defn stop-lane-audio! []
  (doseq [{:keys [source nodes]} @lane-audio-runtime]
    (try (.stop source) (catch :default _ nil))
    (doseq [node nodes] (try (.disconnect node) (catch :default _ nil))))
  (reset! lane-audio-runtime []))
(defn schedule-gain-automation! [param segment elapsed start-at]
  (let [base (:segment/audio-gain segment)
        points (:segment/gain-automation segment)]
    (.setValueAtTime param (nle/automation-value-at base points elapsed) start-at)
    (doseq [{:automation/keys [sec gain]} points :when (> sec elapsed)]
      (.linearRampToValueAtTime param gain (+ start-at (- sec elapsed))))))
(defn schedule-fade-envelope! [param segment elapsed start-at]
  (let [duration (:segment/duration-sec segment)
        fade-in (min duration (:segment/fade-in-sec segment))
        fade-out (min duration (:segment/fade-out-sec segment))
        out-start (- duration fade-out)
        intersection (when (pos? (+ fade-in fade-out))
                       (/ (* duration fade-in) (+ fade-in fade-out)))
        breakpoints (->> [fade-in out-start intersection duration]
                         (filter some?) (filter #(and (> % elapsed) (<= % duration)))
                         distinct sort)]
    (.setValueAtTime param (nle/fade-value-at duration fade-in fade-out elapsed) start-at)
    (doseq [sec breakpoints]
      (.linearRampToValueAtTime param
                                (nle/fade-value-at duration fade-in fade-out sec)
                                (+ start-at (- sec elapsed))))))
(defn schedule-audio-lanes! [from-sec]
  (stop-lane-audio!)
  (when-let [{:keys [context master-input]} @export-audio]
    (let [now (+ (.-currentTime context) 0.03)]
      (doseq [segment (nle/audio-segments (:project @state))
              :let [timeline-start (:segment/timeline-start-sec segment)
                    elapsed (max 0 (- from-sec timeline-start))
                    buffer (get-in @state [:audio-buffers (:segment/source-id segment)])
                    offset (+ (:segment/source-start-sec segment) elapsed)
                    duration (when buffer (min (- (:segment/duration-sec segment) elapsed)
                                               (max 0 (- (.-duration buffer) offset))))]
              :when (and buffer (pos? duration) (> (+ timeline-start (:segment/duration-sec segment)) from-sec))]
        (let [source (.createBufferSource context) low (.createBiquadFilter context) mid (.createBiquadFilter context)
              high (.createBiquadFilter context) automation-gain (.createGain context) fade-gain (.createGain context)
              delay (max 0 (- timeline-start from-sec)) start-at (+ now delay)]
          (set! (.-buffer source) buffer) (configure-eq-nodes! low mid high)
          (apply-eq! {:low low :mid mid :high high} (:segment/audio-eq segment))
          (schedule-gain-automation! (.-gain automation-gain) segment elapsed start-at)
          (schedule-fade-envelope! (.-gain fade-gain) segment elapsed start-at)
          (.connect source low) (.connect low mid) (.connect mid high)
          (.connect high automation-gain) (.connect automation-gain fade-gain) (.connect fade-gain master-input)
          (.start source start-at offset duration)
          (swap! lane-audio-runtime conj {:source source :nodes [source low mid high automation-gain fade-gain]}))))))
(defn set-master-gain! [gain]
  (swap! state assoc-in [:project :project/master-gain] (max 0 (min 2 gain)))
  (when-let [master (:master @export-audio)] (set! (.. master -gain -value) gain)))
(defn set-primary-audio! [segment]
  (when @export-audio
    (set-video-eq! (primary-video) (:segment/audio-eq segment))
    (set! (.-value (.-gain (gain-for-video (primary-video)))) (or (:segment/audio-gain segment) 1))
    (set! (.-value (.-gain (gain-for-video (secondary-video)))) 0)))
(defn prepare-video! [video segment]
  (js/Promise.
   (fn [resolve reject]
     (if-let [url (get-in @state [:assets (:segment/source-id segment) :url])]
       (do
         (set! (.-onerror video) #(reject (js/Error. (str "Cannot decode " (:segment/source-id segment)))))
         (set! (.-onloadedmetadata video)
               #(do (set! (.-currentTime video) (:segment/source-start-sec segment))
                    (set! (.-onseeked video)
                          (fn [] (set! (.-onseeked video) nil) (resolve true)))))
         (set! (.-src video) url) (.load video))
       (reject (js/Error. (str "Missing asset " (:segment/source-id segment))))))))
(defn swap-video-nodes! []
  (swap! state update :primary-slot {:a :b :b :a}))
(declare play-timeline-from!)
(defn play-timeline-from! [segments index preplayed-sec]
  (if (>= index (count segments)) (js/Promise.resolve true)
      (let [segment (nth segments index) next-segment (get segments (inc index))
            transition (:segment/transition-out segment)
            dissolve-sec (if (and next-segment (= :dissolve (:transition/type transition)))
                           (/ (:transition/duration-frames transition) (get-in @state [:project :project/fps])) 0)
            remaining-sec (max 0 (- (:segment/duration-sec segment) preplayed-sec))
            start-dissolve-sec (max 0 (- remaining-sec dissolve-sec))]
        (swap! state assoc :export-segment segment)
        (if (pos? dissolve-sec)
          (-> (prepare-video! (secondary-video) next-segment)
              (.then (fn []
                       (.play (primary-video))
                       (js/Promise.
                        (fn [resolve _]
                          (js/setTimeout
                           (fn []
                             (let [ctx (:context @export-audio) now (.-currentTime ctx)
                                   primary-param (.-gain (gain-for-video (primary-video)))
                                   secondary-param (.-gain (gain-for-video (secondary-video)))
                                   primary-gain (or (:segment/audio-gain segment) 1)
                                   secondary-gain (or (:segment/audio-gain next-segment) 1)]
                               (set-video-eq! (secondary-video) (:segment/audio-eq next-segment))
                               (.setValueAtTime primary-param primary-gain now) (.linearRampToValueAtTime primary-param 0 (+ now dissolve-sec))
                               (.setValueAtTime secondary-param 0 now) (.linearRampToValueAtTime secondary-param secondary-gain (+ now dissolve-sec)))
                             (.play (secondary-video))
                             (swap! state assoc :dissolve {:started-ms (js/performance.now) :duration-ms (* 1000 dissolve-sec)}))
                           (* 1000 start-dissolve-sec))
                          (js/setTimeout
                           (fn [] (.pause (primary-video)) (swap! state dissoc :dissolve) (swap-video-nodes!) (resolve true))
                           (* 1000 remaining-sec))))))
              (.then #(play-timeline-from! segments (inc index) dissolve-sec)))
          (do (set-primary-audio! segment) (.play (primary-video))
              (js/Promise.
               (fn [resolve reject]
                 (js/setTimeout
                  (fn []
                    (.pause (primary-video))
                    (if next-segment
                      (-> (prepare-video! (secondary-video) next-segment)
                          (.then (fn [] (swap-video-nodes!) (resolve true))) (.catch reject))
                      (resolve true)))
                  (* 1000 remaining-sec))))
              )))))
(defn play-segments! [segments]
  (-> (prepare-video! (primary-video) (first segments))
      (.then #(do (when (or (:exporting? @state) (:analyzing-delivery? @state)) (schedule-audio-lanes! 0))
                  (play-timeline-from! segments 0 0)))))
(defn analyser-power [analyser]
  (let [samples (js/Float32Array. (.-fftSize analyser))]
    (.getFloatTimeDomainData analyser samples)
    (/ (reduce (fn [sum value] (+ sum (* value value))) 0 (array-seq samples)) (.-length samples))))
(defn analyser-peak [analyser]
  (let [samples (js/Float32Array. (.-fftSize analyser))]
    (.getFloatTimeDomainData analyser samples)
    (reduce (fn [peak value] (max peak (js/Math.abs value))) 0 (array-seq samples))))
(defn sample-delivery-block [loudness-analysers peak-analysers]
  {:power (reduce + (map analyser-power loudness-analysers))
   :peak (reduce max 0 (map analyser-peak peak-analysers))})
(defn analyze-delivery! []
  (let [segments (nle/render-segments (:project @state))
        {:keys [context loudness-analysers peak-analysers master]} (ensure-export-audio!)
        blocks (atom []) peak (atom 0) timer (atom nil)
        base-gain (or (get-in @state [:project :project/master-gain]) 0.9)
        settings (nle/delivery-audio (:project @state))]
    (.resume context) (sync-master-eq!) (set! (.. master -gain -value) base-gain)
    (swap! state assoc :analyzing-delivery? true :playing? true :delivery-report nil)
    (reset! timer (js/setInterval
                   (fn [] (let [{:keys [power] block-peak :peak} (sample-delivery-block loudness-analysers peak-analysers)]
                            (swap! blocks conj power) (swap! peak max block-peak))) 100))
    (-> (play-segments! segments)
        (.then (fn [_]
                 (let [lufs (nle/integrated-lufs @blocks)
                       peak-db (if (pos? @peak) (* 20 (js/Math.log10 @peak)) -96.0)
                       gain-db (nle/normalization-gain-db lufs peak-db
                                                       (:delivery/target-lufs settings)
                                                       (:delivery/sample-peak-ceiling-db settings))
                       report {:loudness/lufs lufs :sample-peak/dbfs peak-db
                               :normalization/gain-db gain-db
                               :delivery/lufs (+ lufs gain-db)
                               :delivery/sample-peak-dbfs (+ peak-db gain-db)}]
                   (swap! state assoc :delivery-report report) report)))
        (.finally (fn [] (when @timer (js/clearInterval @timer)) (stop-lane-audio!)
                    (swap! state assoc :analyzing-delivery? false :playing? false))))))
(defn record-production! [gain-db]
  (let [project (:project @state) profile (resolved-recorder-profile project)
        segments (nle/render-segments project) audio-segments (nle/audio-segments project)]
    (when-not profile
      (swap! state assoc :project-error (str "Selected export profile is unsupported: "
                                             (:profile/name (nle/export-profile project)))))
    (when (and (seq segments) (every? #(get-in @state [:assets (:segment/source-id %) :url]) segments)
               profile (every? #(get-in @state [:audio-buffers (:segment/source-id %)]) audio-segments))
      (let [stream (.captureStream @canvas-node (get-in @state [:project :project/fps]))
            {:keys [context destination]} (ensure-export-audio!)
            base-gain (or (get-in @state [:project :project/master-gain]) 0.9)
            delivery-gain (* base-gain (js/Math.pow 10 (/ gain-db 20)))
            audio-track (first (array-seq (.getAudioTracks (.-stream destination))))
            _ (.addTrack stream audio-track)
            recorder (js/MediaRecorder. stream (recorder-options profile)) chunks (array)]
        (.resume context) (sync-master-eq!) (set! (.. (:master @export-audio) -gain -value) delivery-gain)
        (set-primary-audio! (first segments)) (swap! state assoc :exporting? true :playing? true)
        (set! (.-ondataavailable recorder) #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
        (set! (.-onstop recorder) #(do (stop-lane-audio!)
                                      (download-blob! (js/Blob. chunks #js {:type (:profile/actual-mime profile)})
                                                      (:profile/filename profile))
                                      (swap! state dissoc :export-segment)
                                      (swap! state assoc :exporting? false :playing? false)))
        (.start recorder 250)
        (-> (play-segments! segments) (.then #(.stop recorder))
            (.catch (fn [error] (js/console.error error) (.stop recorder))))))))
(defn export-production! []
  (if-not (resolved-recorder-profile (:project @state))
    (swap! state assoc :project-error "Selected export profile is unsupported in this browser")
    (if (:delivery/normalize? (nle/delivery-audio (:project @state)))
      (-> (analyze-delivery!) (.then (fn [report] (record-production! (:normalization/gain-db report))))
          (.catch #(swap! state assoc :project-error (str "Delivery analysis failed: " (.-message %)))))
      (record-production! 0))))
(declare move-trim! finish-trim! cancel-trim!)
(defn remove-trim-listeners! []
  (.removeEventListener js/window "pointermove" move-trim!)
  (.removeEventListener js/window "pointerup" finish-trim!)
  (.removeEventListener js/window "pointercancel" cancel-trim!))
(defn start-trim! [event clip edge total]
  (.preventDefault event) (.stopPropagation event)
  (let [target (.-currentTarget event) lane (.closest target ".lane") rect (.getBoundingClientRect lane)]
    (try (.setPointerCapture target (.-pointerId event)) (catch :default _ nil))
    (.addEventListener js/window "pointermove" move-trim!)
    (.addEventListener js/window "pointerup" finish-trim!)
    (.addEventListener js/window "pointercancel" cancel-trim!)
    (swap! state assoc :selected (:clip/id clip) :trim-preview (:project @state)
           :trim-drag {:pointer-id (.-pointerId event) :clip-id (:clip/id clip) :edge edge
                       :origin-x (.-clientX event) :lane-width (.-width rect) :total total
                       :base-project (:project @state)})))
(defn move-trim! [event]
  (when-let [{:keys [pointer-id clip-id edge origin-x lane-width total base-project]} (:trim-drag @state)]
    (when (= pointer-id (.-pointerId event))
      (.preventDefault event)
      (let [delta (js/Math.round (* (/ (- (.-clientX event) origin-x) lane-width) total))]
        (swap! state assoc :trim-preview (nle/trim-edge base-project clip-id edge delta))))))
(defn finish-trim! [event]
  (when (= (.-pointerId event) (get-in @state [:trim-drag :pointer-id]))
    (.preventDefault event)
    (let [preview (:trim-preview @state)]
      (remove-trim-listeners!)
      (swap! state assoc :project preview :trim-preview nil :trim-drag nil))))
(defn cancel-trim! [event]
  (when (= (.-pointerId event) (get-in @state [:trim-drag :pointer-id]))
    (remove-trim-listeners!)
    (swap! state assoc :trim-preview nil :trim-drag nil)))
(defn key-trim! [event clip edge]
  (when-let [delta ({"ArrowLeft" -1 "ArrowRight" 1} (.-key event))]
    (.preventDefault event) (.stopPropagation event)
    (swap! state update :project nle/trim-edge (:clip/id clip) edge delta)))
(defn clip-view [c total]
  [:div.clip {:class (when (= (:selected @state) (:clip/id c)) "selected")
              :role "button" :tab-index 0 :aria-label (str "Select clip " (:clip/name c))
              :style {:left (str (* 100 (/ (:clip/start-frame c) total)) "%")
                      :width (str (* 100 (/ (- (:clip/out-frame c) (:clip/in-frame c)) total)) "%")
                      :background (:clip/color c)}
              :on-click #(swap! state assoc :selected (:clip/id c) :frame (:clip/start-frame c))
              :on-key-down #(when (#{"Enter" " "} (.-key %)) (swap! state assoc :selected (:clip/id c) :frame (:clip/start-frame c)))}
   [:span.trim-handle.left {:role "slider" :tab-index 0 :aria-label (str "Trim in " (:clip/name c))
                            :aria-valuemin 0 :aria-valuemax (dec (:clip/out-frame c)) :aria-valuenow (:clip/in-frame c)
                            :on-key-down #(key-trim! % c :in)
                            :on-pointer-down #(start-trim! % c :in total)}]
   [:span.clip-name (:clip/name c)]
   [:span.trim-handle.right {:role "slider" :tab-index 0 :aria-label (str "Trim out " (:clip/name c))
                             :aria-valuemin (inc (:clip/in-frame c)) :aria-valuemax 999999 :aria-valuenow (:clip/out-frame c)
                             :on-key-down #(key-trim! % c :out)
                             :on-pointer-down #(start-trim! % c :out total)}]])
(defn selected-clip [project id] (some #(when (= id (:clip/id %)) %) (mapcat :track/clips (:project/tracks project))))
(defn next-video-clip [project id]
  (second (drop-while #(not= id (:clip/id %)) (nle/video-clips project))))
(defn edit-trim! [clip k value]
  (let [in-frame (if (= k :in) value (:clip/in-frame clip)) out-frame (if (= k :out) value (:clip/out-frame clip))]
    (swap! state update :project nle/trim-clip (:clip/id clip) in-frame out-frame)))
(defn audio-clip? [project clip]
  (boolean (some #(= (:clip/id clip) (:clip/id %)) (nle/audio-clips project))))
(defn audio-clip-duration-sec [project clip]
  (/ (- (:clip/out-frame clip) (:clip/in-frame clip)) (:project/fps project)))
(defn set-audio-automation-endpoint! [project clip endpoint gain]
  (let [duration (audio-clip-duration-sec project clip)
        base (or (:clip/audio-gain clip) 1)
        points (or (:clip/gain-automation clip) [])
        start (nle/automation-value-at base points 0)
        end (nle/automation-value-at base points duration)]
    (swap! state update :project nle/set-audio-gain-automation (:clip/id clip)
           [{:sec 0 :gain (if (= endpoint :start) gain start)}
            {:sec duration :gain (if (= endpoint :end) gain end)}])))
(defn app [] (let [{:keys [frame playing? selected decoded? assets effect exporting?]} @state
                    project (or (:trim-preview @state) (:project @state))
                    total (max 300 (nle/duration-frames project)) fps (:project/fps project)
                    delivery (nle/delivery-audio project)
                    color (nle/color-pipeline project)
                    missing (nle/missing-asset-ids project (keys assets))]
 [:main [:header [:div [:small "KOTOBA-LANG / VIDEO"] [:h1 "KAMI NLE"]] [:div.transport [:button.primary {:on-click toggle-play! :disabled (not decoded?)} (if playing? "❚❚ Pause" "▶ Play decoded media")] [:output (nle/timecode frame fps)]]]
  [:section.workspace [:aside [:h2 "Project bin"] [:label.import "Import / relink V1 videos" [:input {:type "file" :accept "video/*" :multiple true :aria-label "Import or relink NLE videos" :on-change load-media!}]]
    [:label.import "Import independent audio lanes" [:input {:type "file" :accept "audio/*" :multiple true
                                                               :aria-label "Import NLE audio lanes" :on-change load-audio!}]]
    [:label.import "Search video directory" [:input {:type "file" :accept "video/*" :multiple true :webkitdirectory ""
                                                       :aria-label "Search NLE video directory" :on-change scan-video-directory!}]]
    (if (seq assets)
      (for [[id asset] assets] ^{:key id}
        [:div.asset [:span (str "🎞 " id " • " (:name asset)
                                (when (:proxy-url asset) (str " • proxy " (js/Math.round (/ (:proxy-size asset) 1024)) " KiB")))]
         [:button {:aria-label (str "Generate proxy for " id) :disabled (= id (:proxy-generating @state))
                   :on-click #(generate-proxy! id)}
          (if (= id (:proxy-generating @state)) "Generating…" (if (:proxy-url asset) "Regenerate proxy" "Generate proxy"))]])
      [:div.asset "No media loaded"])
    [:label "Use proxies for preview" [:input {:type "checkbox" :checked (:proxy-preview? @state)
                                                 :aria-label "Use proxies for preview"
                                                 :on-change #(toggle-proxy-preview! (.. % -target -checked))}]]
    [:label "Effect" [:select {:value (name effect) :on-change #(swap! state assoc :effect (keyword (.. % -target -value)))} [:option {:value "none"} "None"] [:option {:value "cinema"} "Cinema"] [:option {:value "mono"} "Monochrome"] [:option {:value "dream"} "Dream"]]]
    [:label "Input color" [:output {:aria-label "Input color space"} "Media metadata"]]
    [:label "Output color" [:select {:value (name (:color/output-space color)) :aria-label "Output color space"
                                       :on-change #(swap! state update :project nle/set-color-pipeline :color/output-space
                                                          (keyword (.. % -target -value)))}
                              [:option {:value "srgb"} "sRGB"] [:option {:value "display-p3"} "Display P3"]]]
    (for [[key label minimum maximum step]
          [[:color/exposure-stops "Exposure stops" -5 5 0.1]
           [:color/contrast "Color contrast" 0 3 0.05]
           [:color/saturation "Color saturation" 0 3 0.05]]]
      ^{:key key} [:label label [:input {:type "number" :min minimum :max maximum :step step
                                          :value (get color key) :aria-label label
                                          :on-change #(swap! state update :project nle/set-color-pipeline key
                                                             (js/parseFloat (.. % -target -value)))}]])
    [:label "Caption text" [:textarea {:value (:caption-text @state) :aria-label "New caption text"
                                      :on-change #(swap! state assoc :caption-text (.. % -target -value))}]]
    [:label "Caption frames" [:input {:type "number" :min 1 :step 1 :value (:caption-duration-frames @state)
                                        :aria-label "New caption duration frames"
                                        :on-change #(swap! state assoc :caption-duration-frames
                                                           (max 1 (js/parseInt (.. % -target -value))))}]]
    [:label "New caption language" [:input {:value (:caption-language @state) :aria-label "New caption language"
                                               :on-change #(swap! state assoc :caption-language (.. % -target -value))}]]
    [:label "Review actor" [:input {:value (:review-author @state) :aria-label "Caption review actor"
                                      :on-change #(swap! state assoc :review-author (.. % -target -value))}]]
    (let [notifications (nle/unread-review-notifications-for project (:review-author @state))]
      [:div.asset {:aria-label "Unread review notifications"}
       [:strong (str "Unread review notifications: " (count notifications))]
       (for [notification notifications]
         ^{:key (:notification/id notification)}
         [:span
          [:small (str (name (:notification/kind notification)) " from " (:notification/actor notification)
                       " on " (:notification/caption-id notification))]
          [:button {:aria-label (str "Mark notification read " (:notification/id notification))
                    :on-click #(swap! state update :project nle/mark-review-notification-read
                                      (:notification/id notification) (:review-author @state) (js/Date.now))}
           "Mark read"]])])
    [:label.import "Import WebVTT for language"
     [:input {:type "file" :accept ".vtt,text/vtt" :aria-label "Import WebVTT"
              :on-change import-webvtt!}]]
    [:label.import "Import IMSC 1.2 captions"
     [:input {:type "file" :accept ".xml,.ttml,application/ttml+xml" :aria-label "Import IMSC1/TTML"
              :on-change import-imsc1!}]]
    [:button {:on-click #(swap! state update :project nle/clone-caption-language
                                (nle/normalize-language (:project/caption-language project))
                                (:caption-language @state))
              :disabled (= (nle/normalize-language (:project/caption-language project))
                           (nle/normalize-language (:caption-language @state)))}
     "Clone active captions to new language"]
    [:label "Burn-in / export language"
     [:select {:value (nle/normalize-language (:project/caption-language project))
               :aria-label "Active caption language"
               :on-change #(swap! state update :project nle/set-caption-language (.. % -target -value))}
      (for [language (distinct (concat [(nle/normalize-language (:project/caption-language project))]
                                       (nle/caption-languages project)))]
        ^{:key language} [:option {:value language} language])]]
    [:label "Caption position" [:select {:value (name (:caption-position @state)) :aria-label "New caption position"
                                           :on-change #(swap! state assoc :caption-position (keyword (.. % -target -value)))}
                                [:option {:value "bottom"} "Bottom"] [:option {:value "top"} "Top"]]]
    [:label "Caption alignment" [:select {:value (name (:caption-align @state)) :aria-label "New caption alignment"
                                            :on-change #(swap! state assoc :caption-align (keyword (.. % -target -value)))}
                                 [:option {:value "left"} "Left"] [:option {:value "center"} "Center"]
                                 [:option {:value "right"} "Right"]]]
    [:label "Caption font scale" [:input {:type "number" :min 0.5 :max 2 :step 0.1
                                            :value (:caption-font-scale @state) :aria-label "New caption font scale"
                                            :on-change #(swap! state assoc :caption-font-scale
                                                               (js/parseFloat (.. % -target -value)))}]]
    [:button {:on-click add-caption-at-playhead! :disabled (empty? (:caption-text @state))} "Add caption at playhead"]
    (for [caption (:project/captions project)]
      ^{:key (:caption/id caption)} [:div.asset
       [:input {:value (:caption/text caption) :aria-label (str (:caption/id caption) " text")
                :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                   {:caption/text (.. % -target -value)})}]
       [:input {:value (nle/caption-language caption) :aria-label (str (:caption/id caption) " language")
                :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                   {:caption/language (.. % -target -value)})}]
       [:input {:value (or (:caption/reviewer caption) "")
                :placeholder "Assigned reviewer" :aria-label (str (:caption/id caption) " reviewer")
                :on-change #(swap! state update :project nle/set-caption-reviewer (:caption/id caption)
                                   (.. % -target -value))}]
       [:select {:value (name (nle/caption-status caption)) :aria-label (str (:caption/id caption) " status")
                 :on-change #(swap! state update :project nle/set-caption-status (:caption/id caption)
                                    (keyword (.. % -target -value)) (:review-author @state) (js/Date.now))}
        [:option {:value "draft"} "Draft"] [:option {:value "review"} "In review"]
        [:option {:value "approved"
                  :disabled (and (:caption/reviewer caption)
                                 (not= (:caption/reviewer caption) (:review-author @state)))} "Approved"]]
       [:textarea {:value (get-in @state [:caption-review-drafts (:caption/id caption)] "")
                   :aria-label (str (:caption/id caption) " review note")
                   :on-change #(swap! state assoc-in [:caption-review-drafts (:caption/id caption)]
                                      (.. % -target -value))}]
       [:button {:aria-label (str "Add review note to " (:caption/id caption))
                 :disabled (str/blank? (get-in @state [:caption-review-drafts (:caption/id caption)] ""))
                 :on-click #(let [now (js/Date.now)]
                              (swap! state update :project nle/start-caption-review-thread (:caption/id caption)
                                     (str "review:" now) (:review-author @state)
                                     (get-in @state [:caption-review-drafts (:caption/id caption)]) now)
                              (swap! state assoc-in [:caption-review-drafts (:caption/id caption)] ""))} "Add review note"]
       (for [note (:caption/review-notes caption)]
         ^{:key (:review/id note)}
         [:div
          [:small (str (when (:review/parent-id note) "↳ ") (:review/author note) ": " (:review/text note)
                       (when (contains? note :review/resolved?)
                         (if (:review/resolved? note) " [resolved]" " [open]")))]
          (when (and (:review/thread-id note) (nil? (:review/parent-id note)))
            [:span
             [:button {:aria-label (str (if (:review/resolved? note) "Reopen " "Resolve ") (:review/id note))
                       :on-click #(swap! state update :project nle/set-caption-review-thread-resolution
                                         (:caption/id caption) (:review/thread-id note)
                                         (not (:review/resolved? note)) (:review-author @state) (js/Date.now))}
              (if (:review/resolved? note) "Reopen" "Resolve")]
             [:input {:value (get-in @state [:caption-reply-drafts (:review/id note)] "")
                      :disabled (:review/resolved? note)
                      :placeholder "Reply" :aria-label (str (:review/id note) " reply")
                      :on-change #(swap! state assoc-in [:caption-reply-drafts (:review/id note)]
                                         (.. % -target -value))}]
             [:button {:aria-label (str "Reply to " (:review/id note))
                       :disabled (or (:review/resolved? note)
                                     (str/blank? (get-in @state [:caption-reply-drafts (:review/id note)] "")))
                       :on-click #(let [now (js/Date.now)]
                                    (swap! state update :project nle/reply-caption-review-thread
                                           (:caption/id caption) (:review/thread-id note) (str "reply:" now)
                                           (:review-author @state)
                                           (get-in @state [:caption-reply-drafts (:review/id note)]) now)
                                    (swap! state assoc-in [:caption-reply-drafts (:review/id note)] ""))}
              "Reply"]])])
       (when-let [entry (last (:caption/status-history caption))]
         [:small (str "Status " (name (:status/from entry)) " → " (name (:status/to entry))
                      " by " (:status/actor entry))])
       [:input {:type "number" :min 0 :value (:caption/start-frame caption)
                :aria-label (str (:caption/id caption) " start frame")
                :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                   {:caption/start-frame (js/parseInt (.. % -target -value))})}]
       [:input {:type "number" :min 1 :value (:caption/end-frame caption)
                :aria-label (str (:caption/id caption) " end frame")
                :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                   {:caption/end-frame (js/parseInt (.. % -target -value))})}]
       [:select {:value (name (:caption/position (nle/normalize-caption-style (:caption/style caption))))
                 :aria-label (str (:caption/id caption) " position")
                 :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                    {:caption/style (assoc (nle/normalize-caption-style (:caption/style caption))
                                                           :caption/position (keyword (.. % -target -value)))})}
        [:option {:value "bottom"} "Bottom"] [:option {:value "top"} "Top"]]
       [:select {:value (name (:caption/align (nle/normalize-caption-style (:caption/style caption))))
                 :aria-label (str (:caption/id caption) " alignment")
                 :on-change #(swap! state update :project nle/update-caption (:caption/id caption)
                                    {:caption/style (assoc (nle/normalize-caption-style (:caption/style caption))
                                                           :caption/align (keyword (.. % -target -value)))})}
        [:option {:value "left"} "Left"] [:option {:value "center"} "Center"] [:option {:value "right"} "Right"]]
       [:button {:aria-label (str "Delete " (:caption/id caption))
                 :on-click #(swap! state update :project nle/remove-caption (:caption/id caption))} "Delete"]])
    [:button {:on-click export-webvtt! :disabled (empty? (:project/captions project))} "Export WebVTT"]
    [:button {:on-click export-imsc1! :disabled (empty? (:project/captions project))} "Export IMSC1/TTML"]
    (when-let [clip (selected-clip project selected)]
      [:div.asset [:strong (str "Edit • " (:clip/name clip))]
       [:label "Source in" [:input {:type "number" :min 0 :value (:clip/in-frame clip) :on-change #(edit-trim! clip :in (js/parseInt (.. % -target -value)))}]]
       [:label "Source out" [:input {:type "number" :min 1 :value (:clip/out-frame clip) :on-change #(edit-trim! clip :out (js/parseInt (.. % -target -value)))}]]
       [:label "Clip audio" [:input {:type "range" :min 0 :max 2 :step 0.05 :value (or (:clip/audio-gain clip) 1)
                                      :aria-label "Clip audio gain" :on-change #(swap! state update :project nle/set-clip-audio-gain (:clip/id clip) (js/parseFloat (.. % -target -value)))}]]
       (when (audio-clip? project clip)
         (let [duration (audio-clip-duration-sec project clip)
               points (or (:clip/gain-automation clip) [])
               base (or (:clip/audio-gain clip) 1)]
           [:div
            [:label "Fade in (s)" [:input {:type "number" :min 0 :max duration :step 0.05
                                             :value (or (:clip/fade-in-sec clip) 0) :aria-label "Audio fade in seconds"
                                             :on-change #(swap! state update :project nle/set-audio-fades (:clip/id clip)
                                                                (js/parseFloat (.. % -target -value)) (or (:clip/fade-out-sec clip) 0))}]]
            [:label "Fade out (s)" [:input {:type "number" :min 0 :max duration :step 0.05
                                              :value (or (:clip/fade-out-sec clip) 0) :aria-label "Audio fade out seconds"
                                              :on-change #(swap! state update :project nle/set-audio-fades (:clip/id clip)
                                                                 (or (:clip/fade-in-sec clip) 0) (js/parseFloat (.. % -target -value)))}]]
            [:label "Gain start" [:input {:type "number" :min 0 :max 2 :step 0.05
                                            :value (nle/automation-value-at base points 0) :aria-label "Audio gain automation start"
                                            :on-change #(set-audio-automation-endpoint! project clip :start (js/parseFloat (.. % -target -value)))}]]
            [:label "Gain end" [:input {:type "number" :min 0 :max 2 :step 0.05
                                          :value (nle/automation-value-at base points duration) :aria-label "Audio gain automation end"
                                          :on-change #(set-audio-automation-endpoint! project clip :end (js/parseFloat (.. % -target -value)))}]]]))
       (for [[band label] [[:low-db "Clip low EQ"] [:mid-db "Clip mid EQ"] [:high-db "Clip high EQ"]]]
         ^{:key band} [:label label [:input {:type "range" :min -12 :max 12 :step 0.5
                                              :value (get (nle/normalize-eq (:clip/audio-eq clip)) band)
                                              :aria-label label
                                              :on-change #(let [db (js/parseFloat (.. % -target -value))]
                                                            (swap! state update :project nle/set-clip-eq (:clip/id clip) band db)
                                                            (when (= (:clip/id clip) (:clip/id (nle/clip-at-frame (:project @state) (:frame @state))))
                                                              (set-video-eq! (primary-video) (:clip/audio-eq (selected-clip (:project @state) (:clip/id clip))))))}]])
       [:label "Transition" [:select {:value (name (or (get-in clip [:clip/transition-out :transition/type]) :cut)) :on-change #(swap! state update :project nle/set-transition (:clip/id clip) (keyword (.. % -target -value)) 12)} [:option {:value "cut"} "Cut"] [:option {:value "fade"} "Fade to black"] [:option {:value "dissolve"} "Cross dissolve"]]]
       [:div.tools [:button {:on-click #(swap! state update :project nle/ripple-trim-out (:clip/id clip) (+ 5 (:clip/out-frame clip)))} "Ripple +5"]
        [:button {:on-click #(swap! state update :project nle/slip-clip (:clip/id clip) -5)} "Slip −5"]
        [:button {:on-click #(swap! state update :project nle/slip-clip (:clip/id clip) 5)} "Slip +5"]
        (when-let [right (next-video-clip project (:clip/id clip))]
          [:button {:on-click #(swap! state update :project nle/roll-cut (:clip/id clip) (:clip/id right) 5)} "Roll +5"])]] )
    [:label "Master audio" [:input {:type "range" :min 0 :max 1.5 :step 0.05 :value (or (:project/master-gain project) 0.9) :aria-label "Master audio gain"
                                     :on-change #(set-master-gain! (js/parseFloat (.. % -target -value)))}]]
    (for [[band label] [[:low-db "Master low EQ"] [:mid-db "Master mid EQ"] [:high-db "Master high EQ"]]]
      ^{:key band} [:label label [:input {:type "range" :min -12 :max 12 :step 0.5
                                           :value (get (nle/normalize-eq (:project/master-eq project)) band)
                                           :aria-label label
                                           :on-change #(set-master-eq! band (js/parseFloat (.. % -target -value)))}]])
    [:label "Export preset" [:select {:value (name (:project/export-profile project)) :aria-label "Export preset"
                                       :on-change #(swap! state assoc-in [:project :project/export-profile] (keyword (.. % -target -value)))}
                              (for [[id profile] nle/export-profiles]
                                ^{:key id} [:option {:value (name id) :disabled (not (profile-supported? profile))}
                                            (str (:profile/name profile) (when-not (profile-supported? profile) " • unsupported"))])]]
    [:label "Normalize delivery" [:input {:type "checkbox"
                                            :checked (:delivery/normalize? delivery)
                                            :aria-label "Normalize delivery audio"
                                            :on-change #(swap! state update :project nle/set-delivery-audio :delivery/normalize? (.. % -target -checked))}]]
    [:label "Delivery LUFS" [:input {:type "number" :min -30 :max -5 :step 1
                                       :value (:delivery/target-lufs delivery)
                                       :aria-label "Delivery target LUFS"
                                       :on-change #(swap! state update :project nle/set-delivery-audio :delivery/target-lufs
                                                          (js/parseFloat (.. % -target -value)))}]]
    [:label "Sample peak ceiling" [:input {:type "number" :min -12 :max 0 :step 0.5
                                             :value (:delivery/sample-peak-ceiling-db delivery)
                                             :aria-label "Delivery sample peak ceiling dBFS"
                                             :on-change #(swap! state update :project nle/set-delivery-audio :delivery/sample-peak-ceiling-db
                                                                (js/parseFloat (.. % -target -value)))}]]
    [:meter {:min -60 :max 0 :value (max -60 (:audio-meter-db @state)) :title (str (.toFixed (:audio-meter-db @state) 1) " dBFS")}]
    [:button {:on-click analyze-delivery! :disabled (or (not decoded?) exporting? (:analyzing-delivery? @state))}
     (if (:analyzing-delivery? @state) "Analyzing delivery…" "Analyze delivery")]
    (when-let [report (:delivery-report @state)]
      [:output {:aria-label "Delivery loudness report"}
       (str (.toFixed (:loudness/lufs report) 1) " LUFS • "
            (.toFixed (:sample-peak/dbfs report) 1) " dBFS • gain "
            (.toFixed (:normalization/gain-db report) 1) " dB")])
    [:button {:on-click export-production! :disabled (or (not decoded?) exporting? (:analyzing-delivery? @state)
                                                         (nil? (resolved-recorder-profile project)))}
     (cond exporting? "Encoding production…" (:analyzing-delivery? @state) "Preflighting audio…"
           :else (str "Export " (some-> (resolved-recorder-profile project) :profile/container name .toUpperCase)))]
    [:button {:on-click download-project!} "Save project EDN"]
    [:label "Open project EDN" [:input {:type "file" :accept ".edn,application/edn" :aria-label "Open NLE project EDN" :on-change load-project!}]]
    [:button {:on-click export-package!} "Package project + media"]
    [:label "Open media package" [:input {:type "file" :accept ".zip,.kami.zip,application/zip" :aria-label "Open NLE media package" :on-change open-package!}]]
    [:button {:on-click clear-media-cache! :aria-label "Clear persistent media cache"} "Clear media cache"]
    [:button {:on-click undo! :disabled (empty? (get-in @state [:history :history/past])) :aria-label "Undo project edit"} "↶ Undo"]
    [:button {:on-click redo! :disabled (empty? (get-in @state [:history :history/future])) :aria-label "Redo project edit"} "↷ Redo"]
    [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy project EDN"]]
   (when-let [error (:project-error @state)] [:aside [:strong (str "Project error: " error)]])
   (when (:directory-searching? @state) [:aside [:strong "Searching video directory…"]])
   (when-let [source-id (:proxy-generating @state)] [:aside [:strong (str "Generating preview proxy for " source-id "…")]])
   (when-let [error (:proxy-error @state)] [:aside [:strong (str "Proxy error: " error)]])
   (when-let [{:keys [matched missing ignored]} (:directory-result @state)]
     [:aside [:strong (str "Directory relink: " matched " matched • " (count missing) " missing • " ignored " ignored")]])
   (when (:recovered? @state) [:aside [:strong "Recovered autosaved project"]])
   (when (:cache-restoring? @state) [:aside [:strong "Restoring verified media cache…"]])
   (when (pos? (:cache-restored-count @state))
     [:aside [:strong (str "Restored " (:cache-restored-count @state) " verified cached media")]])
   (when (seq missing) [:aside.missing-media [:strong (str "Missing media: " (count missing))] [:span (pr-str missing)]])
   [:div.monitor [:video {:ref #(reset! video-a-node %) :style {:display "none"} :plays-inline true :on-loaded-metadata media-ready! :on-pause #(swap! state assoc :playing? false)}]
    [:video {:ref #(reset! video-b-node %) :style {:display "none"} :plays-inline true}]
    [:div.frame [:span "PROGRAM"] [:strong (nle/timecode frame fps)] ^{:key (name (:color/output-space color))}
     [:canvas {:ref set-canvas-node! :aria-label "Decoded video preview"}]
     (when-not decoded? [:div.scene "IMPORT VIDEO"])] [:div.tools [:button {:disabled (nil? selected) :on-click #(swap! state update :project nle/split-clip selected frame (str selected "-b"))} "Split at playhead"] [:span (str fps " fps • " total " frames • " (name effect) " • " (name (:color/output-space color)))]]]]
  [:section.timeline [:input.scrub {:type "range" :min 0 :max total :value frame :aria-label "Playhead" :on-change #(let [f (js/parseInt (.. % -target -value))] (swap! state assoc :frame f) (seek-frame! f))}] (for [track (:project/tracks project)] ^{:key (:track/id track)} [:div.track [:div.track-name (:track/name track)] [:div.lane (for [c (:track/clips track)] ^{:key (:clip/id c)} [clip-view c total])]])]
  [:footer (if-let [e (seq (nle/validate-project project))] (str "Errors: " e) "HTMLVideo decode • graded canvas • capability-negotiated MediaRecorder export")]]))
(defonce root-node (atom nil))
(defn init! []
  (when-not @root-node
    (restore-recovery!) (install-history!) (install-autosave!) (install-shortcuts!)
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app])
  (restore-cached-media!))
