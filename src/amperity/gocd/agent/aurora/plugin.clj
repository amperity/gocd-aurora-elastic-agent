(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:require
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    com.google.gson.Gson
    (com.thoughtworks.go.plugin.api
      GoApplicationAccessor)
    (com.thoughtworks.go.plugin.api.exceptions
      UnhandledRequestTypeException)
    (com.thoughtworks.go.plugin.api.request
      GoPluginApiRequest)
    (com.thoughtworks.go.plugin.api.response
      DefaultGoPluginApiResponse
      GoPluginApiResponse)))


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
      (DefaultGoPluginApiResponse/success (u/json-encode result)))))



;; ## Plugin Metadata

;; This call is expected to return the icon for the plugin, so as to make
;; it easy for users to identify the plugin.
(defmethod handle-request "cd.go.elastic-agent.get-icon"
  [_ _ _]
  (let [icon-svg (slurp (io/resource "amperity/gocd/agent/aurora/logo.svg"))]
    {"content_type" "image/svg+xml"
     "data" (u/b64-encode-str icon-svg)}))


;; This message is a request to the plugin to provide plugin capabilities.
;; Based on these capabilities GoCD would enable or disable the plugin
;; features for a user.
(defmethod handle-request "cd.go.elastic-agent.get-capabilities"
  [_ _ _]
  {"supports_plugin_status_report" false
   "supports_cluster_status_report" false
   "supports_agent_status_report" false})


;; This message is a request to the plugin perform the migration on the
;; existing config on load of the plugin. This allows a plugin to perform
;; the migration on the existing config in order to support the newer
;; version of the plugin.
(defmethod handle-request "cd.go.elastic-agent.migrate-config"
  [_ _ data]
  (let [cluster-profiles (get data "cluster_profiles")
        agent-profiles (get data "elastic_agent_profiles")]
    ;; TODO: validate and fixup any existing config
    {"cluster_profiles" (vec cluster-profiles)
     "elastic_agent_profiles" (vec agent-profiles)}))



;; ## Status Reports

;; If plugin supports status report, this message must be implemented to report
;; the status of a particular elastic agent brought up by the plugin to run a
;; job. The purpose of this call is to provide specific information about the
;; current state of the elastic agent.
(defmethod handle-request "cd.go.elastic-agent.agent-status-report"
  [_ _ data]
  (let [agent-id (get data "elastic_agent_id")
        cluster-profile (get data "cluster_profile_properties")
        job-info (get data "job_identifier")]
    ;; TODO: implement agent status report
    {"view" "<span><strong>NYI:<strong> agent status</span>"}))


;; If plugin supports cluster status report, this message must be implemented
;; to provide the overall status of the cluster.
(defmethod handle-request "cd.go.elastic-agent.cluster-status-report"
  [_ _ data]
  (let [cluster-profile (get data "cluster_profile_properties")]
    ;; TODO: implement cluster status report
    {"view" "<span><strong>NYI:<strong> cluster status</span>"}))


;; If plugin supports the plugin status report, this message must be
;; implemented to provide the overall status of the environment.
(defmethod handle-request "cd.go.elastic-agent.plugin-status-report"
  [_ _ data]
  (let [cluster-profiles (get data "all_cluster_profile_properties")]
    ;; TODO: implement plugin status report
    {"view" "<span><strong>NYI:<strong> plugin status</span>"}))



;; ## Cluster Profiles

;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-view"
  [_ _ _]
  (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/cluster-profile-view.html"))]
    {"template" view-html}))


;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-metadata"
  [_ _ _]
  [{"key" "aurora_url"
    "metadata" {"required" true, "secure" false}}
   {"key" "aurora_cluster"
    "metadata" {"required" true, "secure" false}}
   {"key" "aurora_role"
    "metadata" {"required" true, "secure" false}}
   {"key" "aurora_env"
    "metadata" {"required" true, "secure" false}}])


;; This call is expected to validate the user inputs that form a part of
;; the cluster profile.
(defmethod handle-request "cd.go.elastic-agent.validate-cluster-profile"
  [_ _ settings]
  ;; TODO: validate cluster profile settings
  ;; {"key": "foo", "message": "..."}
  [])



;; ## Elastic Agent Profiles

;; This is a message that the plugin should implement, to allow users to
;; configure elastic agent profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-elastic-agent-profile-view"
  [_ _ _]
  (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/elastic-agent-profile-view.html"))]
    {"template" view-html}))


;; This is a message that the plugin should implement, to allow users to
;; configure elastic agent profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-elastic-agent-profile-metadata"
  [_ _ _]
  [{"key" "agent_cpu"
    "metadata" {"required" true, "secure" false}}
   {"key" "agent_ram"
    "metadata" {"required" true, "secure" false}}])


;; This call is expected to validate the user inputs that form a part of the
;; elastic agent profile.
(defmethod handle-request "cd.go.elastic-agent.validate-elastic-agent-profile"
  [_ _ settings]
  ;; TODO: validate agent profile settings
  ;; {"key": "foo", "message": "..."}
  [])



;; ## Agent Lifecycle

;; Will need an Aurora client per stack; lock on client access.
;; Need to track what agents are alive and which are currently occupied.
;; Need a matching algorithm to compare an agent to a profile requirement.
;; Need the interop to create a new agent service; probably want to run under
;; `{{cluster}}/{{role}}/prod/{{type}}-agent-{{id}}` so an example `aws-dev/www-data/prod/test-agent-0`


