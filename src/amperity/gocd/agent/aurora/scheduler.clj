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
    [clojure.string :as str])
  (:import
    java.time.Instant))



(comment
  ;; Scheduler state structure.
  {:clusters
   {"aws-dev"
    {:url "http://..."
     :quota {,,,}}}

   :agents
   {"aws-dev/www-data/prod/test-agent-0"
    {:state :running
     :environment "build"
     :resources {:cpu 1.0, :ram 1024, :disk 1024}
     ;; TODO: launched-for?
     :last-active #inst "2019-08-10T14:16:00Z"
     :events [{:time #inst "2019-08-10T13:55:23Z"
               :state :launching
               :message "..."}
              ...]}}})


;; So what does the plugin management look like?
;;
;; ### create-agent
;; Send a message to the scheduler agent so that name selection is
;; single-threaded. If the scheduler decides to launch a new agent, it adds the
;; record in `:launching` and uses a function to start a future which will send
;; a state-update putting the agent into `:pending` or `:failed`.
;;
;; ### should-assign-work
;; Unlike the other methods, this needs to synchronously return a result.
;; Fortunately, it doesn't require changing any state so we just need to look
;; up the agent resources and make a decision in-thread.
;;
;; ### job-completion
;; This one is easy, just touch the last-active time on the agent.
;;
;; ### server-ping
;; The most complex case. First, use the app-accessor to load the list of
;; gocd-agents.
;;
;; For each cluster, start a thread to go read the quota from that cluster.
;; Each thread sends back an update to the scheduler.
;;
;; For each cluster, start a thread to list the agents in that cluster. Create
;; another thread blocking on all the results. On success, send a message back
;; to the agent with all of the paired up state/gocd/aurora agent information.
;; Use this to perform state logic for every known agent.
;; Note that a failed Aurora list call will prevent some state transitions, but
;; the main sequence should work okay.


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



;; ## Client Management

(defn- init-client
  "Initialize an Aurora client for the given cluster."
  [scheduler cluster-profile]
  (if-let [url (:aurora_url cluster-profile)]
    (update-in scheduler [:clients url] aurora/ensure-client url)
    scheduler))


(defn- init-cluster-clients
  "Initialize an Aurora client for each of the given clusters."
  [scheduler cluster-profiles]
  (reduce init-client scheduler cluster-profiles))


(defn- get-client
  "Fetch an initialized Aurora client for the given profile."
  [scheduler cluster-profile]
  (let [url (:aurora_url cluster-profile)]
    (get-in scheduler [:clients url])))



;; ## Agent Management

(defn- init-agent-state
  "Initialize a new agent using the given profile."
  [agent-id state agent-profile gocd-environment]
  {:agent-id agent-id
   :state state
   :environment gocd-environment
   :resources (agent/profile->resources agent-profile)
   :last-active (Instant/now)
   :events []})


(defn- update-agent-state
  "Updates the agent's state and adds a new event."
  [agent-state state message]
  (-> agent-state
      (assoc :state state)
      (update :events
              (fnil conj [])
              {:time (Instant/now)
               :state state
               :message message})))


(defn- mark-agent-active
  "Mark the identified agent as being recently active."
  [agent-state]
  (assoc agent-state :last-active (Instant/now)))


(defn- stale?
  "True if the agent's last event timestamp is present and more than
  `threshold` seconds in the past."
  [agent-state threshold]
  (let [^Instant last-time (last (keep :time (:events agent-state)))
        horizon (.minusSeconds (Instant/now) threshold)]
    (and last-time (.isBefore last-time horizon))))


(defn- idle?
  "True if the agent's last-active timestamp is present and more than
  `threshold` seconds in the past."
  [agent-state threshold]
  (let [^Instant last-active (:last-active agent-state)
        horizon (.minusSeconds (Instant/now) threshold)]
    (and last-active (.isBefore last-active horizon))))


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



;; ## Cluster Management

(defn- set-cluster-quota
  [scheduler aurora-cluster quota-usage]
  ;; TODO: implement
  scheduler)


