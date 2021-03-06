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
  (when-let [environments (:auto-register-environments params)]
    (when-not (string? environments)
      (throw (IllegalArgumentException.
               (str "Agent job setting auto-register-environments must be a"
                    " string, got: " (pr-str environments)))))))



;; ## Job Tasks

(defn- ->proc
  "Construct an Aurora process definition with the given name and command
  list. Commands will be flattened and have nils removed."
  [proc-name & cmds]
  {:name proc-name
   :daemon false
   :max_failures 1
   :ephemeral false
   :min_duration 5
   :cmdline (->> (flatten cmds)
                 (remove nil?)
                 (str/join "\n"))
   :final false})


(defn- install-proc
  "Constructs a new Aurora process definition to fetch and install the gocd
  agent."
  [source-url]
  (->proc
    "install"
    "set -e"
    (str "wget -O go-agent.zip " source-url)
    "unzip go-agent.zip"
    "rm go-agent.zip"
    "mv go-agent-* go-agent"))


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
      (->proc
        "configure"
        "set -e"
        "mkdir go-agent/config"
        (clean wrapper-properties-path)
        (wrapper-property "wrapper.app.parameter.100" "-serverUrl")
        (wrapper-property "wrapper.app.parameter.101" (:server-url params))
        (wrapper-property "wrapper.port" "{{thermos.ports[wrapper]}}")
        (wrapper-property "wrapper.jvm.port.min" "57345")
        (wrapper-property "wrapper.jvm.port.max" "61000")
        (clean autoregister-properties-path)
        (autoregister-property "agent.auto.register.key" (:auto-register-key params))
        (autoregister-property "agent.auto.register.hostname" (:auto-register-hostname params))
        (when-let [environments (:auto-register-environments params)]
          (autoregister-property "agent.auto.register.environments" environments))
        (autoregister-property "agent.auto.register.elasticAgent.pluginId" (:elastic-plugin-id params))
        (autoregister-property "agent.auto.register.elasticAgent.agentId" (:elastic-agent-id params))
        (str "base64 -d <<<'" (u/b64-encode-str logback-xml) "' > go-agent/config/agent-bootstrapper-logback.xml")
        "cp go-agent/config/agent-bootstrapper-logback.xml go-agent/config/agent-launcher-logback.xml"
        "cp go-agent/config/agent-bootstrapper-logback.xml go-agent/config/agent-logback.xml"))))


(defn- run-proc
  "Constructs a new Aurora process definition to run the gocd agent."
  [init-script]
  (->proc
    "run"
    (when-not (str/blank? init-script)
      init-script)
    "export PATH=\"$HOME/bin:$PATH\""
    "go-agent/bin/go-agent console"))


(defn agent-task
  "Builds a task config map for a gocd agent."
  [job-name settings]
  (validate-settings! settings)
  (let [procs (into
                []
                (remove nil?)
                [(install-proc (:agent-source-url settings))
                 (configure-proc settings)
                 (run-proc (:init-script settings))])
        order (into [] (comp (remove :final) (map :name)) procs)]
    {:name job-name
     :finalization_wait 30
     :max_failures 1
     :max_concurrency 0
     :constraints [{:order order}]
     :processes procs}))
