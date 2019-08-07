(defproject amperity/gocd-aurora-elastic-agent "0.1.0-SNAPSHOT"
  :description "A plugin for GoCD providing elastic agent support via Apache Aurora."
  :url "https://github.com/amperity/gocd-aurora-elastic-agent"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :repositories
  [["amperity" "https://s3-us-west-2.amazonaws.com/amperity-static-packages/jars/"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]

   ;; GoCD plugin lib.
   [cd.go.plugin/go-plugin-api "18.2.0" :scope "provided"]

   ;; Aurora API and related libs, since the project below doesn't actually
   ;; declare any transitive dependencies.
   [amperity/aurora-api "0.21.0"]
   [com.google.code.gson/gson "2.8.5"]
   [com.google.guava/guava "23.0"]
   [org.apache.thrift/libthrift "0.9.1"] ])
