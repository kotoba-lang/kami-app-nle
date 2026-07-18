(ns kami.app-nle.ui
  (:require [reagent.core :as r] [reagent.dom.client :as rdom] [cljs.reader :as reader] [kami.app-nle.core :as nle]))

(def sample (nle/project {:project/id "demo-cut" :project/name "海辺の予告編" :project/fps 30
 :project/tracks [{:track/id "v2" :track/name "V2 • Titles" :track/type :video :track/clips [{:clip/id "title" :clip/name "OPENING" :clip/start-frame 45 :clip/in-frame 0 :clip/out-frame 75 :clip/color "#fbbf24"}]}
 {:track/id "v1" :track/name "V1 • Picture" :track/type :video :track/clips [{:clip/id "wide" :clip/name "Wide shot" :clip/source-id "asset:0" :clip/start-frame 0 :clip/in-frame 20 :clip/out-frame 170 :clip/color "#38bdf8"} {:clip/id "close" :clip/name "Close up" :clip/source-id "asset:1" :clip/start-frame 150 :clip/in-frame 10 :clip/out-frame 130 :clip/color "#a78bfa"}]}
 {:track/id "a1" :track/name "A1 • Dialogue" :track/type :audio :track/clips [{:clip/id "dialogue" :clip/name "Dialogue.wav" :clip/start-frame 30 :clip/in-frame 0 :clip/out-frame 240 :clip/color "#34d399"}]}]}))
(defonce state (r/atom {:project sample :frame 105 :playing? false :selected "wide" :assets {} :active-source nil :pending-source-frame 0 :decoded? false :effect :none :exporting? false :project-error nil :primary-slot :a :master-gain 0.9 :audio-meter-db -96}))
(defonce video-a-node (atom nil)) (defonce video-b-node (atom nil))
(defonce canvas-node (atom nil)) (defonce media-url (atom nil))
(defonce export-audio (atom nil))
(def filters {:none "none" :cinema "contrast(1.18) saturate(1.22)" :mono "grayscale(1) contrast(1.12)" :dream "saturate(1.35) brightness(1.08) blur(1px)"})
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
    (let [v (primary-video) overlay (secondary-video) c @canvas-node ctx (.getContext c "2d")
          segment (:export-segment @state) transition (:segment/transition-out segment)
          elapsed (when segment (- (.-currentTime v) (:segment/source-start-sec segment)))
          remaining (when segment (- (:segment/duration-sec segment) elapsed))
          fade-sec (when transition (/ (:transition/duration-frames transition) (get-in @state [:project :project/fps])))
          fade-alpha (if (and (= :fade (:transition/type transition)) fade-sec (pos? fade-sec) remaining (< remaining fade-sec)) (max 0 (/ remaining fade-sec)) 1)
          dissolve (:dissolve @state)
          progress (when dissolve (min 1 (max 0 (/ (- (js/performance.now) (:started-ms dissolve)) (:duration-ms dissolve)))))]
      (when (>= (.-readyState v) 2)
        (set! (.-filter ctx) (get filters (:effect @state) "none"))
        (set! (.-globalAlpha ctx) 1) (set! (.-fillStyle ctx) "black") (.fillRect ctx 0 0 (.-width c) (.-height c))
        (set! (.-globalAlpha ctx) (if progress (- 1 progress) fade-alpha)) (.drawImage ctx v 0 0 (.-width c) (.-height c))
        (when (and progress overlay (>= (.-readyState overlay) 2))
          (set! (.-globalAlpha ctx) progress) (.drawImage ctx overlay 0 0 (.-width c) (.-height c)))
        (set! (.-globalAlpha ctx) 1)
        (swap! state assoc :frame (js/Math.floor (* (.-currentTime v) (get-in @state [:project :project/fps])))
               :audio-meter-db (audio-meter-db)))
      (js/requestAnimationFrame draw-frame!))))
