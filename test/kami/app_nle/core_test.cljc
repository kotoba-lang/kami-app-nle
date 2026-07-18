(ns kami.app-nle.core-test (:require [clojure.test :refer [deftest is]] [kami.app-nle.core :as nle]))
(def p (nle/project {:project/tracks [{:track/id "v" :track/clips [{:clip/id "c" :clip/start-frame 0 :clip/in-frame 10 :clip/out-frame 110}]}]}))
(deftest editing (is (= "00:00:02:00" (nle/timecode 60 30))) (is (= 100 (nle/duration-frames p)))
 (is (= 2 (count (get-in (nle/split-clip p "c" 40 "c-b") [:project/tracks 0 :track/clips])))) (is (empty? (nle/validate-project p))))
