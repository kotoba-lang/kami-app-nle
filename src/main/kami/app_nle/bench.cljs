(ns kami.app-nle.bench)

(def actors
  [{:id "solo-creator" :label "Solo creator" :tasks ["Import media" "Make a rough cut" "Export a review"]}
   {:id "assistant-editor" :label "Assistant editor" :tasks ["Organize a bin" "Relink a proxy" "Sync a take"]}
   {:id "colorist" :label "HDR / colorist" :tasks ["Apply a look" "Check scopes" "Export HDR metadata"]}
   {:id "broadcast-operator" :label "Broadcast delivery operator" :tasks ["Run QC" "Export MOV / MXF" "Fix a delivery error"]}
   {:id "rights-librarian" :label "Rights / asset librarian" :tasks ["Verify a CID" "Inspect provenance" "Handle revoked media"]}
   {:id "accessibility-reviewer" :label "Accessibility reviewer" :tasks ["Check captions" "Use keyboard navigation" "Review audio description"]}])

(defn initial-run [] {:actor-id (:id (first actors)) :task-index 0 :events [] :started-at (.now js/Date)})
(defn current-task [run] (get-in (some #(when (= (:id %) (:actor-id run)) %) actors) [:tasks (:task-index run)]))
(defn record! [run-atom kind payload] (swap! run-atom update :events conj (merge {:kind kind :at (.now js/Date)} payload)))
