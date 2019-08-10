(ns amperity.gocd.agent.aurora.cluster
  "Cluster profile definition and functions."
  (:require
    [clojure.string :as str]))


(def default-agent-source-url
  (let [version "19.7.0"
        build "9567"
        coord (str version "-" build)]
    (str "https://download.gocd.org/binaries/" coord
         "/generic/go-agent-" coord ".zip")))



;; ## Cluster Profiles

(def profile-metadata
  "Schema for a cluster profile map."
  [{:key :aurora_url
    :metadata {:required true, :secure false}}
   {:key :aurora_cluster
    :metadata {:required true, :secure false}}
   {:key :aurora_role
    :metadata {:required true, :secure false}}
   {:key :aurora_env
    :metadata {:required true, :secure false}}
   {:key :agent_source_url
    :metadata {:required false, :secure false}}])


(defn- migrate-profile-properties
  "Migrate an existing map of profile settings to the latest representation."
  [properties]
  (into {}
        (remove (comp str/blank? val))
        {:aurora_url (:aurora_url properties)
         :aurora_cluster (:aurora_cluster properties)
         :aurora_role (:aurora_role properties)
         :aurora_env (:aurora_env properties)
         :agent_source_url (:agent_source_url properties)}))


(defn migrate-profile
  "Migrate an existing map of profile settings to the latest representation."
  [old]
  (update old :properties migrate-profile-properties))


(defn validate-profile
  "Validate profile settings, returning a sequence of any errors found. Each
  error should be a map with `:key` and `:message` entries."
  [settings]
  ;; TODO: validate cluster profile settings
  ;; {:key "foo", :message "..."}
  [])
