(ns amperity.gocd.agent.aurora.scheduler
  "Agent scheduling and lifecycle logic."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.cluster :as cluster]
    [amperity.gocd.agent.aurora.job :as job]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.server :as server]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.string :as str]))


(comment
  ;; Scheduler state structure.
  {:clients
   {"http://..."
    {:client AuroraSchedulerManager$Client
     :transport THttpClient}}

   :clusters
   {"aws-dev"
    {:url "http://..."
     :quota {,,,}}}

   :agents
   {"aws-dev/www-data/prod/test-agent-0" agent-state}})


(defn- self-dispatcher
  "Construct a function which will dispatch the provided function to the
  current agent, along with any provided arguments. Throws an exception if
  called outside of an evaluating agent context."
  [f & args]
  (if-let [self *agent*]
    (fn send-it
      [& more]
      (apply send self f (concat args more)))
    (throw (IllegalStateException.
             "Cannot construct dispatch function outside of an agent thread."))))


(defn- log-state-change
  "Log state changes to an agent."
  [agent-id old-agent new-agent]
  (let [old-state (:state old-agent)
        new-state (:state new-agent)
        last-event (last (:events new-agent))]
    (when (not= old-state new-state)
      (log/info "Agent %s (%s -> %s) %s"
                agent-id
                (name (or old-state "--"))
                (name (or new-state "--"))
                (:message last-event "--")))))


(defn update-agent
  "Update the agent with the given id in the scheduler by applying `f` to it
  and `args`. Returns an updated scheduler map."
  [scheduler agent-id f & args]
  (let [old-agent (get-in scheduler [:agents agent-id])
        new-agent (apply f old-agent args)]
    (log-state-change agent-id old-agent new-agent)
    (assoc-in scheduler [:agents agent-id] new-agent)))



;; ## Aurora Cluster Clients

;; TODO: work on these, probably move some to cluster ns

(defn- init-client
  "Initialize an Aurora client for the given cluster."
  [scheduler cluster-profile]
  (let [aurora-url (:aurora_url cluster-profile)
        aurora-cluster (:aurora_cluster cluster-profile)]
    (if (and aurora-url aurora-cluster)
      (-> scheduler
          (update-in [:clients aurora-url] aurora/ensure-client aurora-url)
          (assoc-in [:clusters aurora-cluster :url] aurora-url))
      scheduler)))


(defn- init-cluster-clients
  "Initialize an Aurora client for each of the given clusters."
  [scheduler cluster-profiles]
  (reduce init-client scheduler cluster-profiles))


(defn- get-cluster-client
  "Fetch an initialized Aurora client for the given profile."
  [scheduler cluster-profile]
  (let [url (or (:aurora_url cluster-profile)
                (get-in scheduler [:clusters (:aurora_cluster cluster-profile) :url]))]
    (get-in scheduler [:clients url])))



;; ## Job Predicates

(defn- aurora-pending?
  "True if the aurora job summary indicates that there are pending tasks."
  [aurora-job]
  (pos-int? (get-in aurora-job [:states :pending])))


(defn- aurora-active?
  "True if the aurora job summary indicates that there are active tasks."
  [aurora-job]
  (pos-int? (get-in aurora-job [:states :active])))


(defn- aurora-alive?
  "True if the aurora job summary indicates that there are pending or active
  tasks."
  [aurora-job]
  (or (aurora-pending? aurora-job) (aurora-active? aurora-job)))


