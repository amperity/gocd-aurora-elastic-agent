(ns amperity.gocd.agent.aurora.client
  "Aurora client methods and wrappers."
  (:require
    [amperity.gocd.agent.aurora.job :as job]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    java.time.Instant
    (org.apache.aurora.gen
      AuroraSchedulerManager$Client
      AuroraSchedulerManager$Client$Factory
      ExecutorConfig
      JobConfiguration
      JobKey
      JobSummary
      Resource
      Response
      ResponseCode
      ResponseDetail
      ScheduledTask
      TaskConfig
      TaskConstraint
      TaskEvent
      TaskQuery)
    (org.apache.thrift.protocol
      TJSONProtocol)
    (org.apache.thrift.transport
      THttpClient)))


;; ## Aurora Interop

(defn- enum->keyword
  "Converts an enum constant to a keyword by lower-casing and kebab-casing
  the constant's name. Returns nil when value is nil."
  [value]
  (when value
    (-> (str value)
        (str/lower-case)
        (str/replace "_" "-")
        (keyword))))


(defn- ->job-key
  "Constructs a new Aurora `JobKey` object."
  ^JobKey
  [role environment job-name]
  (doto (JobKey.)
    (.setRole role)
    (.setEnvironment environment)
    (.setName job-name)))


(defn- entry->resource
  "Construct a new `Resource` requirement object."
  ^Resource
  [[k v]]
  (case k
    :cpu (doto (Resource.) (.setNumCpus v))
    :ram (doto (Resource.) (.setRamMb v))
    :disk (doto (Resource.) (.setDiskMb v))
    (throw (IllegalArgumentException.
             (str "Unknown resource key: " (pr-str k))))))


