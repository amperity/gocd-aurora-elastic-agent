(ns amperity.gocd.agent.aurora.lifecycle
  "Agent lifecycle logic."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.cluster :as cluster]
    [amperity.gocd.agent.aurora.job :as job]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.server :as server]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.string :as str])
  (:import
    java.time.Instant))


(defn update-cluster-quota
  "Check the available resource quota in the given cluster. Updates the cluster
  state."
  [state cluster-profile]
  ;; TODO: update cluster quota information
  ,,,)


(defn- next-agent-name
  "Determine the next available agent name given the running agents."
  [state cluster-profile agent-tag]
  (loop [agent-num 0]
    (let [agent-name (str agent-tag "-agent-" agent-num)
          agent-id (agent/form-id
                     (:aurora_cluster cluster-profile)
                     (:aurora_role cluster-profile)
                     (:aurora_env cluster-profile)
                     agent-name)]
      (if (contains? (:agents @state) agent-id)
        (recur (inc agent-num))
        agent-name))))


(defn launch-agent!
  "Launch a new agent."
  [state cluster-profile agent-profile gocd-auto-register-key gocd-environment]
  (locking state
    (let [aurora-url (:aurora_url cluster-profile)
          ;; TODO: configurable prefix
          agent-tag "test"
          agent-name (next-agent-name state cluster-profile agent-tag)
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
      ;; TODO: log message
      (aurora/create-agent!
        state
        aurora-url
        cluster-profile
        agent-profile
        agent-name
        agent-task)
      (swap! state assoc-in [:agents agent-id]
             {:environment gocd-environment
              :resources (agent/profile->resources agent-profile)
              :launched-at (Instant/now)
              ;; Add some slack here to give the agent time to start.
              :last-active (.plusSeconds (Instant/now) 300)})
      agent-id)))


(defn prune-agents!
  "Prune Aurora jobs and data about agents which appear to not be registered in
  GoCD."
  [state cluster-profile gocd-agents]
  (let [gocd-agent? (into #{} (map :agent_id) gocd-agents)
        state-agents (:agents @state)
        aurora-url (:aurora_url cluster-profile)
        aurora-cluster (:aurora_cluster cluster-profile)
        aurora-agents (aurora/list-agents
                        state aurora-url
                        (:aurora_role cluster-profile)
                        (:aurora_env cluster-profile))
        horizon (.minusSeconds (Instant/now) 600)]
    (log/debug "Found aurora agents: %s" (pr-str aurora-agents))
    (doseq [job-summary aurora-agents]
      (let [agent-id (agent/form-id (assoc job-summary :aurora-cluster aurora-cluster))
            ^Instant launched-at (get-in state-agents [agent-id :launched-at])]
        (when (and (not (gocd-agent? agent-id))
                   (or (nil? launched-at)
                       (.isBefore launched-at horizon))
                   (or (pos-int? (get-in job-summary [:states :active]))
                       (pos-int? (get-in job-summary [:states :pending]))))
          (let [aurora-agent (aurora/get-agent state aurora-url agent-id)
                ^Instant task-time (last (keep :time (:events aurora-agent)))]
            (when (and (= :running (:status aurora-agent))
                       task-time
                       (.isBefore task-time horizon))
              (log/warn "Killing orphaned agent %s" agent-id)
              (aurora/kill-agent! state aurora-url agent-id)
              (swap! state update :agents dissoc agent-id))))))
    (doseq [agent-info state-agents]
      ;; TODO: remove state information about agents which have no aurora job or gocd job and are stale
      ,,,)))


(defn- terminate-agent!
  "Put an agent through the termination lifecycle."
  [state aurora-url agent-id label]
  (let [app-accessor (:app-accessor @state)
        agent-task (aurora/get-agent state aurora-url agent-id)
        status (:status agent-task :unknown)]
    (if (contains? #{:pending :assigned :starting :running} status)
      ;; Kill agent.
      (do
        (log/info "Killing %s agent %s (%s)" (name status) agent-id label)
        (aurora/kill-agent! state aurora-url agent-id))
      ;; Agent has shut down probably.
      (do
        (log/info "Removing %s agent %s (%s)" (name status) agent-id label)
        (server/delete-agents! app-accessor #{agent-id})
        (swap! state update :agents dissoc agent-id)))))


(defn manage-agent
  "Manage the state of a GoCD agent."
  [state cluster-profiles gocd-agent]
  (let [app-accessor (:app-accessor @state)
        agent-id (:agent_id gocd-agent)
        agent-state (:agent_state gocd-agent)
        build-state (:build_state gocd-agent)
        config-state (:config_state gocd-agent)
        enabled? (= "Enabled" config-state)
        idle? (= "Idle" agent-state)
        aurora-cluster (:aurora-cluster (agent/parse-id agent-id))
        cluster-profile (first (filter #(= aurora-cluster (:aurora_cluster %))
                                       cluster-profiles))
        aurora-url (:aurora_url cluster-profile)
        ^Instant last-active (get-in @state [:agents agent-id :last-active])
        ;; TODO: make this configurable?
        ttl-seconds 180]
    (cond
      ;; Agent is in a bad state, try to clean it up.
      (contains? #{"Missing" "LostContact"} agent-state)
      (do
        (when enabled?
          (log/info "Disabling agent %s (%s)" agent-id agent-state)
          (server/disable-agents! app-accessor #{agent-id}))
        (terminate-agent! state aurora-url agent-id agent-state))

      ;; Healthy agent.
      enabled?
      (if (and idle? last-active)
        (if (.isAfter (Instant/now) (.plusSeconds last-active ttl-seconds))
          (do
            (log/info "Retiring idle agent %s" agent-id)
            (server/disable-agents! app-accessor #{agent-id}))
          (log/info "Agent %s is healthy" agent-id))
        ;; Update last-active timestamp.
        (do
          (log/info "Updating last-active timestamp for agent %s" agent-id)
          (swap! state assoc-in [:agents agent-id :last-active] (Instant/now))))

      ;; Disabled agent is still busy, wait for it to drain.
      (not idle?)
      (log/info "Waiting for disabled agent %s to drain" agent-id)

      ;; Agent is disabled and quiescent, see if it it has been terminated.
      :else
      (terminate-agent! state aurora-url agent-id "retired"))))
