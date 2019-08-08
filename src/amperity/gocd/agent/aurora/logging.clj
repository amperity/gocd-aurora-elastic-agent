(ns amperity.gocd.agent.aurora.logging
  "Basic logging support code.")


(def ^:private logger
  amperity.gocd.agent.aurora.AuroraElasticAgentPlugin/LOGGER)


(defmacro ^:private deflog
  "Define a logging function with the given level symbol."
  [level]
  `(defn ~level
     [& args#]
     (if (instance? Throwable (first args#))
       (let [[~'ex & more#] args#]
         (. logger ~level (str (apply format more#)) ~(vary-meta 'ex assoc :tag 'Throwable))
         (. logger ~level (str (apply format args#)))))))


(deflog debug)
(deflog info)
(deflog warn)
(deflog error)
