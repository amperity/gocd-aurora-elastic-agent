(ns amperity.gocd.agent.aurora.util
  "Plugin utilities."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk])
  (:import
    com.google.gson.Gson
    java.util.Base64))


;; TODO: read from plugin.xml ?
(def ^:const plugin-id
  "amperity.gocd.agent.aurora")


(def ^:private ^Gson gson
  (Gson.))


(defn- stringify-keywords
  "Recursively transforms all keywords into strings."
  [v]
  (walk/postwalk
    (fn [x]
      (if (keyword? x)
        (subs (str x) 1)
        x))
    v))


(defn- gson->clj
  "Recursively transforms data structures into their Clojure equivalents."
  [v]
  (walk/prewalk
    (fn [x]
      (cond
        (instance? java.util.List x)
        (into [] x)

        (instance? java.util.Map x)
        (into {}
              (map (fn [[k v]]
                     (if (string? k)
                       [(keyword k) v]
                       [k v])))
              x)

        (instance? java.util.Set x)
        (into #{} x)

        :else x)
      )
    v))


(defn json-encode
  "Encode a value to a JSON string."
  ^String
  [value]
  (.toJson gson (stringify-keywords value)))


(defn json-decode-vec
  "Decode a vector from a JSON string."
  [^String json]
  (gson->clj (.fromJson gson json java.util.List)))


(defn json-decode-map
  "Decode a map from a JSON string."
  [^String json]
  (gson->clj (.fromJson gson json java.util.Map)))


(defn b64-encode-str
  "Encode the bytes in a string to a Base64-encoded string."
  [^String data]
  (.encodeToString (Base64/getEncoder) (.getBytes data)))
