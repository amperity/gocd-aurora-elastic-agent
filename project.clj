(defproject amperity/gocd-aurora-elastic-agent "0.1.0-SNAPSHOT"
  :description "A plugin for GoCD providing elastic agent support via Apache Aurora."
  :url "https://github.com/amperity/gocd-aurora-elastic-agent"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :repositories
  [["amperity" "https://s3-us-west-2.amazonaws.com/amperity-static-packages/jars/"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [amperity/aurora-api "0.21.0"]
   ;; Included as undeclared transitive dependencies of aurora-api.
   [com.google.code.gson/gson "2.8.5"]
   [org.apache.thrift/libthrift "0.9.1"]]

  :java-source-paths ["src"]

  :profiles
  {:provided
   {:dependencies
    [[cd.go.plugin/go-plugin-api "19.7.0"]
     [com.google.guava/guava "23.0"]]}

   :repl
   {:source-paths ["dev"]
    :dependencies
    [[org.clojure/tools.namespace "0.2.11"]]}

   :uberjar
   {:target-path "target/uberjar"
    :aot :all}})
