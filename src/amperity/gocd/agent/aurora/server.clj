(ns amperity.gocd.agent.aurora.server
  "Methods for interacting with the GoCD server."
  (:require
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.string :as str])
  (:import
    (com.thoughtworks.go.plugin.api
      GoApplicationAccessor
      GoPluginIdentifier)
    (com.thoughtworks.go.plugin.api.request
      DefaultGoApiRequest)))


(def ^:private plugin-identifier
  "Identifier for the type of plugin and compatible API versions."
  (GoPluginIdentifier. "elastic-agent" ["5.0"]))


(defn get-server-info
  "Retrieve information about the GoCD server."
  [^GoApplicationAccessor app-accessor]
  (let [req (DefaultGoApiRequest. "go.processor.server-info.get" "1.0" plugin-identifier)
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to fetch GoCD server information (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    (u/json-decode-map (.responseBody res))))


(defn list-agents
  "List the GoCD agent registrations for this plugin."
  [^GoApplicationAccessor app-accessor]
  (let [req (DefaultGoApiRequest. "go.processor.elastic-agents.list-agents" "1.0" plugin-identifier)
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to list GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    (u/json-decode-vec (.responseBody res))))


(defn disable-agents!
  "Disable agents in GoCD."
  [^GoApplicationAccessor app-accessor agent-ids]
  (let [agents (mapv (partial array-map :agent_id) agent-ids)
        req (doto (DefaultGoApiRequest. "go.processor.elastic-agents.disable-agents" "1.0" plugin-identifier)
              (.setRequestBody (u/json-encode agents)))
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to disable GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    true))


(defn delete-agents!
  "Delete agents in GoCD. Agents must be disabled first."
  [^GoApplicationAccessor app-accessor agent-ids]
  (let [agents (mapv (partial array-map :agent_id) agent-ids)
        req (doto (DefaultGoApiRequest. "go.processor.elastic-agents.delete-agents" "1.0" plugin-identifier)
              (.setRequestBody (u/json-encode agents)))
        res (.submit app-accessor req)]
    (when (not= 200 (.responseCode res))
      (throw (ex-info (format "Failed to delete GoCD agents (%d): %s"
                              (.responseCode res)
                              (.responseBody res))
                      {})))
    true))
