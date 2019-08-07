(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:require
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
      GoPluginApiResponse)
    java.util.Base64))


(let [gson (Gson.)]
  (defn- json-encode
    "Encode a value to a JSON string."
    ^String
    [value]
    (.toJson gson value))

  (defn- json-decode
    "Decode a map from a JSON string."
    [^String json]
    (into {} (.fromJson gson json java.util.Map))))



;; ## Request Handling

(defmulti handle-request
  "Handle a plugin API request and respond. Methods should return `true` for an
  empty success response, a data structure to coerce into a successful JSON
  response, or a custom `GoPluginApiResponse`."
  (fn dispatch
    [req-name data]
    req-name))


(defmethod handle-request :default
  [req-name data]
  (throw (UnhandledRequestTypeException. req-name)))


(defn handler
  "Request handling entry-point."
  [^GoPluginApiRequest request]
  (let [req-name (.requestName request)
        req-data (when-not (str/blank? (.requestBody request))
                   (json-decode (.requestBody request)))
        result (handle-request req-name req-data)]
    (cond
      (true? result)
      (DefaultGoPluginApiResponse/success "")

      (instance? GoPluginApiResponse result)
      result

      :else
      (DefaultGoPluginApiResponse/success (json-encode result)))))



;; ## Plugin Metadata

;; This call is expected to return the icon for the plugin, so as to make
;; it easy for users to identify the plugin.
(defmethod handle-request "cd.go.elastic-agent.get-icon"
  [_ _]
  (let [icon-svg (slurp (io/resource "amperity/gocd/agent/aurora/logo.svg"))]
    {"content_type" "image/svg+xml"
     "data" (.encodeToString (Base64/getEncoder) (.getBytes icon-svg))}))


;; This message is a request to the plugin to provide plugin capabilities.
;; Based on these capabilities GoCD would enable or disable the plugin
;; features for a user.
(defmethod handle-request "cd.go.elastic-agent.get-capabilities"
  [_ _]
  {"supports_plugin_status_report" false
   "supports_cluster_status_report" false
   "supports_agent_status_report" false})


;; This message is a request to the plugin perform the migration on the
;; existing config on load of the plugin. This allows a plugin to perform
;; the migration on the existing config in order to support the newer
;; version of the plugin.
(defmethod handle-request "cd.go.elastic-agent.migrate-config"
  [_ data]
  (let [cluster-profiles (get data "cluster_profiles")
        agent-profiles (get data "elastic_agent_profiles")]
    ;; TODO: validate and fixup any existing config
    {"cluster_profiles" (vec cluster-profiles)
     "elastic_agent_profiles" (vec agent-profiles)}))



;; ## Plugin Status

;; TODO: cd.go.elastic-agent.agent-status-report
;; TODO: cd.go.elastic-agent.cluster-status-report
;; TODO: cd.go.elastic-agent.plugin-status-report



;; ## Cluster Profiles

;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-view"
  [_ _]
  (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/cluster-profile-view.html"))]
    {"template" view-html}))


;; This is a message that the plugin should implement, to allow users to
;; configure cluster profiles from the Elastic Profiles View in GoCD.
(defmethod handle-request "cd.go.elastic-agent.get-cluster-profile-metadata"
  [_ _]
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
  [_ settings]
  ;; TODO: implement validation errors
  ;; {"key": "foo", "message": "..."}
  [])



;; ## Elastic Agent Profiles

;; - cd.go.elastic-agent.get-elastic-agent-profile-view
;; - cd.go.elastic-agent.get-elastic-agent-profile-metadata
;; - cd.go.elastic-agent.validate-elastic-agent-profile



;; ## Agent Lifecycle

;; Each elastic agent plugin will receive a periodic signal at regular
;; intervals for it to perform any cleanup operations. Plugins may use this
;; message to disable and/or terminate agents at their discretion.
;; TODO: "cd.go.elastic-agent.server-ping"


;; This message is a request to the plugin to create an agent for a job
;; that has been scheduled.
;;
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
;; TODO: "cd.go.elastic-agent.create-agent"


;; When there are multiple agents available to run a job, the server will
;; ask the plugin if jobs should be assigned to a particular agent. The
;; request will contain information about the agent, the job configuration
;; and the environment that the agent belongs to. This allows plugin to
;; decide if proposed agent is suitable to schedule a job on it. For
;; example, plugin can check if flavor or region of VM is suitable.
;; TODO: "cd.go.elastic-agent.should-assign-work"


;; TODO: "cd.go.elastic-agent.job-completion"
