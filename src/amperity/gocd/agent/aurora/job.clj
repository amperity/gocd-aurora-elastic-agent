(ns amperity.gocd.agent.aurora.job
  "Code and configuration for specifying a GoCD Agent job in Aurora."
  (:require
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str]))


;; ## Job Settings

(defn- validate-settings!
  "Validate the configuration for running an agent."
  [params]
  (doseq [k [:server-url
             :agent-source-url
             :auto-register-hostname
             :auto-register-key
             :elastic-plugin-id
             :elastic-agent-id]]
    (let [v (get params k)]
      (when-not (string? v)
        (throw (IllegalArgumentException.
                 (str "Agent job settings must include a " (name k)
                      " string, got: " (pr-str v)))))))
  (when-let [environment (:auto-register-environment params)]
    (when-not (string? environment)
      (throw (IllegalArgumentException.
               (str "Agent job setting auto-register-environment must be a"
                    " string, got: " (pr-str environment)))))))



;; ## Job Tasks

(defn- install-proc
  "Constructs a new Aurora process definition to fetch and install the gocd
  agent."
  [source-url]
  {:name "agent:install"
   :daemon false
   :max_failures 1
   :ephemeral false
   :min_duration 5
   :cmdline (->>
              ["set -e"
               (str "wget -O go-agent.zip " source-url)
               "unzip go-agent.zip"
               "rm go-agent.zip"
               "mv go-agent-* go-agent"]
              (str/join "\n"))
   :final false})


(defn- configure-proc
  "Constructs a new Aurora process definition to fetch and install the gocd
  agent."
  [params]
  (let [wrapper-properties-path "go-agent/wrapper-config/wrapper-properties.conf"
        autoregister-properties-path "go-agent/config/autoregister.properties"
        logback-xml (slurp (io/resource "amperity/gocd/agent/aurora/logback-agent.xml"))]
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
       :cmdline (->>
                  ["set -e"
                   "mkdir go-agent/config"
                   (clean wrapper-properties-path)
                   (wrapper-property "wrapper.app.parameter.100" "-serverUrl")
                   (wrapper-property "wrapper.app.parameter.101" (:server-url params))
                   (clean autoregister-properties-path)
                   (autoregister-property "agent.auto.register.key" (:auto-register-key params))
                   (autoregister-property "agent.auto.register.hostname" (:auto-register-hostname params))
                   (when-let [environment (:auto-register-environment params)]
                     (autoregister-property "agent.auto.register.environments" environment))
                   (autoregister-property "agent.auto.register.elasticAgent.pluginId" (:elastic-plugin-id params))
                   (autoregister-property "agent.auto.register.elasticAgent.agentId" (:elastic-agent-id params))
                   (str "base64 -d <<<'" (u/b64-encode-str logback-xml) "' > go-agent/config/agent-bootstrapper-logback.xml")
                   "cp go-agent/config/agent-bootstrapper-logback.xml go-agent/config/agent-launcher-logback.xml"
                   "cp go-agent/config/agent-bootstrapper-logback.xml go-agent/config/agent-logback.xml"]
                  (remove nil?)
                  (str/join "\n"))
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


(defn agent-task
  "Builds a task config map for a gocd agent."
  [job-name settings]
  (validate-settings! settings)
  (let [procs [(install-proc (:agent-source-url settings))
               (configure-proc settings)
               (run-proc)]
        order (into [] (comp (remove :final) (map :name)) procs)]
    {:name job-name
     :finalization_wait 30
     :max_failures 1
     :max_concurrency 0
     :constraints [{:order order}]
     :processes procs}))
