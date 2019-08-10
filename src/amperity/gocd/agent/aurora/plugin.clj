(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.cluster :as cluster]
    [amperity.gocd.agent.aurora.job :as job]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    com.google.gson.Gson
    (com.thoughtworks.go.plugin.api
      GoApplicationAccessor
      GoPluginIdentifier)
    (com.thoughtworks.go.plugin.api.exceptions
      UnhandledRequestTypeException)
    (com.thoughtworks.go.plugin.api.request
      DefaultGoApiRequest
      GoPluginApiRequest)
    (com.thoughtworks.go.plugin.api.response
      DefaultGoPluginApiResponse
      GoPluginApiResponse)
    java.time.Instant))


;; ## State Initialization

(def ^:private plugin-identifier
  "Identifier for the type of plugin and compatible API versions."
  (GoPluginIdentifier. "elastic-agent" ["5.0"]))


(defn initialize
  "Initialize the plugin state, returning an initial value for the state atom."
  [logger ^GoApplicationAccessor app-accessor]
  (alter-var-root #'log/logger (constantly logger))
  (let [req (DefaultGoApiRequest. "go.processor.server-info.get" "1.0" plugin-identifier)
        res (.submit app-accessor req)
        server-info (when (= 200 (.responseCode res))
                      (u/json-decode-map (.responseBody res)))
        server-url (if-let [site-url (:site_url server-info)]
                     (str site-url "/go")
                     "http://localhost:8153/go")]
    (log/info "Got server-info status %s and body: %s"
              (.responseCode res)
              (.responseBody res))
    {:app-accessor app-accessor
     :server-url server-url
     :clients {}
     :clusters {}
     :agents {}}))



;; ## Request Handling

(defmulti handle-request
  "Handle a plugin API request and respond. Methods should return `true` for an
  empty success response, a data structure to coerce into a successful JSON
  response, or a custom `GoPluginApiResponse`."
  (fn dispatch
    [state req-name data]
    req-name))


(defmethod handle-request :default
  [_ req-name _]
  (throw (UnhandledRequestTypeException. req-name)))


(defn handler
  "Request handling entry-point."
  [state ^GoPluginApiRequest request]
  (try
    (let [req-name (.requestName request)
          req-data (when-not (str/blank? (.requestBody request))
                     (u/json-decode-map (.requestBody request)))
          result (handle-request state req-name req-data)]
      (cond
        (true? result)
        (DefaultGoPluginApiResponse/success "")

        (instance? GoPluginApiResponse result)
        result

        :else
        (DefaultGoPluginApiResponse/success (u/json-encode result))))
    (catch UnhandledRequestTypeException ex
      (throw ex))
    (catch Exception ex
      (log/errorx ex "Failed to process %s plugin request" (.requestName request))
      (DefaultGoPluginApiResponse/error (.getMessage ex)))))



;; ## Plugin Metadata

;; This call is expected to return the icon for the plugin, so as to make
;; it easy for users to identify the plugin.
(defmethod handle-request "cd.go.elastic-agent.get-icon"
  [_ _ _]
  (let [icon-svg (slurp (io/resource "amperity/gocd/agent/aurora/logo.svg"))]
    {:content_type "image/svg+xml"
     :data (u/b64-encode-str icon-svg)}))


;; This message is a request to the plugin to provide plugin capabilities.
;; Based on these capabilities GoCD would enable or disable the plugin
;; features for a user.
(defmethod handle-request "cd.go.elastic-agent.get-capabilities"
  [_ _ _]
  {:supports_plugin_status_report false
   :supports_cluster_status_report false
   :supports_agent_status_report false})


;; This message is a request to the plugin perform the migration on the
;; existing config on load of the plugin. This allows a plugin to perform
;; the migration on the existing config in order to support the newer
;; version of the plugin.
(defmethod handle-request "cd.go.elastic-agent.migrate-config"
  [_ _ data]
  (let [cluster-profiles (:cluster_profiles data)
        agent-profiles (:elastic_agent_profiles data)]
    {:cluster_profiles (mapv cluster/migrate-profile cluster-profiles)
     :elastic_agent_profiles (mapv agent/migrate-profile agent-profiles)}))



;; ## Status Reports

;; If plugin supports status report, this message must be implemented to report
;; the status of a particular elastic agent brought up by the plugin to run a
;; job. The purpose of this call is to provide specific information about the
;; current state of the elastic agent.
(defmethod handle-request "cd.go.elastic-agent.agent-status-report"
  [_ _ data]
  (let [agent-id (:elastic_agent_id data)
        cluster-profile (:cluster_profile_properties data)
        job-info (:job_identifier data)]
    ;; TODO: implement agent status report
    {:view "<span><strong>NYI:<strong> agent status</span>"}))


;; If plugin supports cluster status report, this message must be implemented
;; to provide the overall status of the cluster.
(defmethod handle-request "cd.go.elastic-agent.cluster-status-report"
  [_ _ data]
  (let [cluster-profile (:cluster_profile_properties data)]
    ;; TODO: implement cluster status report
    {:view "<span><strong>NYI:<strong> cluster status</span>"}))


;; If plugin supports the plugin status report, this message must be
;; implemented to provide the overall status of the environment.
(defmethod handle-request "cd.go.elastic-agent.plugin-status-report"
  [_ _ data]
  (let [cluster-profiles (:all_cluster_profile_properties data)]
    ;; TODO: implement plugin status report
    {:view "<span><strong>NYI:<strong> plugin status</span>"}))



;; ## Cluster Profiles

;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-view"
  [_ _ _]
  (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/cluster-profile-view.html"))]
    {:template view-html}))