(defn- update-cluster-quota*
  "Check the available resource quota in the given cluster. Updates the cluster
  state."
  [scheduler cluster-profile]
  (future
    ;; TODO: fetch/shape cluster quota information
    ,,,))


(defn- list-aurora-agents*
  "List the Aurora agent jobs in the given cluster on a new thread. Returns a
  deferred which yields a collection of aurora jobs on success, or nil on
  failure."
  [scheduler cluster-profile]
  (future
    (try
      (let [client (get-client scheduler cluster-profile)
            aurora-cluster (:aurora_cluster cluster-profile)
            aurora-role (:aurora_role cluster-profile)
            aurora-env (:aurora_env cluster-profile)]
        (aurora/list-agents client aurora-role aurora-env))
      (catch Exception ex
        (log/errorx ex "Failed to list aurora agents for cluster %s"
                    (:aurora_cluster cluster-profile))
        nil))))


(defn- assemble-agent-data*
  "Takes a map of scheduler agent states, a collection of gocd agent info, and
  a collection of deferred aurora agent job collections, and calls the provided
  function on each unique agent-id, state, aurora job, and gocd info."
  [f agent-states aurora-agent-futures gocd-agents]
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
        (map (juxt identity agent-states aurora-map gocd-map))
        (run! (partial apply f))))))


;; TODO: make this better
(declare handle-agent-event)
(declare <manage-agent>)


(defn manage-clusters
  "Manage a collection of clusters, updating their resources and managing their
  internal agents."
  [scheduler cluster-profiles gocd-agents]
  (let [self *agent*
        ;; TODO: make this better
        send-update!
        (fn [agent-id agent-state aurora-job gocd-agent]
          (send self handle-agent-event agent-id <manage-agent> agent-state aurora-job gocd-agent))
        scheduler (init-cluster-clients scheduler cluster-profiles)
        aurora-futures (mapv (partial list-aurora-agents* scheduler) cluster-profiles)]
    (assemble-agent-data*
      send-update!
      (:agents scheduler)
      aurora-futures
      gocd-agents)
    scheduler))



;; ## Agent Scheduling

;; All the logic here is based around the idea of 'agent event functions'. Each
;; of these functions is applied to the state of a specific agent in the
;; scheduler, and should return a map with the following keys:
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

