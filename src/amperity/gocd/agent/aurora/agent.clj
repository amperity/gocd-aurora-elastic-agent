(ns amperity.gocd.agent.aurora.agent
  "Agent profile definition and functions."
  (:require
    [clojure.string :as str]))


;; ## Agent Profiles

(def profile-metadata
  "Schema for an elastic agent profile map."
  [{:key :cpu
    :metadata {:required true, :secure false}}
   {:key :ram
    :metadata {:required true, :secure false}}
   {:key :disk
    :metadata {:required false, :secure false}}])


(defn- migrate-profile-properties
  "Migrate an existing map of profile settings to the latest representation."
  [properties]
  (into {}
        (remove (comp str/blank? val))
        {:cpu (:cpu properties)
         :ram (:ram properties)
         :disk (:disk properties)}))


(defn migrate-profile
  "Migrate an existing map of profile settings to the latest representation."
  [old]
  (update old :properties migrate-profile-properties))


(defn validate-profile
  "Validate profile settings, returning a sequence of any errors found. Each
  error should be a map with `:key` and `:message` entries."
  [settings]
  (into
    []
    (remove nil?)
    [(let [cpu (:cpu settings)]
       (if (str/blank? cpu)
         {:key :cpu
          :message "CPU resource allocation must be provided"}
         (try
           (Double/parseDouble (:cpu settings))
           nil
           (catch Exception ex
             {:key :cpu
              :message "Could not parse cpu allocation as a floating-point number"}))))
     (let [ram (:ram settings)]
       (if (str/blank? ram)
         {:key :ram
          :message "Memory resource allocation must be provided"}
         (try
           (Integer/parseInt ram)
           nil
           (catch Exception ex
             {:key :ram
              :message "Could not parse memory allocation as an integer"}))))
     (let [disk (:disk settings)]
       (when-not (str/blank? disk)
         (try
           (Integer/parseInt disk)
           nil
           (catch Exception ex
             {:key :disk
              :message "Could not parse disk allocation as an integer"}))))]))



;; ## Agent Identifiers

(defn form-id
  "Form an agent identifier from a map of the aurora cluster, role, and env,
  and the agent name."
  ([params]
   (form-id
     (:aurora-cluster params)
     (:aurora-role params)
     (:aurora-env params)
     (:agent-name params)))
  ([aurora-cluster aurora-role aurora-env agent-name]
   (str/join "/" [aurora-cluster aurora-role aurora-env agent-name])))


(defn parse-id
  "Parse an agent identifier, returning a map of the aurora cluster, role, and
  env, and the agent name."
  [agent-id]
  (let [[cluster role env name] (str/split agent-id #"/")]
    {:aurora-cluster cluster
     :aurora-role role
     :aurora-env env
     :agent-name name}))



;; ## Resources

(def default-resources
  "Default set of resources to provide agent jobs."
  {:cpu 1.0
   :ram 512
   :disk 1024})


(defn profile->resources
  "Construct a map of required Aurora resources for an elastic agent profile."
  [profile]
  (into {}
        (filter val)
        {:cpu (let [cpu (:cpu profile)]
                (when-not (str/blank? cpu)
                  (Double/parseDouble cpu)))
         :ram (let [ram (:ram profile)]
                (when-not (str/blank? ram)
                  (Integer/parseInt ram)))
         :disk (let [disk (:disk profile)]
                 (when-not (str/blank? disk)
                   (Integer/parseInt disk)))}))


(defn resource-satisfied?
  "True if the resources required in profile `a` are satisfied by an agent with
  profile `b`."
  [a b]
  (every?
    (fn check-resource
      [[resource requirement]]
      (when-let [available (get b resource)]
        (<= requirement available)))
    a))
