(ns user
  (:require
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.logging :as log]
    [amperity.gocd.agent.aurora.scheduler :as scheduler]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]))


(def scheduler
  "Placeholder scheduler, representing the plugin state."
  (agent
    {:server-url "http://10.255.255.11:8153/go"
     :clients {}
     :clusters {}
     :agents {}}))
