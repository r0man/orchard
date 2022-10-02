(ns orchard.stacktrace.parser.pst
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [instaparse.core  :as insta :refer [defparser]]
            [orchard.misc :as misc]
            [orchard.stacktrace.parser.util :as util]))

(def ^:private stacktrace-start-regex
  "The regular expression matching the start of an `clojure.repl/pst` stacktrace."
  #"(?s)([a-zA-Z0-9_$/-]+)\s+.*\n\s+([a-zA-Z0-9_$/.-]+)\s+\(([a-zA-Z0-9_$/.-]+):(\d+)\)")

(defparser ^:private parser
  (io/resource "orchard/stacktrace/parser/pst.bnf"))

(defn- transform-data
  "Transform a :data node from Instaparse to Throwable->map."
  [& data]
  (when-let [content (misc/safe-read-edn (apply str data))]
    [:data content]))

(defn- transform-stacktrace
  "Transform the :S node from Instaparse to Throwable->map."
  [& causes]
  (let [root (last causes)]
    {:cause (:message root)
     :data (:data root)
     :trace (:trace (first causes))
     :via (mapv (fn [{:keys [data type message trace]}]
                  (cond-> {:at (first trace)
                           :message message
                           :type type
                           :trace trace}
                    data (assoc :data data)))
                causes)}))

(defn- transform-exception
  "Transform a :exception node from Instaparse to Throwable->map."
  [& exceptions]
  (reduce (fn [m [k v]] (assoc m k v)) {} exceptions))

(def ^:private transform-file
  "Transform a :file node from Instaparse to Throwable->map."
  (partial apply str))

(def ^:private transform-class
  "Transform a :class node from Instaparse to Throwable->map."
  (comp symbol (partial apply str)))

(defn- transform-message
  "Transform a :message node from Instaparse to Throwable->map."
  [& content]
  [:message (apply str content)])

(def ^:private transform-number
  "Transform a :number node from Instaparse to Throwable->map."
  (comp edn/read-string (partial apply str)))

(defn- transform-trace
  "Transform a :trace node from Instaparse to Throwable->map."
  [& frames]
  [:trace (vec frames)])

(def ^:private transformations
  "The Instaparse `clojure.repl/pst` transformations."
  {:S transform-stacktrace
   :call vector
   :class transform-class
   :data transform-data
   :exception transform-exception
   :file transform-file
   :message transform-message
   :method symbol
   :number transform-number
   :simple-name str
   :simple-symbol symbol
   :trace transform-trace})

(defn parse-stacktrace
  "Parse the `stacktrace` string in the Aviso format."
  [stacktrace]
  (try (let [result (util/parse-try parser stacktrace stacktrace-start-regex)
             failure (insta/get-failure result)]
         (if (or (nil? result) failure)
           (cond-> {:error :incorrect
                    :type :incorrect-input
                    :input stacktrace}
             failure (assoc :failure failure))
           (-> (insta/transform transformations result)
               (assoc :product :pst))))
       (catch Exception e
         {:error :unsupported
          :type :input-not-supported
          :input stacktrace
          :exception e})))