(defn- gocd-registered?
  "True if the agent is present in GoCD."
  [gocd-agent]
  (and (= "Enabled" (:config_state gocd-agent))
       (not (contains? #{"Missing" "LostContact"} (:agent_state gocd-agent)))))



;; ## Job Assignment

(defn should-assign-work?
  "True if the scheduler should assign the given job which wants
  `agent-profile` to the agent identified."
  [scheduler agent-profile agent-id]
  (if-let [resources (get-in scheduler [:agents agent-id :resources])]
    ;; Determine if job requirements are satisfied by the agent.
    (agent/resource-satisfied?
      (agent/profile->resources agent-profile)
      resources)
    ;; No resources recorded for this agent, don't assign work to it.
    false))


(defn mark-ready
  "Mark the agent as running if it was in a boot state. Often the agent will
  ask for work before the ping timer sees it has registered, so this keeps it
  more up-to-date."
  [agent-state]
  (if (contains? #{:launching :pending :starting} (:state agent-state))
    (agent/update-state agent-state :running "Agent registered with GoCD server")
    agent-state))



;; ## Agent Launching

;; The code here covers the initial request to create a new agent. The
;; scheduler first decides whether it _should_ launch an agent to handle the
;; job; if so, it will find an unused agent name and initialize it.

(defn- should-create-agent?
  "True if the scheduler should create a new agent to satisfy the job."
  [scheduler request]
  (let [{:keys [cluster-profile agent-profile gocd-environment]} request
        aurora-cluster (:aurora_cluster cluster-profile)
        cluster-quota (get-in scheduler [:clusters aurora-cluster :quota])
        job-resources (agent/profile->resources agent-profile)
        promised (->> (vals (:agents scheduler))
                      (filter #(= (get-in request [:gocd-job :job_id])
                                  (:launched-for %)))
                      (filter (comp #{:launching :pending :starting} :state))
                      (remove #(agent/stale? % 600))
                      (first))
        ;; TODO: this logic results in slower ramp time than desired when lots
        ;; of jobs want new agents, but there's a single free agent that can
        ;; take those jobs. The existing capacity prevents new agents from
        ;; launching, even though it's not enough to satisfy the spike in
        ;; demand.
        candidates (into #{}
                         (keep
                           (fn candidate?
                             [[agent-id agent-state]]
                             (when (and (= :running (:state agent-state))
                                        (= gocd-environment (:environment agent-state))
                                        (:idle? agent-state)
                                        (agent/resource-satisfied? job-resources (:resources agent-state)))
                               agent-id)))
                         (:agents scheduler))]
    (cond
      ;; If there's an agent already promised to this job but not fully running
      ;; yet, wait for it to start.
      promised
      (log/info "Not launching new agent because %s is still launching for job %s"
                (:agent-id promised)
                (:launched-for promised))

      ;; Some agents are already available to handle the work.
      (seq candidates)
      (log/info "Not launching new agent because %d candidates are available in environment %s: %s"
                (count candidates)
                gocd-environment
                (str/join " " candidates))

      ;; Check that the cluster has available quota.
      (not (cluster/quota-available? cluster-quota job-resources))
      (log/info "Not launching new agent because cluster %s is at capacity (%s)"
                (:aurora_cluster cluster-profile)
                (pr-str cluster-quota))

      :else true)))


(defn- next-agent-id
  "Determine the next available agent name given the running agents."
  [scheduler cluster-profile agent-tag]
  (loop [agent-num 0]
    (let [agent-name (str agent-tag "-agent-" agent-num)
          agent-id (agent/form-id
                     (:aurora_cluster cluster-profile)
                     (:aurora_role cluster-profile)
                     (:aurora_env cluster-profile)
                     agent-name)]
      (if (contains? (:agents scheduler) agent-id)
        (recur (inc agent-num))
        agent-id))))


(defn- launch-agent!
  "Start a future thread to launch a new agent in Aurora."
  [scheduler agent-id request]
  (let [dispatch-update (self-dispatcher update-agent agent-id agent/update-state)]
    (future
      (try
        (let [cluster-profile (:cluster-profile request)
              agent-profile (:agent-profile request)
              aurora-client (get-cluster-client scheduler cluster-profile)
              source-url (if (str/blank? (:agent_source_url cluster-profile))
                           cluster/default-agent-source-url
                           (:agent_source_url cluster-profile))
              agent-name (:agent-name (agent/parse-id agent-id))
              agent-task (job/agent-task
                           agent-name
                           {:server-url (:server_api_url cluster-profile)
                            :agent-source-url source-url
                            :auto-register-hostname agent-name
                            :auto-register-environment (:gocd-environment request)
                            :auto-register-key (:gocd-register-key request)
                            :elastic-plugin-id u/plugin-id
                            :elastic-agent-id agent-id})]
          (aurora/create-agent!
            aurora-client
            cluster-profile
            agent-profile
            agent-name
            agent-task))
        ;; On success, set state to pending.
        (dispatch-update :pending "Agent job created in Aurora")
        (catch Exception ex
          ;; On failure, set state to failed.
          (log/errorx ex "Failed to create agent job in Aurora")
          (dispatch-update
            :failed
            (str "Failed to create agent job in Aurora: "
                 (.getSimpleName (class ex)) " "
                 (.getMessage ex))))))))


(defn request-new-agent
  "Request a new agent be created to satisfy a job. May or may not result in a
  new agent being launched."
  [scheduler request]
  (let [cluster-profile (:cluster-profile request)
        agent-profile (:agent-profile request)
        gocd-environment (:gocd-environment request)
        agent-tag (:agent_tag agent-profile)]
    (if (should-create-agent? scheduler request)
      ;; Find next available name and launch.
      (let [agent-id (next-agent-id scheduler cluster-profile agent-tag)
            gocd-job-id (get-in request [:gocd-job :job_id])
            agent-state (assoc (agent/init-state agent-id :launching agent-profile gocd-environment)
                               :launched-for gocd-job-id)]
        (launch-agent! scheduler agent-id request)
        (assoc-in scheduler [:agents agent-id] agent-state))
      ;; Don't launch new agent.
      scheduler)))



;; ## Agent Scheduling

;; The scheduling logic here is based around the idea of 'agent event
;; functions'. Each of these functions is applied to the state of a specific
;; agent in the scheduler, and should return a map with the following optional
;; keys:
;;
;; - `:agent` a new state map for the agent
;;     - if not present, the agent's current state is preserved
;;     - if value is nil, the agent's state is removed from the scheduler
;;     - otherwise, the value is used as the new agent state
;; - `:effect` a map specifying a side-effect which should happen
;;   asynchronously in another thread.
;;     - `:type` the type of effect to cause
;;     - `:on-success` an event vector to dispatch if the effect succeeds
;;     - `:on-failure` an event vector to dispatch if the effect fails
;;     - `*` may contain other keys based on the effect type
;;
;; This could be simplified, but separating the effects out like this means
;; that the individual state management logic doesn't need to reference the
;; scheduler. This lets the transitions remain pure functions, which are easier
;; to reason about and more amenable to testing.


(defn- enact-raw-effect!
  "Enact a side-effect without any error guards or callbacks. Do not call this
  directly."
  [scheduler effect]
  (let [agent-id (:agent-id effect)]
    (case (:type effect)
      :kill-aurora-agent
      ;; NOTE: this code assumes that cluster names map uniquely to profiles.
      (let [aurora-cluster (:aurora-cluster (agent/parse-id agent-id))
            aurora-url (get-in scheduler [:clusters aurora-cluster :url])
            aurora-client (get-in scheduler [:clients aurora-url])]
        (aurora/kill-agent! aurora-client agent-id))

      :disable-gocd-agent
      (let [app-accessor (:app-accessor scheduler)]
        (server/disable-agents! app-accessor #{agent-id}))

      :delete-gocd-agent
      (let [app-accessor (:app-accessor scheduler)]
        (server/delete-agents! app-accessor #{agent-id})))))


(defn- enact-effect!
  "Enact a side-effect caused by an agent event in a separate thread. Returns
  the deferred future which will return nil."
  [scheduler dispatch-update effect]
  (future
    (try
      (enact-raw-effect! scheduler effect)
      (when-let [[state message] (:on-success effect)]
        (dispatch-update state message))
      (catch Exception ex
        (log/errorx ex "Failed to enact side-effect %s on %s"
                    (:type effect)
                    (:agent-id effect))
        (when-let [[state message] (:on-failure effect)]
          (dispatch-update state message))))
    nil))


(defn- handle-agent-event
  "Update the agent in the scheduler by applying the event function `f` to its
  state and the provided arguments. The event function should return a result
  map optionally containing an updated `:agent` state and an async `:effect` to
  cause."
  [scheduler agent-id f & args]
  (let [dispatch-update (self-dispatcher update-agent agent-id agent/update-state)
        agent-state (get-in scheduler [:agents agent-id])
        result (apply f agent-state args)
        next-state (:agent result)]
    ;; Invoke side effects.
    (when-let [effect (:effect result)]
      (enact-effect! scheduler dispatch-update (assoc effect :agent-id agent-id)))
    ;; Update agent state if set.
    (cond
      (not (contains? result :agent))
      scheduler

      (map? next-state)
      (do
        (log-state-change agent-id agent-state next-state)
        (assoc-in scheduler [:agents agent-id] next-state))

      (nil? next-state)
      (update scheduler :agents dissoc agent-id)

      :else
      (do
        (log/warn "Agent event function returned unknown result type: %s"
                  (pr-str next-state))
        scheduler))))


(defn- update-state-fx
  "Event response which updates the agent's state."
  [agent-state state message]
  {:agent (agent/update-state agent-state state message)})


(defn- drain-agent-fx
  "Update the agent's state immediately and disable the GoCD agent. Moves the
  agent to draining once complete."
  [agent-state state message]
  {:agent (agent/update-state agent-state state message)
   :effect {:type :disable-gocd-agent
            :on-success [:draining "Agent disabled in GoCD"]}})


(defn- kill-agent-fx
  "Update the agent's state immediately and kill the Aurora job. Moves the
  agent to killed once complete."
  [agent-state state message]
  {:agent (agent/update-state agent-state state message)
   :effect {:type :kill-aurora-agent
            :on-success [:killed "Aurora job killed"]}})


(defn- terminate-agent-fx
  "Update the agent's state immediately and remove it from the GoCD server.
  Moves the agent to terminated once complete."
  [agent-state state message]
  {:agent (agent/update-state agent-state state message)
   :effect {:type :delete-gocd-agent
            :on-success [:terminated "Agent terminated"]}})



;; ## State Transitions

(defmulti manage-agent-state
  "Manage an agent's state by synchronizing with the available information from
  the Aurora job and the GoCD server."
  (fn dispatch
    [agent-state aurora-job gocd-agent]
    (:state agent-state))
  :default :unknown)


;; Agent is not tracked in the scheduler state.
(defmethod manage-agent-state nil
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If an untracked agent is registered in gocd, this is a legacy agent.
    gocd-agent
    (drain-agent-fx
      agent-state :legacy
      "Detected legacy agent in GoCD server")

    ;; If an untracked job is active in Aurora, this is an orphaned agent.
    (aurora-alive? aurora-job)
    (kill-agent-fx
      agent-state :orphan
      "Detected orphaned agent job in Aurora")

    ;; Otherwise, this is a dead aurora job, so ignore.
    :else nil))


;; Agent job is being created in Aurora.
(defmethod manage-agent-state :launching
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If active job in aurora, move to starting.
    (aurora-active? aurora-job)
    (update-state-fx
      agent-state :starting
      "Aurora job is active, waiting for agent to register")

    ;; If pending job in aurora, move to pending.
    (aurora-pending? aurora-job)
    (update-state-fx
      agent-state :pending
      "Aurora job is pending")

    ;; After a long timeout, assume error and move to failed.
    (agent/stale? agent-state 600)
    (update-state-fx
      agent-state :failed
      "Agent is stale: no activity for 10 minutes")))


;; Job created in Aurora, waiting for process to start.
(defmethod manage-agent-state :pending
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If active job in aurora, move to starting.
    (aurora-active? aurora-job)
    (update-state-fx
      agent-state :starting
      "Aurora job is active, waiting for agent to register")

    ;; If registered in gocd, move to running.
    (gocd-registered? gocd-agent)
    (update-state-fx
      agent-state :running
      "Agent registered with GoCD server")

    ;; After a long timeout, assume stale and kill the agent.
    (agent/stale? agent-state 600)
    (kill-agent-fx
      agent-state :killing
      "Agent is stale: no activity for 10 minutes")))


;; Job is active in Aurora, waiting for registration with the GoCD server.
(defmethod manage-agent-state :starting
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If registered in gocd, move to running.
    (gocd-registered? gocd-agent)
    (update-state-fx
      agent-state :running
      "Agent registered with GoCD server")

    ;; After a long timeout, assume stale and kill the agent.
    (agent/stale? agent-state 600)
    (kill-agent-fx
      agent-state :killing
      "Agent is stale: no activity for 10 minutes")))


;; Primary healthy state.
(defmethod manage-agent-state :running
  [agent-state aurora-job gocd-agent]
  (let [gocd-state (:agent_state gocd-agent)]
    (cond
      ;; If some third party disabled the agent in GoCD, move to draining.
      (= "Disabled" gocd-state)
      (update-state-fx
        agent-state :draining
        "GoCD agent externally disabled")

      ;; If missing or lost-contact, kill/move to killing.
      (contains? #{"Missing" "LostContact"} gocd-state)
      (kill-agent-fx
        agent-state :killing
        (str "GoCD server thinks agent is " gocd-state))

      ;; After a period of idleness, disable and move to retiring.
      (and (= "Idle" gocd-state)
           (agent/idle? agent-state 300))
      (drain-agent-fx
        agent-state :retiring
        "Retiring idle agent")

      ;; Agent is idle, so mark it as not busy.
      (= "Idle" gocd-state)
      {:agent (agent/mark-idle agent-state)}

      ;; Agent is not idle, update its last active time.
      :else
      {:agent (agent/mark-active agent-state)})))


;; Agent has been idle for a while and is being retired from service.
(defmethod manage-agent-state :retiring
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If disabled in gocd, move to draining.
    (= "Disabled" (:config_state gocd-agent))
    (update-state-fx
      agent-state :draining
      "Agent disabled in GoCD")

    ;; After a long timeout, retry disable call.
    (agent/stale? agent-state 120)
    (drain-agent-fx
      agent-state :retiring
      "Retrying agent retirement")))


;; Agent is disabled in GoCD, wait to make sure it finishes any running jobs.
(defmethod manage-agent-state :draining
  [agent-state aurora-job gocd-agent]
  (when (contains? #{"Idle" "Missing" "LostContact"} (:agent_state gocd-agent))
    ;; Agent is no longer busy.
    (kill-agent-fx
      agent-state :killing
      (str "GoCD agent is " (:agent_state gocd-agent)))))


;; The agent job is being killed in Aurora if it is active.
(defmethod manage-agent-state :killing
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If no longer active or pending in aurora, move to killed.
    (not (aurora-alive? aurora-job))
    (update-state-fx
      agent-state :killed
      "Aurora job killed")

    ;; After a timeout, retry killing the job.
    (agent/stale? agent-state 120)
    (kill-agent-fx
      agent-state :killing
      "Retrying kill of Aurora job")))


;; The Aurora job has been killed.
(defmethod manage-agent-state :killed
  [agent-state aurora-job gocd-agent]
  (when-not (aurora-alive? aurora-job)
    ;; If no longer active in aurora, remove the agent.
    (terminate-agent-fx
      agent-state :removing
      "Removing GoCD agent")))


;; The agent is being unregistered from the GoCD server.
(defmethod manage-agent-state :removing
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If no longer registered with gocd, move to terminated.
    (not (gocd-registered? gocd-agent))
    (update-state-fx
      agent-state :terminated
      "Agent terminated")

    ;; After a timeout, retry removing the agent.
    (agent/stale? agent-state 120)
    (terminate-agent-fx
      agent-state :removing
      "Retrying removal of GoCD agent")))


;; Agent is registered in GoCD but has no scheduler state.
(defmethod manage-agent-state :legacy
  [agent-state aurora-job gocd-agent]
  (when (agent/stale? agent-state 60)
    ;; After a timeout, retry disabling the agent.
    (drain-agent-fx
      agent-state :legacy
      "Retrying disable of legacy agent")))


;; Agent job is active in Aurora but has no scheduler state.
(defmethod manage-agent-state :orphan
  [agent-state aurora-job gocd-agent]
  (when (agent/stale? agent-state 60)
    ;; After a timeout, retry killing the agent.
    (kill-agent-fx
      agent-state :orphan
      "Retrying kill of orphaned agent")))


;; Agent is in a terminal state, keep state for a bit for introspection.
(defmethod manage-agent-state :failed
  [agent-state aurora-job gocd-agent]
  (when (agent/stale? agent-state 600)
    {:agent nil}))


;; Agent is in a terminal state, keep state for a bit for introspection.
(defmethod manage-agent-state :terminated
  [agent-state aurora-job gocd-agent]
  (when (agent/stale? agent-state 300)
    {:agent nil}))


;; Unknown state, but no running job or registered agent.
(defmethod manage-agent-state :unknown
  [agent-state aurora-job gocd-agent]
  (update-state-fx
    agent-state :failed
    (str "Aborting after encountering unknown agent state "
         (pr-str (:state agent-state)))))



;; ## Cluster Management

(defn- set-cluster-quota
  [scheduler aurora-cluster quota]
  (assoc-in scheduler
            [:clusters aurora-cluster :quota]
            (select-keys quota [:available :usage])))


(defn- check-cluster-quota*
  "Check the available resource quota in the given cluster. Updates the cluster
  state."
  [scheduler cluster-profile]
  (let [aurora-cluster (:aurora_cluster cluster-profile)
        aurora-role (:aurora_role cluster-profile)
        dispatch-update (self-dispatcher set-cluster-quota aurora-cluster)]
    (future
      (try
        (let [client (get-cluster-client scheduler cluster-profile)
              quota (aurora/get-quota client aurora-role)]
          (dispatch-update quota))
        (catch Exception ex
          (log/errorx ex "Failed to check quota usage for cluster %s"
                      aurora-cluster))))))


(defn- list-aurora-agents*
  "List the Aurora agent jobs in the given cluster on a new thread. Returns a
  deferred which yields a collection of aurora jobs on success, or nil on
  failure."
  [scheduler cluster-profile]
  (future
    (try
      (let [client (get-cluster-client scheduler cluster-profile)]
        (aurora/list-agents client cluster-profile))
      (catch Exception ex
        (log/errorx ex "Failed to list aurora agents for cluster %s"
                    (:aurora_cluster cluster-profile))
        nil))))


(defn- dispatch-agent-updates*
  "Takes a map of scheduler agent states, a collection of gocd agent info, and
  a collection of deferred aurora agent job collections, and calls the provided
  function on each unique agent-id, state, aurora job, and gocd info."
  [dispatch agent-states aurora-agent-futures gocd-agents]
  (future
    (let [aurora-map (into {}
                           (comp
                             (mapcat deref)
                             (map (juxt :agent-id identity)))
                           aurora-agent-futures)
          gocd-map (into {}
                         (map (juxt :agent_id identity))
                         gocd-agents)]
      (->>
        (concat (keys agent-states)
                (keys aurora-map)
                (keys gocd-map))
        (into (sorted-set))
        (map (juxt identity aurora-map gocd-map))
        (run! (partial apply dispatch))))))


(defn manage-clusters
  "Manage a collection of clusters, updating their resources and managing their
  internal agents."
  [scheduler cluster-profiles gocd-agents]
  (let [self *agent*
        scheduler (init-cluster-clients scheduler cluster-profiles)
        aurora-futures (mapv (partial list-aurora-agents* scheduler) cluster-profiles)]
    (run! (partial check-cluster-quota* scheduler) cluster-profiles)
    (letfn [(dispatch-update
              [agent-id aurora-job gocd-agent]
              (send self handle-agent-event agent-id manage-agent-state aurora-job gocd-agent))]
      (dispatch-agent-updates*
        dispatch-update
        (:agents scheduler)
        aurora-futures
        gocd-agents))
    scheduler))
