(ns amperity.gocd.agent.aurora.agent-test
  (:require
    [amperity.gocd.agent.aurora.agent :as agent]
    [clojure.test :refer [deftest testing is]]))


(deftest validate-settings
  (testing "happy path"
    (let [input
          {:agent_tag "build"
           :cpu "1.0"
           :ram "512"
           :disk "1024"}]
      (is (= [] (agent/validate-settings input)))
      (is (= [] (agent/validate-settings (assoc input :environments ""))))
      (is (= [] (agent/validate-settings (assoc input :environments "foo-env"))))
      (is (= [] (agent/validate-settings (assoc input :environments "foo-env,bar-env,baz_env"))))))
  (testing "failures"
    (let [input
          {:agent_tag "haha invalid"
           :environments "i don't like spaces either"
           :cpu "french"
           :ram "seventy.five"
           :disk "75"}
          result (agent/validate-settings input)]
      (is (= 5 (count result)))
      (is (= #{:agent_tag :environments :cpu :ram :disk}
             (into #{} (map :key) result))))))
