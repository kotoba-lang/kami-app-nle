(ns kami.app-nle.core-test (:require [clojure.test :refer [deftest is]] [clojure.string :as str]
                                     [kami.app-nle.core :as nle]))
(def p (nle/project {:project/tracks [{:track/id "v" :track/clips [{:clip/id "c" :clip/start-frame 0 :clip/in-frame 10 :clip/out-frame 110}]}]}))
(deftest editing (is (= "00:00:02:00" (nle/timecode 60 30))) (is (= 100 (nle/duration-frames p)))
 (is (= 2 (count (get-in (nle/split-clip p "c" 40 "c-b") [:project/tracks 0 :track/clips])))) (is (empty? (nle/validate-project p))))
(deftest render-authority
  (let [bound (assoc-in p [:project/tracks 0 :track/type] :video)
        bound (assoc-in bound [:project/tracks 0 :track/clips 0 :clip/source-id] "asset:a")]
    (is (= "c" (:clip/id (nle/clip-at-frame bound 50))))
    (is (= [{:segment/clip-id "c" :segment/source-id "asset:a" :segment/timeline-start-sec 0
             :segment/source-start-sec 1/3 :segment/duration-sec 10/3 :segment/audio-gain 1.0
             :segment/audio-eq nle/flat-eq :segment/transition-out nil}]
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
(deftest project-authoritative-three-band-eq
  (let [bound (-> p (assoc-in [:project/tracks 0 :track/type] :video)
                  (assoc-in [:project/tracks 0 :track/clips 0 :clip/source-id] "asset:a"))
        equalized (-> bound (nle/set-clip-eq "c" :low-db 6.5)
                      (nle/set-clip-eq "c" :mid-db -20)
                      (nle/set-master-eq :high-db 4.0))]
    (is (= {:low-db 6.5 :mid-db -12.0 :high-db 0.0}
           (:segment/audio-eq (first (nle/render-segments equalized)))))
    (is (= {:low-db 0.0 :mid-db 0.0 :high-db 4.0} (:project/master-eq equalized)))
    (is (empty? (nle/validate-project equalized)))
    (is (= [[:invalid-clip-eq "c"]]
           (nle/validate-project (assoc-in equalized [:project/tracks 0 :track/clips 0 :clip/audio-eq :low-db] 30))))))
(deftest independent-audio-lane-binding-and-render-authority
  (let [bound (nle/bind-audio-asset p "asset:audio" "dialogue.wav" 90)
        clip (first (nle/audio-clips bound)) segment (first (nle/audio-segments bound))]
    (is (= ["asset:audio" 0 90] ((juxt :clip/source-id :clip/in-frame :clip/out-frame) clip)))
    (is (= {:segment/clip-id (:clip/id clip) :segment/source-id "asset:audio"
            :segment/timeline-start-sec 0 :segment/source-start-sec 0 :segment/duration-sec 3
            :segment/audio-gain 1.0 :segment/audio-eq nle/flat-eq
            :segment/fade-in-sec 0 :segment/fade-out-sec 0 :segment/gain-automation []} segment))
    (is (empty? (nle/validate-project bound)))
    (is (= bound (nle/bind-audio-asset bound "asset:audio" "renamed.wav" 300)))
    (is (= 2 (count (nle/audio-clips (nle/bind-audio-asset bound "asset:music" "music.wav" 120)))))))
(deftest audio-fade-crossfade-and-gain-envelope
  (let [bound (nle/bind-audio-asset p "asset:audio" "dialogue.wav" 120)
        id (:clip/id (first (nle/audio-clips bound)))
        automated (-> bound (nle/set-audio-fades id 0.5 1.0)
                      (nle/set-audio-gain-automation id [{:sec 2 :gain 0.5} {:sec 0 :gain 1.5}]))
        segment (first (nle/audio-segments automated))]
    (is (= [0.5 1.0] ((juxt :segment/fade-in-sec :segment/fade-out-sec) segment)))
    (is (= [{:automation/sec 0 :automation/gain 1.5} {:automation/sec 2 :automation/gain 0.5}]
           (:segment/gain-automation segment)))
    (is (= 1.0 (nle/automation-value-at 1.0 (:segment/gain-automation segment) 1)))
    (is (= 0.5 (nle/fade-value-at 4 1 1 0.5)))
    (is (= 0.5 (nle/fade-value-at 4 1 1 3.5)))
    (is (empty? (nle/validate-project automated)))
    (is (= [[:invalid-audio-automation id]]
           (nle/validate-project (assoc-in automated [:project/tracks 1 :track/clips 0 :clip/audio-gain-automation]
                                           [{:automation/sec 2 :automation/gain 1} {:automation/sec 1 :automation/gain 1}]))))))
(deftest production-export-profile
  (is (= ["video/webm;codecs=vp8,opus" 2000000]
         ((juxt :profile/mime :profile/video-bps) (nle/export-profile p))))
  (is (= "video/webm;codecs=vp9,opus"
         (:profile/mime (nle/export-profile (assoc p :project/export-profile :master)))))
  (let [mp4 (nle/export-profile (assoc p :project/export-profile :mp4-master))]
    (is (= [:mp4 "mp4" "kami-nle-master.mp4"]
           [(:profile/container mp4) (:profile/extension mp4) (nle/export-filename mp4)]))
    (is (= 3 (count (:profile/mimes mp4))))))
(deftest project-authoritative-delivery-loudness
  (let [configured (-> p
                       (nle/set-delivery-audio :delivery/target-lufs -40)
                       (nle/set-delivery-audio :delivery/sample-peak-ceiling-db 2))]
    (is (= [-30.0 0.0]
           ((juxt :delivery/target-lufs :delivery/sample-peak-ceiling-db)
            (:project/delivery-audio configured))))
    (is (< (abs (- -20.691 (nle/integrated-lufs [0.01 0.01 0.0]))) 0.001))
    (is (= -2.0 (nle/normalization-gain-db -20.0 -0.5 -14.0 -2.5)))
    (is (= 6.0 (nle/normalization-gain-db -20.0 -10.0 -14.0 -1.0)))
    (is (empty? (nle/validate-project configured)))
    (is (= [:invalid-delivery-audio]
           (nle/validate-project (assoc-in configured [:project/delivery-audio :delivery/target-lufs] 1))))))
(deftest project-authoritative-color-pipeline
  (let [configured (-> p
                       (nle/set-color-pipeline :color/output-space :display-p3)
                       (nle/set-color-pipeline :color/exposure-stops 8)
                       (nle/set-color-pipeline :color/contrast -1)
                       (nle/set-color-pipeline :color/saturation 2.25))]
    (is (= {:color/input-space :media-native :color/output-space :display-p3
            :color/exposure-stops 5.0 :color/contrast 0.0 :color/saturation 2.25}
           (:project/color-pipeline configured)))
    (is (empty? (nle/validate-project configured)))
    (is (= [:invalid-color-pipeline]
           (nle/validate-project (assoc-in configured [:project/color-pipeline :color/output-space] :rec2020))))))
(deftest project-authoritative-captions-and-webvtt
  (let [captioned (-> p
                      (nle/add-caption "cap-1" 15 75 "Hello, 海辺")
                      (nle/add-caption "cap-2" 90 120 "Second line"))]
    (is (= ["cap-1"] (mapv :caption/id (nle/captions-at-frame captioned 30))))
    (is (= "WEBVTT\n\ncap-1\n00:00:00.500 --> 00:00:02.500\nHello, 海辺\n\ncap-2\n00:00:03.000 --> 00:00:04.000\nSecond line\n"
           (nle/webvtt captioned)))
    (is (empty? (nle/validate-project captioned)))
    (is (= [:duplicate-caption-id]
           (nle/validate-project (update captioned :project/captions conj (first (:project/captions captioned))))))
    (is (= [[:invalid-caption "cap-1"]]
           (nle/validate-project (assoc-in captioned [:project/captions 0 :caption/end-frame] 10))))))
(deftest styled-multiline-caption-contract
  (let [captioned (nle/add-caption p "styled" 0 60 "First line\nSecond line"
                                   {:caption/position :top :caption/align :left :caption/font-scale 1.5})
        caption (first (:project/captions captioned))
        clamped (nle/update-caption captioned "styled"
                                    {:caption/style {:caption/position :invalid :caption/align :right
                                                     :caption/font-scale 9}})]
    (is (= {:caption/position :top :caption/align :left :caption/font-scale 1.5}
           (:caption/style caption)))
    (is (= {:caption/position :bottom :caption/align :right :caption/font-scale 2.0}
           (get-in clamped [:project/captions 0 :caption/style])))
    (is (str/includes? (nle/webvtt captioned) "First line\nSecond line"))
    (is (empty? (nle/validate-project captioned)))
    (is (= [[:invalid-caption "styled"]]
           (nle/validate-project (assoc-in captioned [:project/captions 0 :caption/style :caption/font-scale] 4))))))
(deftest multilingual-caption-selection-and-delivery
  (let [localized (-> p
                      (nle/add-caption "en-1" 0 60 "Hello" "en" nle/default-caption-style)
                      (nle/add-caption "ja-1" 0 60 "こんにちは" "ja" nle/default-caption-style))
        japanese (nle/set-caption-language localized "ja")]
    (is (= ["en" "ja"] (nle/caption-languages localized)))
    (is (= ["en-1"] (mapv :caption/id (nle/captions-at-frame localized 30))))
    (is (= ["ja-1"] (mapv :caption/id (nle/captions-at-frame japanese 30))))
    (is (str/includes? (nle/webvtt localized "en") "Hello"))
    (is (not (str/includes? (nle/webvtt localized "en") "こんにちは")))
    (is (str/includes? (nle/webvtt japanese) "こんにちは"))
    (is (empty? (nle/validate-project japanese)))
    (is (= [:invalid-caption-language]
           (nle/validate-project (assoc japanese :project/caption-language "not valid"))))))
(deftest webvtt-import-replaces-one-language-and-round-trips
  (let [base (-> p
                 (nle/add-caption "en-old" 0 30 "Old" "en" nle/default-caption-style)
                 (nle/add-caption "ja-old" 0 30 "残す" "ja" nle/default-caption-style))
        source "﻿WEBVTT imported captions\nKind: captions\n\nNOTE translator context\nDo not import this\n\nexternal-id\n00:00:00.500 --> 00:00:02.500 align:start\nFirst line\nSecond line\n"
        imported (nle/import-webvtt base source "en")
        english (first (filter #(= "en" (nle/caption-language %)) (:project/captions imported)))]
    (is (= 15 (:caption/start-frame english)))
    (is (= 75 (:caption/end-frame english)))
    (is (= "First line\nSecond line" (:caption/text english)))
    (is (= ["en" "ja"] (nle/caption-languages imported)))
    (is (str/includes? (nle/webvtt imported "en") "00:00:00.500 --> 00:00:02.500"))
    (is (str/includes? (nle/webvtt imported "ja") "残す"))
    (is (empty? (nle/validate-project imported)))
    (is (nil? (nle/import-webvtt base "WEBVTT\n\nbroken" "en")))))
(deftest caption-language-clone-and-delete-workflow
  (let [english (-> p
                    (nle/add-caption "en-a" 0 30 "First" "en" nle/default-caption-style)
                    (nle/add-caption "en-b" 30 60 "Second" "en" nle/default-caption-style))
        japanese (nle/clone-caption-language english "en" "ja")
        deleted (nle/remove-caption japanese "translation:ja:0")]
    (is (= "ja" (:project/caption-language japanese)))
    (is (= ["en" "ja"] (nle/caption-languages japanese)))
    (is (= ["First" "Second"] (mapv :caption/text (filter #(= "ja" (nle/caption-language %))
                                                            (:project/captions japanese)))))
    (is (= ["translation:ja:1"] (mapv :caption/id (filter #(= "ja" (nle/caption-language %))
                                                            (:project/captions deleted)))))
    (is (= japanese (nle/clone-caption-language japanese "ja" "ja")))
    (is (empty? (nle/validate-project deleted)))))
(deftest proxy-preview-never-replaces-original-export-source
  (let [asset {:url "blob:original" :proxy-url "blob:proxy"}]
    (is (= :proxy-url (nle/media-url-key true false asset)))
    (is (= :url (nle/media-url-key false false asset)))
    (is (= :url (nle/media-url-key true true asset)))
    (is (= :url (nle/media-url-key true false {:url "blob:original"})))
    (is (= [640 360 800000]
           ((juxt :profile/max-width :profile/max-height :profile/video-bps) nle/proxy-profile)))))
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
    (is (nil? (nle/asset-id-by-signature registered {:name "scene.webm" :sha256 "different"})))
    (is (= {:asset/name "scene.webm" :asset/sha256 "def456"} (get-in registered [:project/assets "asset:7"])))
    (is (= ["asset:7"] (nle/missing-asset-ids registered [])))
    (is (empty? (nle/missing-asset-ids registered ["asset:7"])))
    (is (= registered (nle/recover-project (nle/recovery-envelope registered))))
    (is (= "asset:0" (nle/next-asset-id registered)))))
(deftest deterministic-directory-relink-search
  (let [project (-> p (nle/register-asset "asset:7" "original.webm" "aaa")
                    (nle/register-asset "asset:8" "legacy.mov"))
        candidates [{:file/index 0 :file/path "root/z/renamed.webm" :file/name "renamed.webm" :file/sha256 "aaa"}
                    {:file/index 1 :file/path "root/a/renamed.webm" :file/name "renamed.webm" :file/sha256 "aaa"}
                    {:file/index 2 :file/path "root/legacy.mov" :file/name "legacy.mov" :file/sha256 "new"}
                    {:file/index 3 :file/path "root/original.webm" :file/name "original.webm" :file/sha256 "wrong"}]
        plan (nle/directory-relink-plan project candidates)]
    (is (= [["asset:7" 1] ["asset:8" 2]]
           (mapv (fn [match] [(:asset/id match) (get-in match [:candidate :file/index])]) (:relink/matches plan))))
    (is (empty? (:relink/missing plan)))
    (is (= ["root/original.webm" "root/z/renamed.webm"] (:relink/ignored-paths plan)))))
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