(defn- ->job-config
  "Constructs a new Aurora `JobConfiguration` object."
  ^JobConfiguration
  [aurora-cluster
   aurora-role
   aurora-env
   job-name
   resources
   task]
  (let [job-key (->job-key aurora-role aurora-env job-name)
        resources (select-keys resources [:cpu :ram :disk])
        aurora-resources (into #{} (map entry->resource) resources)
        exec-resources (-> resources
                           (update :ram * 1024 1024)
                           (update :disk * 1024 1024))
        exec-data {:cluster aurora-cluster
                   :role aurora-role
                   :environment aurora-env
                   :name job-name
                   ;:tier "..."
                   :priority 0
                   :service true
                   :max_task_failures 1
                   :task (assoc task :resources exec-resources)}
        exec-conf (doto (ExecutorConfig.)
                    (.setName "AuroraExecutor")
                    (.setData (u/json-encode exec-data)))
        task-conf (doto (TaskConfig.)
                    (.setJob job-key)
                    (.setResources aurora-resources)
                    (.setPriority 0)
                    (.setIsService true)
                    (.setMaxTaskFailures 1)
                    (.setExecutorConfig exec-conf))]
    (doto (JobConfiguration.)
      (.setKey job-key)
      (.setTaskConfig task-conf)
      (.setInstanceCount 1))))


(defn- aggregate-task-state
  "Computes an aggregate job state from the task state counts."
  [task-states]
  (or (first (filter (comp pos-int? task-states)
                     [:active :pending :failed :finished]))
      :unknown))


(defn- JobSummary->map
  "Coerce a job summary to a Clojure map."
  [^JobSummary summary]
  (let [job-config (.getJob summary)
        job-key (.getKey job-config)
        ;task-config (.getTaskConfig job-config)
        stats (.getStats summary)
        task-states {:active (.getActiveTaskCount stats)
                     :failed (.getFailedTaskCount stats)
                     :finished (.getFinishedTaskCount stats)
                     :pending (.getPendingTaskCount stats)}]
    {:role (.getRole job-key)
     :environment (.getEnvironment job-key)
     :name (.getName job-key)
     :state (aggregate-task-state task-states)
     :task-states task-states}))


(defn- TaskEvent->map
  [^TaskEvent event]
  (cond-> {:time (Instant/ofEpochMilli (.getTimestamp event))
           :status (enum->keyword (.getStatus event))
           :message (.getMessage event)
           :scheduler (.getScheduler event)}
    (.isSetScheduler event)
    (assoc :scheduler (.getScheduler event))

    (.isSetMessage event)
    (assoc :message (.getMessage event))))


(defn- ScheduledTask->map
  [^ScheduledTask task]
  (let [assigned (.getAssignedTask task)]
    (cond-> {:task-id (.getTaskId assigned)
             :slave-id (.getSlaveId assigned)
             :slave-host (.getSlaveHost assigned)
             :instance-id (.getInstanceId assigned)
             :status (enum->keyword (.getStatus task))
             :failures (.getFailureCount task)
             :events (mapv TaskEvent->map (.getTaskEvents task))}
      (.getAncestorId task)
      (assoc :ancestor-id (.getAncestorId task)))))



;; ## Aurora Calls

(defn get-client
  "Open a client connected to the given URL. Retrieves a cached client from the
  state atom or creates a new one as needed."
  [state url]
  (if-let [client (get-in @state [:clients url])]
    client
    (let [transport (THttpClient. url)
          protocol (TJSONProtocol. transport)
          factory (AuroraSchedulerManager$Client$Factory.)
          candidate (.getClient factory protocol)
          new-state (swap! state (fn maybe-update
                                   [state-map]
                                   (if (get-in state-map [:clients url])
                                     ;; Already had a client, someone beat us to it.
                                     state-map
                                     ;; Inject new client.
                                     (assoc-in state-map [:clients url] candidate))))
          client (get-in new-state [:clients url])]
      (when-not (identical? candidate client)
        (.close transport))
      client)))


(defn- check-code!
  "Check that the Aurora client call succeeded."
  [call-name ^Response response]
  (let [response-code (.getResponseCode response)]
    (when-not (= response-code ResponseCode/OK)
      (throw (ex-info (str call-name " returned unsuccessful response code: "
                           response-code)
                      {:code response-code
                       :errors (vec (.getDetails response))})))))



;; ## Agent API

(defn list-agents
  "List the agent jobs running in Aurora."
  [^AuroraSchedulerManager$Client client aurora-role]
  (let [response (.getJobSummary client aurora-role)]
    (check-code! "GetJobSummary" response)
    (into []
          (map JobSummary->map)
          (.. response
              getResult
              getJobSummaryResult
              getSummaries))))


(defn get-agent
  "Retrieve information about a specific agent."
  [^AuroraSchedulerManager$Client client agent-id]
  (let [[aurora-cluster aurora-role aurora-env agent-name] (str/split agent-id #"/")
        query (doto (TaskQuery.)
                (.setRole aurora-role)
                (.setEnvironment aurora-env)
                (.setJobName agent-name))
        response (.getTasksStatus client query)
        _ (check-code! "GetTasksStatus" response)
        tasks (mapv ScheduledTask->map (.. response
                                           getResult
                                           getScheduleStatusResult
                                           getTasks))]
    {:name agent-name
     :state (aggregate-task-state (frequencies (map :status tasks)))
     :tasks tasks}))


(defn launch-agent!
  "Use the aurora client to launch a new agent job."
  [^AuroraSchedulerManager$Client client
   server-url ; TODO: can get from sending go.processor.server-info.get to the server
   cluster-profile
   agent-profile
   agent-name ; TODO: determine free number from active agents or generate hash
   gocd-register-key
   gocd-environment
   gocd-job]
  (let [aurora-cluster (:aurora_cluster cluster-profile)
        aurora-role (:aurora_role cluster-profile)
        aurora-env (:aurora_env cluster-profile)
        agent-id (str aurora-cluster "/" aurora-role "/" aurora-env "/" agent-name)
        resources {:cpu (if-let [v (:agent_cpu agent-profile)]
                          (Double/parseDouble v)
                          1.0)
                   :ram (if-let [v (:agent_ram agent-profile)]
                          (Integer/parseInt v)
                          1024)
                   :disk (if-let [v (:agent_disk agent-profile)]
                           (Integer/parseInt v)
                           1024)}
        ;; TODO: fully inject this? :thinking:
        task (job/agent-task
               agent-name
               {:auto-register-hostname agent-name
                :auto-register-environment gocd-environment
                :auto-register-key gocd-register-key
                :elastic-plugin-id u/plugin-id
                :elastic-agent-id agent-id
                :server-url server-url})
        job-config (->job-config
                     aurora-cluster
                     aurora-role
                     aurora-env
                     agent-name
                     resources
                     task)]
    (log/info "Submitting Aurora job %s" agent-id)
    (let [response (.createJob client job-config)]
      (check-code! "CreateJob" response)
      agent-id)))


(defn kill-agent!
  [^AuroraSchedulerManager$Client client agent-id]
  (let [[aurora-cluster aurora-role aurora-env agent-name] (str/split agent-id #"/")
        job-key (->job-key aurora-role aurora-env agent-name)
        task-instances #{}
        response (.killTasks client job-key task-instances "Killed by GoCD Aurora Elastic Agent plugin")]
    (check-code! "KillTasks" response)
    {:details (mapv #(.getMessage ^ResponseDetail %) (.getDetails response))}))
