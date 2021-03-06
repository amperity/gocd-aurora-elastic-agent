(ns amperity.gocd.agent.aurora.plugin
  "Core plugin implementation."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.cluster :as cluster]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.scheduler :as scheduler]
    [amperity.gocd.agent.aurora.server :as server]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hiccup.core :as hiccup])
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


;; ## Scheduler Initialization

(defn initialize!
  "Initialize the plugin scheduler state, returning a configured agent."
  [logger app-accessor]
  (alter-var-root #'log/logger (constantly logger))
  (let [server-info (server/get-server-info app-accessor)]
    ;; TODO: not used for anything anymore
    (log/debug "Got server-info: %s" (pr-str server-info))
    (agent
      {:app-accessor app-accessor
       :clients {}
       :clusters {}
       :agents {}}
      :error-mode :continue
      :error-handler #(log/errorx %2 "Error handling scheduler agent event"))))



;; ## Request Handling

(defmulti handle-request
  "Handle a plugin API request and respond. Methods should return `true` for an
  empty success response, a data structure to coerce into a successful JSON
  response, or a custom `GoPluginApiResponse`."
  (fn dispatch
    [<scheduler> req-name data]
    req-name))


(defmethod handle-request :default
  [_ req-name _]
  (throw (UnhandledRequestTypeException. req-name)))


(defn handler
  "Request handling entry-point."
  [<scheduler> ^GoPluginApiRequest request]
  (try
    (let [req-name (.requestName request)
          req-data (when-not (str/blank? (.requestBody request))
                     (u/json-decode-map (.requestBody request)))
          result (handle-request <scheduler> req-name req-data)]
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
   :supports_cluster_status_report true
   :supports_agent_status_report true})


(defn- migrate-profile
  "Generically migrate a profile by calling `f` on its properties. Logs when
  profile settings change."
  [f profile]
  (let [old-props (:properties profile)
        new-props (f old-props)]
    (when (not= old-props new-props)
      (log/info "Migrated profile %s: %s => %s"
                (:id profile)
                (pr-str old-props)
                (pr-str new-props)))
    (assoc profile :properties new-props)))


;; This message is a request to the plugin perform the migration on the
;; existing config on load of the plugin. This allows a plugin to perform
;; the migration on the existing config in order to support the newer
;; version of the plugin.
(defmethod handle-request "cd.go.elastic-agent.migrate-config"
  [_ _ data]
  (let [cluster-profiles (:cluster_profiles data)
        agent-profiles (:elastic_agent_profiles data)]
    {:cluster_profiles
     (mapv (partial migrate-profile cluster/migrate-settings)
           cluster-profiles)
     :elastic_agent_profiles
     (mapv (partial migrate-profile agent/migrate-settings)
           agent-profiles)}))



;; ## Status Reports

(defn- view-response
  "Construct a view response for a status report."
  [data]
  {:view (hiccup/html data)})


;; If plugin supports status report, this message must be implemented to report
;; the status of a particular elastic agent brought up by the plugin to run a
;; job. The purpose of this call is to provide specific information about the
;; current state of the elastic agent.
(defmethod handle-request "cd.go.elastic-agent.agent-status-report"
  [<scheduler> _ data]
  (log/info "agent-status-report %s" (pr-str data))
  (let [agent-id (:elastic_agent_id data)
        cluster-profile (:cluster_profile_properties data)
        job-info (:job_identifier data)]
    ;; TODO: implement agent status report
    (view-response
      [:div
       [:h2 "Agent Status"]
       [:pre
        (let [agent-state (get-in @<scheduler> [:agents agent-id])]
          (pr-str agent-state))]])))


;; If plugin supports cluster status report, this message must be implemented
;; to provide the overall status of the cluster.
(defmethod handle-request "cd.go.elastic-agent.cluster-status-report"
  [<scheduler> _ data]
  (let [cluster-profile (:cluster_profile_properties data)
        aurora-cluster (:aurora_cluster cluster-profile)
        aurora-role (:aurora_role cluster-profile)
        aurora-env (:aurora_env cluster-profile)]
    (view-response
      [:div
       [:style "ol,ul,li { float: none; }"]
       [:h2 "Cluster Status"]
       [:h3 "Profile"]
       [:pre (pr-str cluster-profile)]
       (when-let [cluster-state (get-in @<scheduler> [:clusters aurora-cluster])]
         [:div
          [:h3 "State"]
          [:pre (pr-str cluster-state)]])
       [:h3 "Agents"]
       (when-let [aurora-url (:aurora_url cluster-profile)]
         [:p [:a {:href (str/replace aurora-url #"/api$" (str "/scheduler/" aurora-role "/" aurora-env))}
              (format "Aurora %s/%s/%s"
                      aurora-cluster
                      aurora-role
                      aurora-env)]])
       (->>
         (:agents @<scheduler>)
         (filter (comp #{aurora-cluster} :aurora-cluster agent/parse-id key))
         (sort-by :state)
         (map (fn render-agent
                [[agent-id agent-state]]
                [:div
                 [:h4 agent-id]
                 [:pre (pr-str agent-state)]
                 [:ul
                  [:li [:strong "state:"] " " (name (:state agent-state :???))]
                  [:li [:strong "last-active:"] " "
                   (str (:last-active agent-state))
                   (when (:idle? agent-state)
                     " (idle)")]
                  [:li [:strong "environments:"] " " (:environments agent-state :???)]
                  [:li [:strong "resources:"]
                   [:ul
                    (for [[k v] (:resources agent-state)]
                      [:li [:strong (name k) ":"] " " (str v)])]]]
                 [:strong "Event History"]
                 [:ul
                  (for [event (:events agent-state)]
                    [:li (str (:time event)) " " [:strong (name (:state event))] " " (:message event)])]])))])))


;; If plugin supports the plugin status report, this message must be
;; implemented to provide the overall status of the environment.
(defmethod handle-request "cd.go.elastic-agent.plugin-status-report"
  [_ _ data]
  (let [cluster-profiles (:all_cluster_profile_properties data)]
    ;; TODO: implement plugin status report
    (view-response
      [:span [:strong "NYI:"] " plugin status"])))



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
  [_ _ data]
  (cluster/validate-settings data))


;; This is called when a cluster profile is changed.
(defmethod handle-request "cd.go.elastic-agent.cluster-profile-changed"
  [_ _ data]
  (log/info "cluster-profile-changed %s" (pr-str data))
  ;; TODO: determine what to do here
  true)



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
  [_ _ data]
  (agent/validate-settings data))


;; This is called when an agent profile is changed.
(defmethod handle-request "cd.go.elastic-agent.elastic-agent-profile-changed"
  [_ _ data]
  (log/info "elastic-agent-profile-changed %s" (pr-str data))
  ;; TODO: determine what to do here
  true)



;; ## Agent Lifecycle

;; Each elastic agent plugin will receive a periodic signal at regular
;; intervals for it to perform any cleanup operations. Plugins may use this
;; message to disable and/or terminate agents at their discretion. Calls occur
;; approximately once per minute.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.server-ping"
  [<scheduler> _ data]
  (log/debug "server-ping: %s" (pr-str data))
  (let [cluster-profiles (:all_cluster_profile_properties data)
        app-accessor (:app-accessor @<scheduler>)
        gocd-agents (server/list-agents app-accessor)]
    (send <scheduler> scheduler/manage-clusters cluster-profiles gocd-agents)
    true))


;; This message is a request to the plugin to create an agent for a job
;; that has been scheduled.
;; NOTE: calls occur on multiple threads
(defmethod handle-request "cd.go.elastic-agent.create-agent"
  [<scheduler> _ data]
  (log/debug "create-agent: %s" (pr-str data))
  (send <scheduler>
        scheduler/request-new-agent
        {:cluster-profile (:cluster_profile_properties data)
         :agent-profile (:elastic_agent_profile_properties data)
         :gocd-environment (:environment data)
         :gocd-register-key (:auto_register_key data)
         :gocd-job (:job_identifier data)})
  true)


;; When there are multiple agents available to run a job, the server will
;; ask the plugin if jobs should be assigned to a particular agent. The
;; request will contain information about the agent, the job configuration
;; and the environments that the agent belongs to. This allows plugin to
;; decide if proposed agent is suitable to schedule a job on it. For
;; example, plugin can check if flavor or region of VM is suitable.
(defmethod handle-request "cd.go.elastic-agent.should-assign-work"
  [<scheduler> _ data]
  (log/debug "should-assign-work: %s" (pr-str data))
  (let [cluster-profile (:cluster_profile_properties data)
        agent-profile (:elastic_agent_profile_properties data)
        agent-id (get-in data [:agent :agent_id])
        gocd-job (:job_identifier data)
        decision (scheduler/should-assign-work?
                   @<scheduler>
                   agent-profile
                   agent-id
                   cluster-profile)]
    (when decision
      (log/info "Assigning job %s to agent %s"
                (server/job-label gocd-job)
                agent-id)
      (send <scheduler> scheduler/update-agent agent-id
            (comp agent/mark-active scheduler/mark-ready)))
    (DefaultGoPluginApiResponse/success (str (boolean decision)))))


;; The intent on this message is to notify the plugin on completion of the job.
;; The plugin may choose to terminate the elastic agent or keep it running in
;; case the same agent can be used for another job configuration.
(defmethod handle-request "cd.go.elastic-agent.job-completion"
  [<scheduler> _ data]
  (log/debug "job-completion: %s" (pr-str data))
  (let [agent-id (:elastic_agent_id data)]
    (send <scheduler> scheduler/update-agent agent-id agent/mark-idle)
    true))
