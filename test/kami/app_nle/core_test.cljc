(ns kami.app-nle.core-test (:require [clojure.test :refer [deftest is]] [kami.app-nle.core :as nle]))
(def p (nle/project {:project/tracks [{:track/id "v" :track/clips [{:clip/id "c" :clip/start-frame 0 :clip/in-frame 10 :clip/out-frame 110}]}]}))
(deftest editing (is (= "00:00:02:00" (nle/timecode 60 30))) (is (= 100 (nle/duration-frames p)))
 (is (= 2 (count (get-in (nle/split-clip p "c" 40 "c-b") [:project/tracks 0 :track/clips])))) (is (empty? (nle/validate-project p))))
(deftest render-authority
  (let [bound (assoc-in p [:project/tracks 0 :track/type] :video)
        bound (assoc-in bound [:project/tracks 0 :track/clips 0 :clip/source-id] "asset:a")]
    (is (= "c" (:clip/id (nle/clip-at-frame bound 50))))
    (is (= [{:segment/clip-id "c" :segment/source-id "asset:a" :segment/timeline-start-sec 0
             :segment/source-start-sec 1/3 :segment/duration-sec 10/3 :segment/audio-gain 1.0 :segment/transition-out nil}]
           (nle/render-segments bound)))))
(deftest trim-transition-and-relations
  (let [base (nle/project {:project/fps 30 :project/tracks [{:track/id "v" :track/type :video
               :track/clips [{:clip/id "a" :clip/source-id "a" :clip/start-frame 0 :clip/in-frame 0 :clip/out-frame 60}
                             {:clip/id "b" :clip/source-id "b" :clip/start-frame 75 :clip/in-frame 0 :clip/out-frame 30}]}]})
        edited (-> base (nle/trim-clip "a" 10 50) (nle/set-transition "a" :fade 12))]
    (is (= [10 50] ((juxt :clip/in-frame :clip/out-frame) (first (nle/video-clips edited)))))
    (is (= {:transition/type :fade :transition/duration-frames 12}
           (:segment/transition-out (first (nle/render-segments edited)))))
    (is (= [{:left "a" :right "b" :relation :gap :frames 35}] (nle/timeline-relations edited)))))
(deftest professional-trim-modes
  (let [base (nle/project {:project/fps 30 :project/tracks [{:track/id "v" :track/type :video
              :track/clips [{:clip/id "a" :clip/source-id "a" :clip/start-frame 0 :clip/in-frame 0 :clip/out-frame 60}
                            {:clip/id "b" :clip/source-id "b" :clip/start-frame 60 :clip/in-frame 10 :clip/out-frame 70}]}]})
        ripple (nle/ripple-trim-out base "a" 75)
        slip (nle/slip-clip base "b" 5)
        roll (nle/roll-cut base "a" "b" 5)]
    (is (= [75 75] [(get-in ripple [:project/tracks 0 :track/clips 0 :clip/out-frame])
                     (get-in ripple [:project/tracks 0 :track/clips 1 :clip/start-frame])]))
    (is (= [15 75 60] ((juxt :clip/in-frame :clip/out-frame :clip/start-frame)
                        (get-in slip [:project/tracks 0 :track/clips 1]))))
    (is (= [65 15 65] [(get-in roll [:project/tracks 0 :track/clips 0 :clip/out-frame])
                       (get-in roll [:project/tracks 0 :track/clips 1 :clip/in-frame])
                       (get-in roll [:project/tracks 0 :track/clips 1 :clip/start-frame])]))))
(deftest pointer-edge-trim-preserves-source-timeline-alignment
  (let [trimmed-in (nle/trim-edge p "c" :in 5)
        trimmed-out (nle/trim-edge p "c" :out -10)]
    (is (= [15 5 110] ((juxt :clip/in-frame :clip/start-frame :clip/out-frame)
                        (get-in trimmed-in [:project/tracks 0 :track/clips 0]))))
    (is (= [10 0 100] ((juxt :clip/in-frame :clip/start-frame :clip/out-frame)
                        (get-in trimmed-out [:project/tracks 0 :track/clips 0]))))
    (is (= p (nle/trim-edge p "c" :in -20)))
    (is (= p (nle/trim-edge p "c" :out -100)))))
