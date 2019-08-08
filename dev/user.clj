(ns user
  (:require
    [amperity.gocd.agent.aurora.client :as aurora]
    [amperity.gocd.agent.aurora.plugin :as plugin]
    [amperity.gocd.agent.aurora.util :as u]
    [clojure.repl :refer :all]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]))
