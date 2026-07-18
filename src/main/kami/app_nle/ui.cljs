(ns kami.app-nle.ui (:require [reagent.core :as r] [reagent.dom.client :as rdom] [kami.app-nle.core :as nle]))
(def sample (nle/project {:project/id "demo-cut" :project/name "海辺の予告編" :project/fps 30
 :project/tracks [{:track/id "v2" :track/name "V2 • Titles" :track/type :video :track/clips [{:clip/id "title" :clip/name "OPENING" :clip/start-frame 45 :clip/in-frame 0 :clip/out-frame 75 :clip/color "#fbbf24"}]}
 {:track/id "v1" :track/name "V1 • Picture" :track/type :video :track/clips [{:clip/id "wide" :clip/name "Wide shot" :clip/start-frame 0 :clip/in-frame 20 :clip/out-frame 170 :clip/color "#38bdf8"} {:clip/id "close" :clip/name "Close up" :clip/start-frame 150 :clip/in-frame 10 :clip/out-frame 130 :clip/color "#a78bfa"}]}
 {:track/id "a1" :track/name "A1 • Dialogue" :track/type :audio :track/clips [{:clip/id "dialogue" :clip/name "Dialogue.wav" :clip/start-frame 30 :clip/in-frame 0 :clip/out-frame 240 :clip/color "#34d399"}]}]}))
(defonce state (r/atom {:project sample :frame 105 :playing? false :selected "wide"}))
(defn clip-view [c total] [:button.clip {:class (when (= (:selected @state) (:clip/id c)) "selected") :style {:left (str (* 100 (/ (:clip/start-frame c) total)) "%") :width (str (* 100 (/ (- (:clip/out-frame c) (:clip/in-frame c)) total)) "%") :background (:clip/color c)} :on-click #(swap! state assoc :selected (:clip/id c) :frame (:clip/start-frame c))} (:clip/name c)])
(defn app [] (let [{:keys [project frame playing? selected]} @state total (max 300 (nle/duration-frames project)) fps (:project/fps project)]
 [:main [:header [:div [:small "KOTOBA-LANG / VIDEO"] [:h1 "KAMI NLE"]] [:div.transport [:button.primary {:on-click #(swap! state update :playing? not)} (if playing? "❚❚ Pause" "▶ Play")] [:output (nle/timecode frame fps)]]]
  [:section.workspace [:aside [:h2 "Project bin"] [:div.asset "🎞 coast-wide.mp4"] [:div.asset "🎞 close-up.mp4"] [:div.asset "♫ dialogue.wav"] [:button {:on-click #(js/navigator.clipboard.writeText (pr-str project))} "Copy project EDN"]]
   [:div.monitor [:div.frame [:span "PROGRAM"] [:strong (nle/timecode frame fps)] [:div.scene "KAMI NLE"]] [:div.tools [:button {:disabled (nil? selected) :on-click #(swap! state update :project nle/split-clip selected frame (str selected "-b"))} "Split at playhead"] [:span (str fps " fps • " total " frames")]]]]
  [:section.timeline [:input.scrub {:type "range" :min 0 :max total :value frame :aria-label "Playhead" :on-change #(swap! state assoc :frame (js/parseInt (.. % -target -value)))}]
   (for [track (:project/tracks project)] ^{:key (:track/id track)} [:div.track [:div.track-name (:track/name track)] [:div.lane (for [c (:track/clips track)] ^{:key (:clip/id c)} [clip-view c total])]])]
  [:footer (if-let [e (seq (nle/validate-project project))] (str "Errors: " e) "EDN project valid • non-linear frame editor")]]))
(defonce root-node (atom nil))
(defn init! []
  (when-not @root-node
    (reset! root-node (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root-node [app]))