;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-metadata"
  [_ _ _]
  cluster/profile-metadata)


;; This call is expected to validate the user inputs that form a part of
;; the cluster profile.
(defmethod handle-request "cd.go.elastic-agent.validate-cluster-profile"
  [_ _ settings]
  (cluster/validate-profile settings))



;; ## Elastic Agent Profiles

;; This is a message that the plugin should implement, to allow users to
;; configure elastic agent profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-elastic-agent-profile-view"
  [_ _ _]
  (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/elastic-agent-profile-view.html"))]
    {:template view-html}))


;; This is a message that the plugin should implement, to allow users to
;; configure elastic agent profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-elastic-agent-profile-metadata"
  [_ _ _]
  agent/profile-metadata)


;; This call is expected to validate the user inputs that form a part of the
;; elastic agent profile.
(defmethod handle-request "cd.go.elastic-agent.validate-elastic-agent-profile"
  [_ _ settings]
  (agent/validate-profile settings))



;; ## Agent Lifecycle

;; Will need an Aurora client per stack; lock on client access.
;; Need to track what agents are alive and which are currently occupied.
;; Need a matching algorithm to compare an agent to a profile requirement.
;; Need the interop to create a new agent service; probably want to run under
;; `{{cluster}}/{{role}}/prod/{{type}}-agent-{{id}}` so an example `aws-dev/www-data/prod/test-agent-0`


