(ns amperity.gocd.agent.aurora.cluster
  "Cluster profile definition and functions."
  (:require
    [clojure.string :as str]))


;; ## Cluster Profiles

(def profile-metadata
  "Schema for a cluster profile map."
  [{:key "aurora_url"
    :metadata {:required true, :secure false}}
   {:key "aurora_cluster"
    :metadata {:required true, :secure false}}
   {:key "aurora_role"
    :metadata {:required true, :secure false}}
   {:key "aurora_env"
    :metadata {:required true, :secure false}}])


(defn migrate-profile
  "Migrate an existing map of profile settings to the latest representation."
  [old]
  ;; TODO: migrate
  old)


(defn validate-profile
  "Validate profile settings, returning a sequence of any errors found. Each
  error should be a map with `:key` and `:message` entries."
  [settings]
  ;; TODO: validate cluster profile settings
  ;; {:key "foo", :message "..."}
  [])