;; Each elastic agent plugin will receive a periodic signal at regular
;; intervals for it to perform any cleanup operations. Plugins may use this
;; message to disable and/or terminate agents at their discretion.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.server-ping"
  [state _ data]
  (let [cluster-profiles (get data "all_cluster_profile_properties")]
    ;; TODO: terminate idle agents
    ;; - Take list of known agents
    ;; - Maybe call Aurora to check on their statuses and remove crashed ones?
    ;; - Maybe call GoCD accessor to refresh agent status?
    ;; - Remove any agents known to be busy
    ;; - Filter to agents whose last job was more than five minutes ago
    ;; - Terminate those agents, update internal state
    true))


;; This message is a request to the plugin to create an agent for a job
;; that has been scheduled.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.create-agent"
  [state _ data]
  ;; {
  ;;   "auto_register_key": "1e0e05fc-eb45-11e5-bc83-93882adfccf6",
  ;;   "elastic_agent_profile_properties": {
  ;;     "Image": "gocd/gocd-agent-alpine-3.5:v18.1.0",
  ;;     "MaxMemory": "https://docker-uri/"
  ;;   },
  ;;   "cluster_profile_properties": {
  ;;     "Image": "DockerURI",
  ;;     "MaxMemory": "500Mb"
  ;;   },
  ;;   "environment": "prod",
  ;;   "job_identifier": {
  ;;     "job_id": 100,
  ;;     "job_name": "test-job",
  ;;     "pipeline_counter": 1,
  ;;     "pipeline_label": "build",
  ;;     "pipeline_name": "build",
  ;;     "stage_counter": "1",
  ;;     "stage_name": "test-stage"
  ;;   }
  ;; }
  ;; TODO: implement create-agent logic
  ;; - Take list of known agents
  ;; - Maybe call Aurora to check on their statuses and remove crashed ones?
  ;; - Maybe call GoCD accessor to refresh agent status?
  ;; - Filter to agents who could be assigned the job (matching environment and compatible agent profile)
  ;; - If no available agents, check overall capacity
  ;; - If capacity, launch an agent service in Aurora
  true)


;; When there are multiple agents available to run a job, the server will
;; ask the plugin if jobs should be assigned to a particular agent. The
;; request will contain information about the agent, the job configuration
;; and the environment that the agent belongs to. This allows plugin to
;; decide if proposed agent is suitable to schedule a job on it. For
;; example, plugin can check if flavor or region of VM is suitable.
(defmethod handle-request "cd.go.elastic-agent.should-assign-work"
  [state _ data]
  ;; {
  ;;   "agent": {
  ;;     "agent_id": "i-283432d4",
  ;;     "agent_state": "Idle",
  ;;     "build_state": "Idle",
  ;;     "config_state": "Enabled"
  ;;   },
  ;;   "environment": "staging",
  ;;   "job_identifier": {
  ;;     "job_id": 100,
  ;;     "job_name": "run-upgrade",
  ;;     "pipeline_counter": 1,
  ;;     "pipeline_label": "build",
  ;;     "pipeline_name": "build",
  ;;     "stage_counter": "1",
  ;;     "stage_name": "test-stage"
  ;;   },
  ;;   "elastic_agent_profile_properties": {
  ;;       "Image": "gocd/gocd-agent-alpine-3.5:v18.1.0",
  ;;       "MaxMemory": "https://docker-uri/"
  ;;   },
  ;;   "cluster_profile_properties": {
  ;;     "Image": "DockerURI",
  ;;     "MaxMemory": "500Mb"
  ;;   }
  ;; }
  ;; TODO: implement should-assign-work logic
  ;; - Is the agent in the right environment? (maybe automatic)
  ;; - Does the agent have compatible profile settings?
  (DefaultGoPluginApiResponse/error "NYI"))


;; The intent on this message is to notify the plugin on completion of the job.
;; The plugin may choose to terminate the elastic agent or keep it running in
;; case the same agent can be used for another job configuration.
(defmethod handle-request "cd.go.elastic-agent.job-completion"
  [state _ data]
  ;; {
  ;;   "elastic_agent_id": "GoCD18efbeef995e40f688cd92dc22a4d332",
  ;;   "elastic_agent_profile_properties": {
  ;;     "Image": "gocd/gocd-agent-alpine-3.5:v18.1.0",
  ;;     "MaxMemory": "https://docker-uri/"
  ;;   },
  ;;   "cluster_profile_properties": {
  ;;     "Image": "DockerURI",
  ;;     "MaxMemory": "500Mb"
  ;;   },
  ;;   "job_identifier": {
  ;;     "job_id": 100,
  ;;     "job_name": "test-job",
  ;;     "pipeline_counter": 1,
  ;;     "pipeline_label": "build",
  ;;     "pipeline_name": "build",
  ;;     "stage_counter": "1",
  ;;     "stage_name": "test-stage"
  ;;   }
  ;; }
  ;; TODO: implement job-completion logic
  ;; - Mark the agent as idle in our internal state? May not matter if we're
  ;;   always refreshing.
  (DefaultGoPluginApiResponse/error "NYI"))
