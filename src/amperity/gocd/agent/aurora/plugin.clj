(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.cluster :as cluster]
    [amperity.gocd.agent.aurora.lifecycle :as lifecycle]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.server :as server]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
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

(defn initialize
  "Initialize the plugin state, returning an initial value for the state atom."
  [logger app-accessor]
  (alter-var-root #'log/logger (constantly logger))
  (let [server-info (server/get-server-info app-accessor)
        server-url (if-let [site-url (:site_url server-info)]
                     (str site-url "/go")
                     "http://localhost:8153/go")]
    (log/debug "Got server-info: %s" (pr-str server-info))
    {:app-accessor app-accessor
     :server-url server-url
     :clients {}
     :clusters {}
     :agents {}}))


(comment
  {:clusters
   {"aws-dev"
    {:quota {,,,}}}

   :agents
   {"aws-dev/www-data/prod/test-agent-0"
    {:environment "build"
     :resources {:cpu 1.0, :ram 1024, :disk 1024}
     :last-active #inst "2019-08-10T14:16:00Z"}}})



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
      (log/errorx ex "Failed to process %s plugin request%s"
                  (.requestName request)
                  (when-let [data (not-empty (ex-data ex))]
                    (str " " (pr-str data))))
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

;; Each elastic agent plugin will receive a periodic signal at regular
;; intervals for it to perform any cleanup operations. Plugins may use this
;; message to disable and/or terminate agents at their discretion.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.server-ping"
  [state _ data]
  (log/debug "server-ping: %s" (pr-str data))
  (log/info "plugin clusters: %s" (pr-str (:clusters @state)))
  (log/info "plugin agents: %s" (pr-str (:agents @state)))
  (let [cluster-profiles (:all_cluster_profile_properties data)
        app-accessor (:app-accessor @state)
        gocd-agents (server/list-agents app-accessor)]
    ;; Check on the status of each cluster.
    (doseq [cluster-profile cluster-profiles]
      (log/debug "Checking aurora cluster: %s" (pr-str cluster-profile))
      (lifecycle/kill-orphaned-agents! state cluster-profile gocd-agents)
      (lifecycle/update-cluster-quota state cluster-profile))
    ;; Check on the status of each agent.
    (doseq [gocd-agent gocd-agents]
      (log/debug "Checking gocd agent: %s" (pr-str gocd-agent))
      (lifecycle/manage-agent state cluster-profiles gocd-agent))
    true))


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
        gocd-agents (server/list-agents app-accessor)
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
                                      (agent/profile->resources agent-profile)
                                      (:resources agent-state))))))
                         gocd-agents)]
    (cond
      ;; Some agents are already available to handle the work.
      (seq candidates)
      (log/info "Not launching new agent because %d candidates are available in environment %s: %s"
                (count candidates)
                (:environment data)
                (str/join " " candidates))

      ;; TODO: check cluster quota
      false
      (log/info "Not launching new agent because cluster %s is at capacity (%s)"
                (:aurora_cluster cluster-profile)
                "...")

      :else
      (lifecycle/launch-agent!
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
  (log/debug "should-assign-work: %s" (pr-str data))
  (let [cluster-profile (:cluster_profile_properties data)
        agent-profile (:elastic_agent_profile_properties data)
        agent-info (:agent data)
        agent-id (:agent_id agent-info)
        gocd-job (:job_identifier data)
        job-id (str (:pipeline_name gocd-job) "/"
                    (:pipeline_label gocd-job) "/"
                    (:stage_name gocd-job) "/"
                    (:stage_counter gocd-job) "/"
                    (:job_name gocd-job))
        decision (if-let [resources (get-in @state [:agents agent-id :resources])]
                   ;; Determine if job requirements are satisfied by the agent.
                   (agent/resource-satisfied?
                     (agent/profile->resources agent-profile)
                     resources)
                   ;; No resources recorded for this agent, don't assign work to it.
                   false)]
    (when decision
      (log/info "Decided to assign job %s to agent %s" job-id agent-id)
      (swap! state assoc-in [:agents agent-id :last-active] (Instant/now)))
    (DefaultGoPluginApiResponse/success (str (boolean decision)))))


;; The intent on this message is to notify the plugin on completion of the job.
;; The plugin may choose to terminate the elastic agent or keep it running in
;; case the same agent can be used for another job configuration.
(defmethod handle-request "cd.go.elastic-agent.job-completion"
  [state _ data]
  (log/debug "job-completion: %s" (pr-str data))
  (let [agent-id (:elastic_agent_id data)
        agent-profile (:elastic_agent_profile_properties data)
        cluster-profile (:cluster_profile_properties data)
        gocd-job (:job_identifier data)]
    (swap! state assoc-in [:agents agent-id :last-active] (Instant/now))
    true))
