(ns amperity.gocd.agent.aurora.client
  "Aurora client methods and wrappers."
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
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
    {:aurora-role (.getRole job-key)
     :aurora-env (.getEnvironment job-key)
     :agent-name (.getName job-key)
     :states task-states}))


(defn- TaskEvent->map
  [^TaskEvent event]
  (cond-> {:time (Instant/ofEpochMilli (.getTimestamp event))
           :status (u/enum->keyword (.getStatus event))
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
             :status (u/enum->keyword (.getStatus task))
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


(defn close-client
  "Close a client in the state."
  [state url]
  ;; TODO: release resources?
  (swap! state update :clients dissoc url)
  nil)


(defn- check-code!
  "Check that the Aurora client call succeeded."
  [^Response response call-name]
  (let [response-code (.getResponseCode response)]
    (when-not (= response-code ResponseCode/OK)
      (throw (ex-info (str call-name " returned unsuccessful response code: "
                           response-code)
                      {:code response-code
                       :errors (into []
                                     (map #(.getMessage ^ResponseDetail %))
                                     (.getDetails response))})))))


(defmacro ^:private with-client
  "Evaluate the body with an Aurora client for `url` bound to `aurora-client`.
  Locks the client during the evaluation and closes it if any error is thrown."
  [state url & body]
  `(let [~'aurora-client (get-client ~state ~url)]
     (try
       (locking ~'aurora-client
         ~@body)
      (catch Exception ex#
        (close-client ~state ~url)
        (throw ex#)))))


(defmacro ^:private aurora-call
  "Make a call using the Aurora client bound by `with-client` and return the
  result. Automatically checks the response code."
  [method-sym & args]
  (let [client (vary-meta 'aurora-client assoc :tag 'AuroraSchedulerManager$Client)
        method-name (str (str/upper-case (subs (str method-sym) 0 1))
                         (subs (str method-sym) 1))]
    `(doto (. ~client ~method-sym ~@args)
       (check-code! ~method-name))))



;; ## Agent API

(defn list-agents
  "List the agent jobs running in Aurora."
  [state url aurora-role aurora-env]
  (with-client state url
    (let [response (aurora-call getJobSummary aurora-role)]
      (into []
            (comp
              (map JobSummary->map)
              (filter #(= aurora-env (:aurora-env %)))
              (keep (fn agents-only
                      [summary]
                      (when-let [[_ tag number] (re-matches #"([a-z-]+)-agent-(\d+)"
                                                            (:agent-name summary))]
                        (assoc summary
                               :agent-tag tag
                               :agent-num number)))))
            (.. response
                getResult
                getJobSummaryResult
                getSummaries)))))


(defn get-agent
  "Retrieve information about a specific agent."
  [state url agent-id]
  (with-client state url
    (let [{:keys [aurora-cluster aurora-role aurora-env agent-name]} (agent/parse-id agent-id)
          query (doto (TaskQuery.)
                  (.setRole aurora-role)
                  (.setEnvironment aurora-env)
                  (.setJobName agent-name))
          response (aurora-call getTasksStatus query)
          tasks (mapv ScheduledTask->map
                      (.. response
                          getResult
                          getScheduleStatusResult
                          getTasks))
          current (loop [tasks tasks
                         candidate nil
                         ^Instant max-time nil]
                    (if (seq tasks)
                      (let [task (first tasks)
                            task-time (last (keep :time (:events task)))]
                        (if (or (nil? max-time) (.isBefore max-time task-time))
                          (recur (next tasks) task task-time)
                          (recur (next tasks) candidate max-time)))
                      candidate))]
      (assoc current
             ;:history tasks
             :aurora-cluster aurora-cluster
             :aurora-role aurora-role
             :aurora-env aurora-env
             :agent-name agent-name))))


(defn launch-agent!
  "Launch a new agent job in Aurora."
  [state
   url
   cluster-profile
   agent-profile
   agent-name
   agent-task]
  (with-client state url
    (let [aurora-cluster (:aurora_cluster cluster-profile)
          aurora-role (:aurora_role cluster-profile)
          aurora-env (:aurora_env cluster-profile)
          resources (merge agent/default-resources (agent/profile->resources agent-profile))
          job-config (->job-config
                       aurora-cluster
                       aurora-role
                       aurora-env
                       agent-name
                       resources
                       agent-task)]
      (log/info "Submitting Aurora job %s/%s/%s/%s"
                aurora-cluster
                aurora-role
                aurora-env
                agent-name)
      (aurora-call createJob job-config)
      true)))


(defn kill-agent!
  "Kill a running agent job in Aurora."
  [state url agent-id]
  (with-client state url
    (let [{:keys [aurora-cluster aurora-role aurora-env agent-name]} (agent/parse-id agent-id)
          job-key (->job-key aurora-role aurora-env agent-name)
          task-instances #{}
          response (aurora-call killTasks
                                job-key task-instances
                                "Killed by GoCD Aurora Elastic Agent plugin")]
      {:details (mapv #(.getMessage ^ResponseDetail %) (.getDetails response))})))
