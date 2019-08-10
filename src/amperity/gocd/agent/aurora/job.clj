(ns amperity.gocd.agent.aurora.job
  "Code and configuration for specifying a GoCD Agent job in Aurora."
  (:require
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.string :as str]))


;; TODO: rehost in S3 bucket
(def gocd-download-url "https://download.gocd.org/binaries")
;; TODO: get this from settings
(def gocd-version "19.7.0")
(def gocd-build "9567")


(defn- validate-settings!
  "Validate the configuration for running an agent."
  [params]
  (doseq [k [:server-url
             :auto-register-hostname
             :auto-register-key
             :elastic-plugin-id
             :elastic-agent-id]]
    (let [v (get params k)]
      (when-not (string? v)
        (throw (IllegalArgumentException.
                 (str "Agent job settings must include a " (name k)
                      " string, got: " (pr-str v))))))))


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
