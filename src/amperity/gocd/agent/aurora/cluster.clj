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
  [;; Aurora cluster
   {:key :aurora_url
    :metadata {:required true, :secure false}}
   {:key :aurora_cluster
    :metadata {:required true, :secure false}}
   {:key :aurora_role
    :metadata {:required true, :secure false}}
   {:key :aurora_env
    :metadata {:required true, :secure false}}
   ;; Agent settings
   {:key :server_api_url
    :metadata {:required true, :secure false}}
   {:key :agent_source_url
    :metadata {:required false, :secure false}}])


(defn migrate-settings
  "Migrate an existing map of profile settings to the latest representation."
  [settings]
  {:aurora_url (:aurora_url settings)
   :aurora_cluster (:aurora_cluster settings)
   :aurora_role (:aurora_role settings)
   :aurora_env (:aurora_env settings)
   :server_api_url (or (:server_api_url settings)
                       "http://localhost:8153/go")
   :agent_source_url (:agent_source_url settings)})


(defn- validate-string
  "Validate that a string with the given key is set and non-blank."
  [settings field-key label]
  (when (str/blank? (get settings field-key))
    {:key field-key
     :message (str label " is required")}))


(defn validate-settings
  "Validate profile settings, returning a sequence of any errors found. Each
  error should be a map with `:key` and `:message` entries."
  [settings]
  (into
    []
    (remove nil?)
    [(validate-string settings :aurora_url "Aurora URL")
     (validate-string settings :aurora_cluster "Aurora cluster")
     (validate-string settings :aurora_role "Aurora role")
     (validate-string settings :aurora_env "Aurora environment")
     (validate-string settings :server_api_url "Server API URL")]))



;; ## Quota Management

(comment
  {:available {:cpu 0.0, :disk 0, :ram 0},
   :shares {:nonprod-dedicated {:cpu 0.0, :disk 0, :ram 0},
            :nonprod-shared {:cpu 11.475000000000001, :disk 42712, :ram 132631},
            :prod-dedicated {:cpu 0.0, :disk 0, :ram 0},
            :prod-shared {:cpu 0.0, :disk 0, :ram 0}},
   :usage {:cpu 11.475000000000001, :disk 42712, :ram 132631}})


(defn quota-available?
  "True if there is enough available quota for the requested resources."
  [quota resources]
  (every?
    (fn satisfied?
      [[resource required]]
      (let [available (get-in quota [:available resource] 0)
            usage (get-in quota [:usage resource] 0)]
        (or (zero? available) (<= (+ usage required) available))))
    resources))