(defn- enact-raw-effect!
  "Enact a side-effect without any error guards or callbacks. Do not call this directly."
  [scheduler effect]
  (let [agent-id (:agent-id effect)]
    (case (:type effect)
      :launch-aurora-agent
      (let [{:keys [cluster-profile agent-profile agent-name]} effect
            aurora-client (get-client scheduler cluster-profile)
            source-url (if (str/blank? (:agent_source_url cluster-profile))
                         cluster/default-agent-source-url
                         (:agent_source_url cluster-profile))
            agent-task (job/agent-task
                         agent-name
                         {:server-url (:server-url scheduler)
                          :agent-source-url source-url
                          :auto-register-hostname agent-name
                          :auto-register-environment (:gocd-environment effect)
                          :auto-register-key (:gocd-register-key effect)
                          :elastic-plugin-id u/plugin-id
                          :elastic-agent-id agent-id})]
        (aurora/create-agent!
          aurora-client
          cluster-profile
          agent-profile
          agent-name
          agent-task))

      :kill-aurora-agent
      ;; NOTE: this code assumes that cluster names map uniquely to profiles.
      (let [aurora-cluster (:aurora-cluster (agent/parse-id agent-id))
            aurora-url (get-in scheduler [:clusters aurora-cluster :url])
            ;; FIXME: ew, gross
            aurora-client (get-client scheduler {:aurora_url aurora-url})]
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
  [scheduler effect]
  (future
    (let [[result ex]
          (try
            [(enact-raw-effect! scheduler effect) nil]
            (catch Exception ex
              (log/errorx ex "Failed to enact side-effect %s on %s"
                          (:type effect)
                          (:agent-id effect))
              [nil ex]))]
      (try
        (if (nil? ex)
          (when-let [on-success (:on-success effect)]
            (on-success result))
          (when-let [on-failure (:on-failure effect)]
            (on-failure ex)))
        (catch Exception ex
          (log/errorx ex "Failed to invoke callback for side-effect %s on %s"
                      (:type effect)
                      (:agent-id effect)))))
    nil))


(defn handle-agent-event
  "Update the agent in the scheduler by applying the event function `f` to its
  state and the provided arguments. The event function should return a result
  map optionally containing an updated `:agent` state, an async `:effect` to
  cause, and a next `:event` to dispatch for further processing."
  [scheduler agent-id f & args]
  (let [send-event (self-dispatcher handle-agent-event agent-id)
        agent-state (get-in scheduler [:agents agent-id])
        result (apply f agent-state args)]
    ;; Invoke side effects.
    (when-let [effect (:effect result)]
      (enact-effect!
        scheduler
        (-> effect
            (assoc :agent-id agent-id)
            (cond->
              (:on-success effect)
              (update :on-success (partial partial apply send-event))
              (:on-failure effect)
              (update :on-failure (partial partial apply send-event)))))
      (enact-effect! scheduler (assoc effect :agent-id agent-id)))
    ;; Update agent state if set.
    (let [next-state (:agent result)]
      (cond
        (not (contains? result :agent))
        scheduler

        (map? next-state)
        (assoc-in scheduler [:agents agent-id] next-state)

        (nil? next-state)
        (update scheduler :agents dissoc agent-id)

        :else
        (do
          (log/warn "Agent event function returned unknown result type: %s"
                    (pr-str next-state))
          scheduler)))))



;; ## Agent Events

(defn- <update-agent-state>
  "Event function to apply an updated state to the agent if it is not already
  the current state."
  [agent-state state message]
  (when (not= state (:state agent-state))
    {:agent (update-agent-state agent-state state message)}))


(defn- <launch-succeeded>
  "Event function for a successful agent launch."
  [agent-state result]
  ;; TODO: use result data in message?
  (log/info "launch-succeeded result: %s" (pr-str result))
  {:agent (update-agent-state
            agent-state :pending
            "Agent job created in Aurora")})


(defn- <launch-failed>
  "Event function for a failed agent launch."
  [agent-state ^Throwable ex]
  {:agent (update-agent-state
            agent-state :failed
            (str "Failed to create agent job in Aurora: "
                 (.getSimpleName (class ex)) " "
                 (.getMessage ex)))})


;; TODO: still need entry point which is not an agent event and chooses the name
(defn <launch-agent>
  "Launch a new agent with the given settings in Aurora. This event is only
  sent by the `create-agent` plugin API call."
  [agent-state
   cluster-profile
   agent-profile
   agent-name
   gocd-environment
   gocd-register-key]
  ;; Ensure we don't double-launch agents.
  (when (and (:state agent-state)
             (not (contains? #{:failed :terminated} (:state agent-state))))
    (throw (IllegalStateException.
             (format "Cannot launch new Aurora job for agent %s which is %s"
                     (:agent-id agent-state)
                     (name (:state agent-state))))))
  {:agent (init-agent-state
            (:agent-id agent-state)
            :launching
            agent-profile
            gocd-environment)
   :effect {:type :launch-aurora-agent
            :cluster-profile cluster-profile
            :agent-profile agent-profile
            :agent-name agent-name
            :gocd-environment gocd-environment
            :gocd-register-key gocd-register-key
            :on-success [<launch-succeeded>]
            :on-failure [<launch-failed>]}})


(defn <mark-agent-active>
  "Event function to mark the agent as recently active."
  [agent-state]
  {:agent (mark-agent-active agent-state)})


(defmulti <manage-agent>
  "Manage an agent's state by synchronizing with the available information from
  the Aurora job and the GoCD server."
  (fn dispatch
    [agent-state aurora-job gocd-agent]
    (:state agent-state))
  :default :unknown)

;; Agent is not tracked in the scheduler state.
(defmethod <manage-agent> :untracked
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If an untracked agent is registered in gocd, this is a legacy agent.
    gocd-agent
    {:agent (update-agent-state
              agent-state :legacy
              "Detected legacy agent in GoCD server")
     :effect {:type :disable-gocd-agent
              :on-success [<update-agent-state> :draining "Waiting for agent to drain"]}}

    ;; If an untracked job is active in Aurora, this is an orphaned agent.
    (aurora-alive? aurora-job)
    {:agent (update-agent-state
              agent-state :orphan
              "Detected orphaned agent job in Aurora")
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}

    ;; Uh... this should never happen, but just in case, do nothing.
    :else
    (log/warn "<manage-agent> encountered unexpected state: %s %s %s"
              (pr-str agent-state)
              (pr-str aurora-job)
              (pr-str gocd-agent))))


;; Agent job is being created in Aurora.
(defmethod <manage-agent> :launching
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If active job in aurora, move to starting.
    (aurora-active? aurora-job)
    {:agent (update-agent-state
              agent-state :starting
              "Aurora job is active, waiting for agent to register")}

    ;; If pending job in aurora, move to pending.
    (aurora-pending? aurora-job)
    {:agent (update-agent-state
              agent-state :pending
              "Aurora job is pending")}

    ;; After a long timeout, assume error and move to failed.
    (stale? agent-state 600)
    {:agent (update-agent-state
              agent-state :failed
              "Agent is stale: no activity for 10 minutes")}))


;; Job created in Aurora, waiting for process to start.
(defmethod <manage-agent> :pending
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If active job in aurora, move to starting.
    (aurora-active? aurora-job)
    {:agent (update-agent-state
              agent-state :starting
              "Aurora job is active, waiting for agent to register")}

    ;; If registered in gocd, move to running.
    (gocd-registered? gocd-agent)
    {:agent (update-agent-state
              agent-state :running
              "Agent registered with GoCD server")}

    ;; After a long timeout, assume stale and kill the agent.
    (stale? agent-state 600)
    {:agent (update-agent-state
              agent-state :killing
              "Agent is stale: no activity for 10 minutes")
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}))


;; Job is active in Aurora, waiting for registration with the GoCD server.
(defmethod <manage-agent> :starting
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If registered in gocd, move to running.
    (gocd-registered? gocd-agent)
    {:agent (update-agent-state
              agent-state :running
              "Agent registered with GoCD server")}

    ;; After a long timeout, assume stale and kill the agent.
    (stale? agent-state 600)
    {:agent (update-agent-state
              agent-state :killing
              "Agent is stale: no activity for 10 minutes")
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}))


;; Primary healthy state.
(defmethod <manage-agent> :running
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If some third party disabled the agent in GoCD, move to draining.
    (= "Disabled" (:agent_state gocd-agent))
    {:agent (update-agent-state
              agent-state :draining
              "GoCD agent externally disabled")}

    ;; If missing or lost-contact, kill/move to killing.
    (contains? #{"Missing" "LostContact"} (:agent_state gocd-agent))
    {:agent (update-agent-state
              agent-state :killing
              (str "GoCD server thinks agent is "
                   (:agent_state gocd-agent)))
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}

    ;; After a period of idleness, disable and move to retiring.
    (and (= "Idle" (:agent_state agent-state))
         (idle? agent-state 300))
    {:agent (update-agent-state
              agent-state :retiring
              "Retiring idle agent")
     :effect {:type :disable-gocd-agent
              :on-success [<update-agent-state> :draining "Agent disabled in GoCD"]}}

    ;; Agent is not idle, update its last active time.
    (not= "Idle" (:agent_state agent-state))
    {:agent (mark-agent-active agent-state)}))


;; Agent has been idle for a while and is being retired from service.
(defmethod <manage-agent> :retiring
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If disabled in gocd, move to draining.
    (= "Disabled" (:config_state gocd-agent))
    {:agent (update-agent-state
              agent-state :draining
              "Agent disabled in GoCD")}

    ;; After a long timeout, retry disable call.
    (stale? agent-state 120)
    {:agent (update-agent-state
              agent-state :retiring
              "Retrying agent retirement")
     :effect {:type :disable-gocd-agent
              :on-success [<update-agent-state> :draining "Agent disabled in GoCD"]}}))


;; Agent is disabled in GoCD, wait to make sure it finishes any running jobs.
(defmethod <manage-agent> :draining
  [agent-state aurora-job gocd-agent]
  (when (contains? #{"Idle" "Missing" "LostContact"} (:agent_state gocd-agent))
    ;; Agent is no longer busy.
    {:agent (update-agent-state
              agent-state :killing
              (str "GoCD agent is " (:agent_state gocd-agent)))
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}))


;; The agent job is being killed in Aurora if it is active.
(defmethod <manage-agent> :killing
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If no longer active or pending in aurora, move to killed.
    (not (aurora-alive? aurora-job))
    {:agent (update-agent-state
              agent-state :killed
              "Aurora job killed")}

    ;; After a timeout, retry killing the job.
    (stale? agent-state 120)
    {:agent (update-agent-state
              agent-state :killing
              "Retrying kill of Aurora job")
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}))


;; The Aurora job has been killed.
(defmethod <manage-agent> :killed
  [agent-state aurora-job gocd-agent]
  (when-not (aurora-alive? aurora-job)
    ;; If no longer active in aurora, remove the agent.
    {:agent (update-agent-state
              agent-state :removing
              "Removing GoCD agent")
     :effect {:type :delete-gocd-agent
              :on-success [<update-agent-state> :terminated "Agent terminated"]}}))


;; The agent is being unregistered from the GoCD server.
(defmethod <manage-agent> :removing
  [agent-state aurora-job gocd-agent]
  (cond
    ;; If no longer registered with gocd, move to terminated.
    (not (gocd-registered? gocd-agent))
    {:agent (update-agent-state
              agent-state :terminated
              "Agent terminated")}

    ;; After a timeout, retry removing the agent.
    (stale? agent-state 120)
    {:agent (update-agent-state
              agent-state :removing
              "Retrying removal of GoCD agent")
     :effect {:type :delete-gocd-agent
              :on-success [<update-agent-state> :terminated "Aurora terminated"]}}))


;; Agent is registered in GoCD but has no scheduler state.
(defmethod <manage-agent> :legacy
  [agent-state aurora-job gocd-agent]
  (when (stale? agent-state 60)
    ;; After a timeout, retry disabling the agent.
    {:agent (update-agent-state
              agent-state :legacy
              "Retrying disable of legacy agent")
     :effect {:type :disable-gocd-agent
              :on-success [<update-agent-state> :draining "Waiting for agent to drain"]}}))


;; Agent job is active in Aurora but has no scheduler state.
(defmethod <manage-agent> :orphan
  [agent-state aurora-job gocd-agent]
  (when (stale? agent-state 60)
    ;; After a timeout, retry killing the agent.
    {:agent (update-agent-state
              agent-state :orphan
              "Retrying kill of orphaned agent")
     :effect {:type :kill-aurora-agent
              :on-success [<update-agent-state> :killed "Aurora job killed"]}}))


;; Agent is in a terminal state, keep state for a bit for introspection.
(defmethod <manage-agent> :failed
  [agent-state aurora-job gocd-agent]
  (when (stale? agent-state 600)
    {:agent nil}))


;; Agent is in a terminal state, keep state for a bit for introspection.
(defmethod <manage-agent> :terminated
  [agent-state aurora-job gocd-agent]
  (when (stale? agent-state 300)
    {:agent nil}))


;; Unknown state, but no running job or registered agent.
(defmethod <manage-agent> :unknown
  [agent-state aurora-job gocd-agent]
  {:agent (update-agent-state
            agent-state :failed
            (str "Aborting after encountering unknown agent state "
                 (pr-str (:state agent-state))))})