(defn- list-gocd-agents
  "List the GoCD agent registrations for this plugin."
  [^GoApplicationAccessor app-accessor]
  (let [req (DefaultGoApiRequest. "go.processor.elastic-agents.list-agents" "1.0" plugin-identifier)
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to list GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    (u/json-decode-vec (.responseBody res))))


(defn- disable-gocd-agents
  "Disable agents in GoCD."
  [^GoApplicationAccessor app-accessor agent-ids]
  (let [agents (mapv (partial array-map :agent_id) agent-ids)
        req (doto (DefaultGoApiRequest. "go.processor.elastic-agents.disable-agents" "1.0" plugin-identifier)
              (.setRequestBody (u/json-encode agents)))
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to disable GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    true))


(defn- delete-gocd-agents
  "Delete agents in GoCD. Agents must be disabled first."
  [^GoApplicationAccessor app-accessor agent-ids]
  (let [agents (mapv (partial array-map :agent_id) agent-ids)
        req (doto (DefaultGoApiRequest. "go.processor.elastic-agents.delete-agents" "1.0" plugin-identifier)
              (.setRequestBody (u/json-encode agents)))
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to delete GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    true))


;; Each elastic agent plugin will receive a periodic signal at regular
;; intervals for it to perform any cleanup operations. Plugins may use this
;; message to disable and/or terminate agents at their discretion.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.server-ping"
  [state _ data]
  (log/info "server-ping: %s" (pr-str data))
  (log/info "plugin state: %s" (pr-str @state))
  (let [cluster-profiles (:all_cluster_profile_properties data)
        app-accessor (:app-accessor @state)
        gocd-agents (list-gocd-agents app-accessor)]
    (log/debug "Checking %d clusters" (count cluster-profiles))
    (doseq [cluster-profile cluster-profiles]
      (log/info "Checking cluster: %s" (pr-str cluster-profile))
      ;; TODO: update cluster quota information
      ,,,)
    (log/debug "Checking %d agents" (count gocd-agents))
    (doseq [gocd-state gocd-agents]
      (log/info "Checking agent: %s" (pr-str gocd-state))
      (let [agent-id (:agent_id gocd-state)
            agent-state (:agent_state gocd-state)
            build-state (:build_state gocd-state)
            config-state (:config_state gocd-state)
            enabled? (= "Enabled" config-state)
            idle? (= "Idle" agent-state)
            aurora-cluster (:aurora-cluster (agent/parse-id agent-id))
            cluster-profile (first (filter #(= aurora-cluster (:aurora_cluster %))
                                           cluster-profiles))
            aurora-client (aurora/get-client state (:aurora_url cluster-profile))
            ^Instant last-active (get-in @state [:agents agent-id :last-active])
            ;; TODO: make this configurable?
            ttl-seconds 60]
        ;; Observed values:
        ;; | agent_state | build_state | config_state |
        ;; |-------------|-------------|--------------|
        ;; | Missing     | Unknown     | Enabled      |
        ;; | LostContact | Unknown     | Enabled      |
        ;; | LostContact | Unknown     | Disabled     |
        ;; | Idle        | Idle        | Enabled      |
        ;; | Building    | Building    | Enabled      |
        (cond
          ;; Agent is in a bad state, try to clean it up.
          (contains? #{"Missing" "LostContact"} agent-state)
          (let [agent-task (aurora/get-agent aurora-client agent-id)]
            (when enabled?
              (log/info "Disabling agent %s in state %s" agent-id agent-state)
              (disable-gocd-agents app-accessor #{agent-id}))
            (if (contains? #{:active :pending} (:status agent-task))
              ;; Kill agent.
              (do
                (log/info "Killing %s agent %s in state %s"
                          (name (:status agent-task))
                          agent-id
                          agent-state)
                (aurora/kill-agent! aurora-client agent-id))
              ;; Agent has shut down probably.
              (do
                (log/info "Removing %s agent %s"
                          (some-> (:status agent-task) name)
                          agent-id)
                (delete-gocd-agents app-accessor #{agent-id})
                (swap! state update :agents dissoc agent-id))))

          ;; Healthy agent.
          enabled?
          (if (and idle? last-active)
            (if (.isAfter (Instant/now) (.plusSeconds last-active ttl-seconds))
              (do
                (log/info "Retiring idle agent %s" agent-id)
                (disable-gocd-agents app-accessor #{agent-id}))
              (log/info "Agent %s is healthy" agent-id))
            ;; Update last-active timestamp.
            (do
              (log/info "Updating last-active timestamp for agent %s" agent-id)
              (swap! state assoc-in [:agents agent-id :last-active] (Instant/now)))))

          ;; Disabled agent is still busy, wait for it to drain.
          (not idle?)
          (log/info "Waiting for disabled agent %s to drain" agent-id)

          ;; Agent is disabled and quiescent, see if it it has been terminated.
          :else
          (let [agent-task (aurora/get-agent aurora-client agent-id)]
            (if (contains? #{:active :pending} (:status agent-task))
              ;; Kill agent.
              (do
                (log/info "Killing retired agent %s" agent-id)
                (aurora/kill-agent! aurora-client agent-id))
              ;; Agent has shut down probably.
              (do
                (log/info "Removing retired agent %s" agent-id)
                (delete-gocd-agents app-accessor #{agent-id})
                (swap! state update :agents dissoc agent-id))))))
    true))


(defn- next-agent-name
  "Determine the next available agent name given the running agents."
  [state cluster-profile agent-prefix]
  (loop [agent-num 0]
    (let [agent-name (str agent-prefix "-agent-" agent-num)
          agent-id (agent/form-id
                       (:aurora_cluster cluster-profile)
                       (:aurora_role cluster-profile)
                       (:aurora_env cluster-profile)
                       agent-name)]
      (if (contains? (:agents @state) agent-id)
        (recur (inc agent-num))
        agent-id))))


(defn- launch-agent!
  "Launch a new agent."
  [state cluster-profile agent-profile gocd-auto-register-key gocd-environment]
  (let [aurora-client (aurora/get-client state (:aurora_url cluster-profile))]
    (locking aurora-client
      ;; TODO: configurable prefix
      (let [agent-name (next-agent-name state cluster-profile "test")
            agent-id (agent/form-id
                       (:aurora_cluster cluster-profile)
                       (:aurora_role cluster-profile)
                       (:aurora_env cluster-profile)
                       agent-name)
            source-url (if (str/blank? (:agent_source_url cluster-profile))
                         cluster/default-agent-source-url
                         (:agent_source_url cluster-profile))
            agent-task (job/agent-task
                         agent-name
                         {:server-url (:server-url @state)
                          :agent-source-url source-url
                          :auto-register-hostname agent-name
                          :auto-register-environment gocd-environment
                          :auto-register-key gocd-auto-register-key
                          :elastic-plugin-id u/plugin-id
                          :elastic-agent-id agent-id})]
        (aurora/launch-agent!
          aurora-client
          cluster-profile
          agent-profile
          agent-name
          agent-task)
        (swap! state assoc-in [:agents agent-id]
               {:environment gocd-environment
                :resources (agent/profile->resources agent-profile)
                :last-active (Instant/now)})
        agent-id))))


;; This message is a request to the plugin to create an agent for a job
;; that has been scheduled.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.create-agent"
  [state _ data]
  (log/debug "create-agent: %s" (pr-str data))
  (let [cluster-profile (:cluster_profile_properties data)
        agent-profile (:elastic_agent_profile_properties data)
        gocd-job (:job_identifier data)
        app-accessor (:app-accessor @state)
        state-agents (:agents @state)
        gocd-agents (list-gocd-agents app-accessor)
        candidates (into []
                         (filter
                           (fn candidate?
                             [gocd-agent]
                             (let [agent-id (:agent_id gocd-agent)
                                   agent-state (get state-agents agent-id)]
                               (and (= "Enabled" (:config_state gocd-agent))
                                    (= "Idle" (:agent_state gocd-agent))
                                    (= (:environment data) (:environment agent-state))
                                    (agent/resource-satisfied?
                                      agent-profile
                                      (:resources agent-state))))))
                         gocd-agents)]
    (cond
      ;; Some agents are already available to handle the work.
      (seq candidates)
      (log/info "Not launching new agent because %d candidates are available: %s"
                (count candidates)
                (str/join " " candidates))

      ;; TODO: check cluster quota
      false
      (log/info "Not launching new agent because cluster %s is at capacity (%s)"
                (:aurora_cluster cluster-profile)
                "...")

      :else
      (launch-agent!
        state
        cluster-profile
        agent-profile
        (:auto_register_key data)
        (:environment data))))
  true)


;; When there are multiple agents available to run a job, the server will
;; ask the plugin if jobs should be assigned to a particular agent. The
;; request will contain information about the agent, the job configuration
;; and the environment that the agent belongs to. This allows plugin to
;; decide if proposed agent is suitable to schedule a job on it. For
;; example, plugin can check if flavor or region of VM is suitable.
(defmethod handle-request "cd.go.elastic-agent.should-assign-work"
  [state _ data]
  (log/info "should-assign-work: %s" (pr-str data))
  (let [cluster-profile (:cluster_profile_properties data)
        agent-profile (:elastic_agent_profile_properties data)
        agent-info (:agent data)
        agent-id (:agent_id agent-info)
        gocd-job (:job_identifier data)
        job-id (str (:pipeline_name data) "/"
                    (:pipeline_counter data) "/"
                    (:stage_name data) "/"
                    (:stage_counter data) "/"
                    (:job_name data))]
    (->
      (if-let [resources (get-in @state [:agents agent-id :resources])]
        ;; Determine if job requirements are satisfied by the agent.
        (agent/resource-satisfied? agent-profile resources)
        ;; No resources recorded for this agent, don't assign work to it.
        false)
      (boolean)
      ;; DEBUG
      (as-> decision
        (do (log/info "Decided %s assign job %s to agent %s"
                      (if decision "to" "not to")
                      job-id
                      agent-id)
            decision))
      (str)
      (DefaultGoPluginApiResponse/success))))


;; The intent on this message is to notify the plugin on completion of the job.
;; The plugin may choose to terminate the elastic agent or keep it running in
;; case the same agent can be used for another job configuration.
(defmethod handle-request "cd.go.elastic-agent.job-completion"
  [state _ data]
  (log/info "job-completion: %s" (pr-str data))
  (let [agent-id (:elastic_agent_id data)
        agent-profile (:elastic_agent_profile_properties data)
        cluster-profile (:cluster_profile_properties data)
        gocd-job (:job_identifier data)]
    (swap! state update-in [:agents agent-id] assoc
           :resources (agent/profile->resources agent-profile)
           :last-active (Instant/now))
    true))