(defn activate-source! [source-id source-frame] (when-let [{:keys [url]} (get-in @state [:assets source-id])] (let [video (primary-video)] (swap! state assoc :pending-source-frame source-frame :active-source source-id) (if (not= url (.-src video)) (do (set! (.-src video) url) (.load video)) (set! (.-currentTime video) (/ source-frame (get-in @state [:project :project/fps])))))))
(defn seek-frame! [frame] (when-let [clip (nle/clip-at-frame (:project @state) frame)] (activate-source! (:clip/source-id clip) (+ (:clip/in-frame clip) (- frame (:clip/start-frame clip))))))
(defn load-media! [e] (let [files (array-seq (.. e -target -files))] (doseq [[index file] (map-indexed vector files)] (let [source-id (str "asset:" index) url (js/URL.createObjectURL file)] (swap! state assoc-in [:assets source-id] {:name (.-name file) :url url}))) (when (seq files) (seek-frame! (:frame @state)))))
(defn media-ready! [] (let [v (primary-video) c @canvas-node] (set! (.-width c) (or (.-videoWidth v) 1280)) (set! (.-height c) (or (.-videoHeight v) 720)) (set! (.-currentTime v) (/ (:pending-source-frame @state) (get-in @state [:project :project/fps]))) (swap! state assoc :decoded? true) (draw-frame!)))
(defn toggle-play! [] (when (:decoded? @state) (if (:playing? @state) (.pause (primary-video)) (.play (primary-video))) (swap! state update :playing? not)))
(defn download-blob! [blob] (let [url (js/URL.createObjectURL blob) a (.createElement js/document "a")] (set! (.-href a) url) (set! (.-download a) "kami-nle-master.webm") (.click a) (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn download-project! []
  (let [blob (js/Blob. #js [(pr-str (:project @state))] #js {:type "application/edn"})
        url (js/URL.createObjectURL blob) a (.createElement js/document "a")]
    (set! (.-href a) url) (set! (.-download a) "kami-nle-project.edn") (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))
(defn load-project! [event]
  (when-let [file (aget (.. event -target -files) 0)]
    (-> (.text file)
        (.then (fn [text]
                 (try
                   (if-let [project (nle/accept-project (reader/read-string text))]
                     (let [first-clip (first (nle/video-clips project))]
                       (swap! state assoc :project project :selected (:clip/id first-clip)
                              :frame (or (:clip/start-frame first-clip) 0) :decoded? false :playing? false :project-error nil))
                     (swap! state assoc :project-error "Unsupported or invalid NLE project"))
                   (catch :default error (swap! state assoc :project-error (.-message error)))))))))
(defn recorder-options [project]
  (let [profile (nle/export-profile project) preferred (:profile/mime profile)
        fallback "video/webm;codecs=vp8,opus" mime (if (.isTypeSupported js/MediaRecorder preferred) preferred fallback)]
    #js {:mimeType mime :videoBitsPerSecond (:profile/video-bps profile) :audioBitsPerSecond (:profile/audio-bps profile)}))
(defn ensure-export-audio! []
  (or @export-audio
      (let [Ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window)) ctx (new Ctor)
            source-a (.createMediaElementSource ctx @video-a-node)
            source-b (.createMediaElementSource ctx @video-b-node)
            gain-a (.createGain ctx) gain-b (.createGain ctx) master (.createGain ctx) analyser (.createAnalyser ctx)
            destination (.createMediaStreamDestination ctx)]
        (set! (.. gain-a -gain -value) 1) (set! (.. gain-b -gain -value) 0)
        (set! (.. master -gain -value) (:master-gain @state)) (set! (.-fftSize analyser) 512)
        (.connect source-a gain-a) (.connect source-b gain-b)
        (.connect gain-a master) (.connect gain-b master) (.connect master analyser)
        (.connect analyser destination) (.connect analyser (.-destination ctx))
        (reset! export-audio {:context ctx :destination destination :node-a @video-a-node :node-b @video-b-node
                              :gain-a gain-a :gain-b gain-b :master master :analyser analyser}))))
(defn gain-for-video [video]
  (let [{:keys [node-a gain-a gain-b]} @export-audio] (if (identical? video node-a) gain-a gain-b)))
(defn set-master-gain! [gain]
  (swap! state assoc :master-gain gain)
  (when-let [master (:master @export-audio)] (set! (.. master -gain -value) gain)))
(defn set-primary-audio! [segment]
  (when @export-audio
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
      (.then #(play-timeline-from! segments 0 0))))
(defn export-webm! []
  (let [segments (nle/render-segments (:project @state))]
    (when (and (seq segments) (every? #(get-in @state [:assets (:segment/source-id %) :url]) segments))
      (let [stream (.captureStream @canvas-node (get-in @state [:project :project/fps]))
            {:keys [context destination]} (ensure-export-audio!)
            audio-track (first (array-seq (.getAudioTracks (.-stream destination))))
            _ (.addTrack stream audio-track)
            recorder (js/MediaRecorder. stream (recorder-options (:project @state))) chunks (array)]
        (.resume context) (set-master-gain! (:master-gain @state)) (set-primary-audio! (first segments)) (swap! state assoc :exporting? true :playing? true)
        (set! (.-ondataavailable recorder) #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
        (set! (.-onstop recorder) #(do (download-blob! (js/Blob. chunks #js {:type "video/webm"})) (swap! state dissoc :export-segment) (swap! state assoc :exporting? false :playing? false)))
        (.start recorder 250)
        (-> (play-segments! segments) (.then #(.stop recorder))
            (.catch (fn [error] (js/console.error error) (.stop recorder))))))))
(defn clip-view [c total] [:button.clip {:class (when (= (:selected @state) (:clip/id c)) "selected") :style {:left (str (* 100 (/ (:clip/start-frame c) total)) "%") :width (str (* 100 (/ (- (:clip/out-frame c) (:clip/in-frame c)) total)) "%") :background (:clip/color c)} :on-click #(swap! state assoc :selected (:clip/id c) :frame (:clip/start-frame c))} (:clip/name c)])
(defn selected-clip [project id] (some #(when (= id (:clip/id %)) %) (mapcat :track/clips (:project/tracks project))))
(defn next-video-clip [project id]
  (second (drop-while #(not= id (:clip/id %)) (nle/video-clips project))))
(defn edit-trim! [clip k value]
  (let [in-frame (if (= k :in) value (:clip/in-frame clip)) out-frame (if (= k :out) value (:clip/out-frame clip))]
    (swap! state update :project nle/trim-clip (:clip/id clip) in-frame out-frame)))
(defn app [] (let [{:keys [project frame playing? selected decoded? assets effect exporting?]} @state total (max 300 (nle/duration-frames project)) fps (:project/fps project)]
 [:main [:header [:div [:small "KOTOBA-LANG / VIDEO"] [:h1 "KAMI NLE"]] [:div.transport [:button.primary {:on-click toggle-play! :disabled (not decoded?)} (if playing? "❚❚ Pause" "▶ Play decoded media")] [:output (nle/timecode frame fps)]]]
  [:section.workspace [:aside [:h2 "Project bin"] [:label.import "Import V1 videos" [:input {:type "file" :accept "video/*" :multiple true :on-change load-media!}]] (if (seq assets) (for [[id asset] assets] ^{:key id} [:div.asset (str "🎞 " id " • " (:name asset))]) [:div.asset "No media loaded"]) [:label "Effect" [:select {:value (name effect) :on-change #(swap! state assoc :effect (keyword (.. % -target -value)))} [:option {:value "none"} "None"] [:option {:value "cinema"} "Cinema"] [:option {:value "mono"} "Monochrome"] [:option {:value "dream"} "Dream"]]]
    (when-let [clip (selected-clip project selected)]
      [:div.asset [:strong (str "Edit • " (:clip/name clip))]
       [:label "Source in" [:input {:type "number" :min 0 :value (:clip/in-frame clip) :on-change #(edit-trim! clip :in (js/parseInt (.. % -target -value)))}]]
       [:label "Source out" [:input {:type "number" :min 1 :value (:clip/out-frame clip) :on-change #(edit-trim! clip :out (js/parseInt (.. % -target -value)))}]]
       [:label "Clip audio" [:input {:type "range" :min 0 :max 2 :step 0.05 :value (or (:clip/audio-gain clip) 1)
                                      :aria-label "Clip audio gain" :on-change #(swap! state update :project nle/set-clip-audio-gain (:clip/id clip) (js/parseFloat (.. % -target -value)))}]]
       [:label "Transition" [:select {:value (name (or (get-in clip [:clip/transition-out :transition/type]) :cut)) :on-change #(swap! state update :project nle/set-transition (:clip/id clip) (keyword (.. % -target -value)) 12)} [:option {:value "cut"} "Cut"] [:option {:value "fade"} "Fade to black"] [:option {:value "dissolve"} "Cross dissolve"]]]
       [:div.tools [:button {:on-click #(swap! state update :project nle/ripple-trim-out (:clip/id clip) (+ 5 (:clip/out-frame clip)))} "Ripple +5"]
        [:button {:on-click #(swap! state update :project nle/slip-clip (:clip/id clip) -5)} "Slip −5"]
        [:button {:on-click #(swap! state update :project nle/slip-clip (:clip/id clip) 5)} "Slip +5"]
        (when-let [right (next-video-clip project (:clip/id clip))]
          [:button {:on-click #(swap! state update :project nle/roll-cut (:clip/id clip) (:clip/id right) 5)} "Roll +5"])]] )
    [:label "Master audio" [:input {:type "range" :min 0 :max 1.5 :step 0.05 :value (:master-gain @state) :aria-label "Master audio gain"
                                     :on-change #(set-master-gain! (js/parseFloat (.. % -target -value)))}]]
    [:label "Export preset" [:select {:value (name (:project/export-profile project)) :aria-label "Export preset"
                                       :on-change #(swap! state assoc-in [:project :project/export-profile] (keyword (.. % -target -value)))}
                              (for [[id profile] nle/export-profiles] ^{:key id} [:option {:value (name id)} (:profile/name profile)])]]
    [:meter {:min -60 :max 0 :value (max -60 (:audio-meter-db @state)) :title (str (.toFixed (:audio-meter-db @state) 1) " dBFS")}]
    [:button {:on-click export-webm! :disabled (or (not decoded?) exporting?)} (if exporting? "Encoding WebM…" "Export WebM")]
    [:button {:on-click download-project!} "Save project EDN"]
    [:label "Open project EDN" [:input {:type "file" :accept ".edn,application/edn" :aria-label "Open NLE project EDN" :on-change load-project!}]]
    [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy project EDN"]]
   (when-let [error (:project-error @state)] [:aside [:strong (str "Project error: " error)]])
   [:div.monitor [:video {:ref #(reset! video-a-node %) :style {:display "none"} :plays-inline true :on-loaded-metadata media-ready! :on-pause #(swap! state assoc :playing? false)}]
    [:video {:ref #(reset! video-b-node %) :style {:display "none"} :plays-inline true}]
    [:div.frame [:span "PROGRAM"] [:strong (nle/timecode frame fps)] [:canvas {:ref #(reset! canvas-node %) :aria-label "Decoded video preview"}] (when-not decoded? [:div.scene "IMPORT VIDEO"])] [:div.tools [:button {:disabled (nil? selected) :on-click #(swap! state update :project nle/split-clip selected frame (str selected "-b"))} "Split at playhead"] [:span (str fps " fps • " total " frames • " (name effect))]]]]
  [:section.timeline [:input.scrub {:type "range" :min 0 :max total :value frame :aria-label "Playhead" :on-change #(let [f (js/parseInt (.. % -target -value))] (swap! state assoc :frame f) (seek-frame! f))}] (for [track (:project/tracks project)] ^{:key (:track/id track)} [:div.track [:div.track-name (:track/name track)] [:div.lane (for [c (:track/clips track)] ^{:key (:clip/id c)} [clip-view c total])]])]
  [:footer (if-let [e (seq (nle/validate-project project))] (str "Errors: " e) "HTMLVideo decode • canvas effects • MediaRecorder WebM export")]]))
(defonce root-node (atom nil))
(defn init! [] (when-not @root-node (reset! root-node (rdom/create-root (.getElementById js/document "app")))) (rdom/render @root-node [app]))