(deftest production-audio-mix-authority
  (let [bound (-> p (assoc-in [:project/tracks 0 :track/type] :video)
                  (assoc-in [:project/tracks 0 :track/clips 0 :clip/source-id] "asset:a"))
        mixed (nle/set-clip-audio-gain bound "c" 0.35)]
    (is (= 0.35 (:segment/audio-gain (first (nle/render-segments mixed)))))
    (is (= 2 (:clip/audio-gain (first (nle/video-clips (nle/set-clip-audio-gain bound "c" 9))))))))
(deftest production-export-profile
  (is (= ["video/webm;codecs=vp8,opus" 2000000]
         ((juxt :profile/mime :profile/video-bps) (nle/export-profile p))))
  (is (= "video/webm;codecs=vp9,opus"
         (:profile/mime (nle/export-profile (assoc p :project/export-profile :master))))))
(deftest validated-project-persistence
  (is (= p (nle/accept-project p)))
  (is (nil? (nle/accept-project (assoc p :project/schema "foreign/v1"))))
  (is (nil? (nle/accept-project [:not :a :project]))))
(deftest versioned-crash-recovery
  (is (= p (nle/recover-project (nle/recovery-envelope p))))
  (is (= {:project p :history nle/empty-history}
         (nle/recover-workspace {:recovery/version 1 :recovery/project p})))
  (is (nil? (nle/recover-project {:recovery/version 999 :recovery/project p})))
  (is (nil? (nle/recover-project {:recovery/version 1 :recovery/project (assoc p :project/schema "foreign/v1")}))))
(deftest persisted-asset-relink-manifest
  (let [registered (nle/register-asset p "asset:7" "scene.webm" "def456")]
    (is (= "asset:7" (nle/asset-id-by-name registered "scene.webm")))
    (is (= "asset:7" (nle/asset-id-by-signature registered {:name "renamed.webm" :sha256 "def456"})))
    (is (= {:asset/name "scene.webm" :asset/sha256 "def456"} (get-in registered [:project/assets "asset:7"])))
    (is (= ["asset:7"] (nle/missing-asset-ids registered [])))
    (is (empty? (nle/missing-asset-ids registered ["asset:7"])))
    (is (= registered (nle/recover-project (nle/recovery-envelope registered))))
    (is (= "asset:0" (nle/next-asset-id registered)))))
(deftest content-addressed-media-cache-policy
  (let [registered (-> p (nle/register-asset "asset:7" "scene.webm" "def456")
                       (nle/register-asset "asset:8" "legacy.mov"))]
    (is (= [{:asset/id "asset:7" :asset/name "scene.webm" :asset/sha256 "def456"}]
           (nle/cache-requests registered)))
    (is (nle/accept-cache-hit registered "asset:7" "def456"))
    (is (not (nle/accept-cache-hit registered "asset:7" "tampered")))))
(deftest bounded-project-undo-redo
  (let [p2 (assoc p :project/export-profile :master) history (nle/record-history nle/empty-history p)
        undone (nle/undo-project p2 history) redone (nle/redo-project (:project undone) (:history undone))]
    (is (= p (:project undone)))
    (is (= p2 (:project redone)))
    (is (= 50 (count (:history/past (reduce (fn [h n] (nle/record-history h (assoc p :n n)))
                                             nle/empty-history (range 70))))))
    (is (= {:project p2 :history history}
           (nle/recover-workspace (nle/recovery-envelope p2 history))))
    (is (nil? (nle/recover-workspace
               (nle/recovery-envelope p2 {:history/past [(assoc p :project/schema "foreign/v1")]
                                          :history/future []}))))
    (is (= 50 (count (:history/past
                      (:history (nle/recover-workspace
                                 (nle/recovery-envelope p2 {:history/past (vec (repeat 70 p))
                                                            :history/future []})))))))))
(deftest portable-media-package-contract
  (let [sha (apply str (repeat 64 "a"))
        packaged (nle/register-asset p "asset:7" "scene.webm" sha)
        media {"asset:7" {:entry/name "media/0" :media/name "scene.webm" :media/type "video/webm" :media/sha256 sha}}
        manifest (nle/package-manifest packaged media)]
    (is (= {:project packaged :media media} (nle/accept-package packaged manifest #{"media/0"})))
    (is (nil? (nle/accept-package packaged manifest #{})))
    (is (nil? (nle/accept-package packaged (assoc-in manifest [:package/media "asset:7" :media/sha256]
                                                      (apply str (repeat 64 "b"))) #{"media/0"})))
    (is (= "media/7" (nle/package-entry-name 7)))))
