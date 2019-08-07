(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:gen-class
    :name ^{com.thoughtworks.go.plugin.api.annotation.Extension []}
          amperity.gocd.agent.aurora.AuroraElasticAgentPlugin
    :state state
    :init init
    :main false
    :implements [com.thoughtworks.go.plugin.api.GoPlugin])
  (:require
    [clojure.java.io :as io])
  (:import
    com.google.gson.Gson
    (com.thoughtworks.go.plugin.api
      GoApplicationAccessor
      GoPluginIdentifier)
    (com.thoughtworks.go.plugin.api.exceptions
      UnhandledRequestTypeException)
    (com.thoughtworks.go.plugin.api.request
      GoPluginApiRequest)
    (com.thoughtworks.go.plugin.api.response
      DefaultGoPluginApiResponse)
    java.util.Base64))


;; ## Utilities

(let [gson (Gson.)]
  (defn- json-encode
    ^String
    [value]
    (.toJson gson value))

  (defn- json-decode
    [^String json]
    (into {} (.fromJson gson json java.util.Map))))



;; ## Plugin Methods

(defn -init
  []
  ;; superclass constructor args and initial state
  [[] (atom {})])


(defn -initializeGoApplicationAccessor
  "This method is executed once at startup to inject the accessor."
  [this ^GoApplicationAccessor app-accessor]
  (swap! (.state this) assoc :app-accessor app-accessor))


(defn -pluginIdentifier
  "The `GoPluginIdentifier` tells GoCD what kind of plugin this is and what
  version(s) of the request/response API it supports."
  [this]
  (GoPluginIdentifier. "elastic-agent" ["5.0"]))


(defn -handle
  "Handle the request and return a response. The response is very much like a
  HTTP response — it has a status code, a response body and optional headers."
  [this ^GoPluginApiRequest request]
  (case (.requestName request)
    ;; This call is expected to return the icon for the plugin, so as to make
    ;; it easy for users to identify the plugin.
    "cd.go.elastic-agent.get-icon"
    (let [icon-svg (slurp (io/resource "amperity/gocd/agent/aurora/logo.svg"))]
      (->
        {"content_type" "image/svg+xml"
         "data" (.encodeToString (Base64/getEncoder) (.getBytes icon-svg))}
        (json-encode)
        (DefaultGoPluginApiResponse/success)))

    ;; This message is a request to the plugin to provide plugin capabilities.
    ;; Based on these capabilities GoCD would enable or disable the plugin
    ;; features for a user.
    "cd.go.elastic-agent.get-capabilities"
    (->
      {"supports_plugin_status_report" false
       "supports_cluster_status_report" false
       "supports_agent_status_report" false}
      (json-encode)
      (DefaultGoPluginApiResponse/success))

    ;; This message is a request to the plugin perform the migration on the
    ;; existing config on load of the plugin. This allows a plugin to perform
    ;; the migration on the existing config in order to support the newer
    ;; version of the plugin.
    "cd.go.elastic-agent.migrate-config"
    (let [body (json-decode (doto (.requestBody request) prn))  ; DEBUG
          cluster-profiles (get body "cluster_profiles")
          agent-profiles (get body "elastic_agent_profiles")]
      (->
        {"cluster_profiles" (vec cluster-profiles)
         "elastic_agent_profiles" (vec agent-profiles)}
        (json-encode)
        (DefaultGoPluginApiResponse/success)))

    ;; This message is a request to the plugin to create an agent for a job
    ;; that has been scheduled.
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
    "cd.go.elastic-agent.create-agent"
    (do
      ;; TODO: Determine whether to launch an agent type on the cluster.
      (DefaultGoPluginApiResponse/error "NYI"))

    ;; When there are multiple agents available to run a job, the server will
    ;; ask the plugin if jobs should be assigned to a particular agent. The
    ;; request will contain information about the agent, the job configuration
    ;; and the environment that the agent belongs to. This allows plugin to
    ;; decide if proposed agent is suitable to schedule a job on it. For
    ;; example, plugin can check if flavor or region of VM is suitable.
    ;; TODO: "cd.go.elastic-agent.should-assign-work"

    ;; Each elastic agent plugin will receive a periodic signal at regular
    ;; intervals for it to perform any cleanup operations. Plugins may use this
    ;; message to disable and/or terminate agents at their discretion.
    ;; TODO: "cd.go.elastic-agent.server-ping"

    ;; This is a message that the plugin should implement, to allow users to
    ;; configure cluster profiles from the Elastic Profiles View in GoCD.
    "cd.go.elastic-agent.get-cluster-profile-view"
    (let [view-html (slurp (io/resource "amperity/gocd/agent/aurora/cluster-profile-view.html"))]
      (->
        {"template" view-html}
        (json-encode)
        (DefaultGoPluginApiResponse/success)))

    ;; This is a message that the plugin should implement, to allow users to
    ;; configure cluster profiles from the Elastic Profiles View in GoCD.
    "cd.go.elastic-agent.get-cluster-profile-metadata"
    (->
      [{"key" "aurora_url"
        "metadata" {"required" true, "secure" false}}
       {"key" "aurora_cluster"
        "metadata" {"required" true, "secure" false}}
       {"key" "aurora_role"
        "metadata" {"required" true, "secure" false}}
       {"key" "aurora_env"
        "metadata" {"required" true, "secure" false}}]
      (json-encode)
      (DefaultGoPluginApiResponse/success))

    ;; This call is expected to validate the user inputs that form a part of
    ;; the cluster profile.
    "cd.go.elastic-agent.validate-cluster-profile"
    (let [settings (json-decode (doto (.requestBody request) prn))] ; DEBUG
      (->
        ;; TODO: implement validation
        ;; {"key": "foo", "message": "..."}
        []
        (json-encode)
        (DefaultGoPluginApiResponse/success)))

    ;; TODO: remaining APIs:
    ;; - cd.go.elastic-agent.validate-elastic-agent-profile
    ;; - cd.go.elastic-agent.get-elastic-agent-profile-view
    ;; - cd.go.elastic-agent.get-elastic-agent-profile-metadata
    ;; - cd.go.elastic-agent.job-completion
    ;; - cd.go.elastic-agent.agent-status-report
    ;; - cd.go.elastic-agent.cluster-status-report
    ;; - cd.go.elastic-agent.plugin-status-report

    ;; Unknown request type.
    (throw (UnhandledRequestTypeException. (.requestName request)))))
