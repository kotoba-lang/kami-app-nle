(ns kami.app-nle.core-test (:require [clojure.test :refer [deftest is]] [kami.app-nle.core :as nle]))
(def p (nle/project {:project/tracks [{:track/id "v" :track/clips [{:clip/id "c" :clip/start-frame 0 :clip/in-frame 10 :clip/out-frame 110}]}]}))
(deftest editing (is (= "00:00:02:00" (nle/timecode 60 30))) (is (= 100 (nle/duration-frames p)))
 (is (= 2 (count (get-in (nle/split-clip p "c" 40 "c-b") [:project/tracks 0 :track/clips])))) (is (empty? (nle/validate-project p))))
(deftest render-authority
  (let [bound (assoc-in p [:project/tracks 0 :track/type] :video)
        bound (assoc-in bound [:project/tracks 0 :track/clips 0 :clip/source-id] "asset:a")]
    (is (= "c" (:clip/id (nle/clip-at-frame bound 50))))
    (is (= [{:segment/clip-id "c" :segment/source-id "asset:a" :segment/timeline-start-sec 0
             :segment/source-start-sec 1/3 :segment/duration-sec 10/3 :segment/transition-out nil}]
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
