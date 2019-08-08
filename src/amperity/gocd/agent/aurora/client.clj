(ns amperity.gocd.agent.aurora.client
  "Aurora client methods and wrappers."
  (:require
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.string :as str])
  (:import
    (org.apache.aurora.gen
      AuroraSchedulerManager$Client
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


;; TODO: get these from environment
(def gocd-download-url "https://download.gocd.org/binaries")
(def gocd-version "19.7.0")
(def gocd-build "9567")



;; ## Job Task

(defn- agent-source-url
  "Return the URL to fetch the GoCD agent zip from."
  [version build]
  (let [coord (str version "-" build)]
    (str gocd-download-url "/" coord "/generic/go-agent-" coord ".zip")))


(defn- install-proc
  "Constructs a new Aurora process definition to fetch and install the gocd
  agent."
  [version build]
  {:name "agent:install"
   :daemon false
   :max_failures 1
   :ephemeral false
   :min_duration 5
   :cmdline (str "wget -O go-agent.zip " (agent-source-url version build)
                 " && unzip go-agent.zip"
                 " && mv go-agent-" version " go-agent")
   :final false})


(defn- configure-proc
  "Constructs a new Aurora process definition to fetch and install the gocd
  agent."
  [params]
  (let [autoregister-properties-path "go-agent/config/autoregister.properties"
        wrapper-properties-path "go-agent/wrapper-config/wrapper-properties.conf"]
    (letfn [(clean
              [path]
              (str "rm -f " path))
            (autoregister-property
              [k v]
              (str "echo '" k "=" v "' >> " autoregister-properties-path))
            (wrapper-property
              [k v]
              (str "echo '" k "=" v "' >> " wrapper-properties-path))]
      {:name "agent:configure"
       :daemon false
       :max_failures 1
       :ephemeral false
       :min_duration 5
       :cmdline (str/join
                  " && "
                  ["mkdir go-agent/config"
                   (clean autoregister-properties-path)
                   (autoregister-property "agent.auto.register.key" (:auto-register-key params))
                   (autoregister-property "agent.auto.register.hostname" (:auto-register-hostname params))
                   (autoregister-property "agent.auto.register.environments" (:auto-register-environment params))
                   (autoregister-property "agent.auto.register.elasticAgent.pluginId" (:elastic-plugin-id params))
                   (autoregister-property "agent.auto.register.elasticAgent.agentId" (:elastic-agent-id params))
                   (clean wrapper-properties-path)
                   (wrapper-property "wrapper.app.parameter.100" "-serverUrl")
                   (wrapper-property "wrapper.app.parameter.101" (:server-url params))])
       :final false})))


(defn- run-proc
  "Constructs a new Aurora process definition to run the gocd agent."
  []
  {:name "agent:run"
   :daemon false
   :max_failures 1
   :ephemeral false
   :min_duration 5
   :cmdline "go-agent/bin/go-agent console"
   :final false})


(defn- agent-task
  "Builds a task config map for a gocd agent."
  [job-name settings]
  (let [procs [(install-proc gocd-version gocd-build)
               (configure-proc settings)
               (run-proc)]
        order (into [] (comp (remove :final) (map :name)) procs)]
    {:name job-name
     :finalization_wait 30
     :max_failures 1
     :max_concurrency 0
     :constraints [{:order order}]
     :processes procs}))



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



;; ## Aurora API

#_
(defn- make-aurora-call
  ^Response
  [address call-fn]
  (with-open [transport (THttpClient. address)]
    (let [protocol (TJSONProtocol. transport)
          client (clojure.lang.Reflector/invokeConstructor
                   AuroraSchedulerManager$Client
                   (to-array [protocol]))]
      (call-fn client))))


(defn launch-agent!
  "Use the aurora client to launch a new agent job."
  [^AuroraSchedulerManager$Client client
   cluster-profile
   agent-profile
   gocd-environment
   gocd-autoregister-key
   gocd-job]
  (let [aurora-cluster (:aurora_cluster cluster-profile)
        aurora-role (:aurora_role cluster-profile)
        aurora-env (:aurora_env cluster-profile)
        agent-tag "test"  ; TODO: parameterize
        agent-number 0    ; TODO: determine free number from active agents or generate hash
        agent-name (str agent-tag "-agent-" agent-number)
        agent-id (str aurora-cluster "/" aurora-role "/" aurora-env "/" agent-name)
        resources {:cpu (:agent_cpu agent-profile 1.0)
                   :ram (:agent_ram agent-profile 2048)
                   :disk (:agent_disk agent-profile 2048)}
        task (agent-task
               agent-name
               {:auto-register-key nil
                :auto-register-hostname agent-name
                :auto-register-environment nil
                :elastic-plugin-id "amperity.gocd.agent.aurora"
                :elastic-agent-id agent-id
                :server-url nil})
        job-config (->job-config
                     aurora-cluster
                     aurora-role
                     aurora-env
                     agent-name
                     resources
                     task)]
    ;(log/infof "Submitting Aurora job %s/%s/%s/%s" job-id)
    (let [response (.createJob client job-config)]
      ;(check-code! "CreateJob" response)
      agent-id)))
