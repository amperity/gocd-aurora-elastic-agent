(ns amperity.gocd.agent.aurora.agent
  "Agent profile definition and functions."
  (:require
    [clojure.string :as str])
  (:import
    java.time.Instant))


;; ## Agent Profiles

(def profile-metadata
  "Schema for an elastic agent profile map."
  [{:key :agent_tag
    :metadata {:required true, :secure false}}
   ;; Agent Resources
   {:key :cpu
    :metadata {:required true, :secure false}}
   {:key :ram
    :metadata {:required true, :secure false}}
   {:key :disk
    :metadata {:required true, :secure false}}
   ;; Agent Setup
   {:key :fetch_url
    :metadata {:required false, :secure false}}
   {:key :init_script
    :metadata {:required false, :secure false}}])


(defn migrate-settings
  "Migrate an existing map of profile settings to the latest representation."
  [settings]
  {:agent_tag (:agent_tag settings)
   :cpu (:cpu settings)
   :ram (:ram settings)
   :disk (:disk settings)
   :fetch_url (:fetch_url settings)
   :init_script (:init_script settings)})


(defn- validate-number
  "Validate a numeric setting. Returns an error map or nil if the
  setting is valid."
  [settings field-key label parse min-val max-val]
  (->
    (try
      (let [value (get settings field-key)
            value (cond
                    (number? value) (double value)
                    (str/blank? value) nil
                    :else (parse value))]
         (cond
           (nil? value)
           (str label " is required")

           (< value min-val)
           (str label " must be at least " min-val)

           (< max-val value)
           (str label " must be at most " max-val)))
      (catch Exception ex
        (str "Could not parse " label " as a number")))
    (as-> message
      (when message
        {:key field-key
         :message message}))))


(defn- validate-float
  "Validate a floating-point number."
  [settings field-key label min-val max-val]
  (validate-number settings field-key label #(Double/parseDouble %) min-val max-val))


(defn- validate-int
  "Validate an integer number."
  [settings field-key label min-val max-val]
  (validate-number settings field-key label #(Integer/parseInt %) min-val max-val))


(defn validate-settings
  "Validate profile settings, returning a sequence of any errors found. Each
  error should be a map with `:key` and `:message` entries."
  [settings]
  (into
    []
    (remove nil?)
    [(when (str/blank? (:agent_tag settings))
       {:key :agent_tag
        :message "Agent tag prefix is required"})
     (validate-float settings :cpu "cpu allocation" 0.1 32.0)
     (validate-int settings :ram "memory allocation" 256 16384)
     (validate-int settings :disk "disk allocation" 256 16384)]))



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



;; ## Agent State

(comment
  {:state :running
   :environment "build"
   :resources {:cpu 1.0, :ram 1024, :disk 1024}
   :last-active #inst "2019-08-10T14:16:00Z"
   :idle? true
   ;; TODO: launched-for?
   :events [{:time #inst "2019-08-10T13:55:23Z"
             :state :launching
             :message "..."}
            ...]})


(defn init-state
  "Initialize a new agent using the given profile."
  [agent-id state agent-profile gocd-environment]
  {:agent-id agent-id
   :state state
   :environment gocd-environment
   :resources (profile->resources agent-profile)
   :last-active (Instant/now)
   :events []})


(defn update-state
  "Updates the agent's state and adds a new event to the history."
  [agent-state state message]
  (-> agent-state
      (assoc :state state)
      (update :events
              (fnil conj [])
              {:time (Instant/now)
               :state state
               :message message})))


(defn mark-idle
  "Mark the identified agent as being idle."
  [agent-state]
  (assoc agent-state :idle? true))


(defn mark-active
  "Mark the identified agent as being recently active."
  [agent-state]
  (assoc agent-state
         :last-active (Instant/now)
         :idle? false))


(defn stale?
  "True if the agent's last event timestamp is present and more than
  `threshold` seconds in the past."
  [agent-state threshold]
  (let [^Instant last-time (last (keep :time (:events agent-state)))
        horizon (.minusSeconds (Instant/now) threshold)]
    (and last-time (.isBefore last-time horizon))))


(defn idle?
  "True if the agent is `:idle?` and its `:last-active` timestamp is present
  and more than `threshold` seconds in the past."
  [agent-state threshold]
  (and (:idle? agent-state)
       (:last-active agent-state)
       (not (.isBefore (.minusSeconds (Instant/now) threshold)
                       (:last-active agent-state)))))
