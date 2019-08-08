(ns amperity.gocd.agent.aurora.util
  "Plugin utilities."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    com.google.gson.Gson
    java.util.Base64))

(let [gson (Gson.)]
  (defn json-encode
    "Encode a value to a JSON string."
    ^String
    [value]
    (.toJson gson value))

  (defn json-decode-map
    "Decode a map from a JSON string."
    [^String json]
    (into {} (.fromJson gson json java.util.Map))))


(defn b64-encode-str
  "Encode the bytes in a string to a Base64-encoded string."
  [^String data]
  (.encodeToString (Base64/getEncoder) (.getBytes data)))
