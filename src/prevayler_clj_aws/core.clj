(ns prevayler-clj-aws.core
  (:require
   [base64-clj.core :as base64]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [prevayler-clj.prevayler4 :refer [Prevayler]]
   [taoensso.nippy :as nippy]
   [prevayler-clj-aws.util :as util])
  (:import
   [clojure.lang IDeref]
   [java.io ByteArrayOutputStream Closeable]))

(defn- marshal [value]
  (-> (nippy/freeze value)
      base64/encode-bytes
      String.))

(defn- unmarshal [in]
  (-> (with-open [out (ByteArrayOutputStream.)]
        (io/copy in out)
        (.toByteArray out))
      base64/decode-bytes
      nippy/thaw))

(defn- snapshot-exists? [s3-cli bucket snapshot-path]
  (->> (util/aws-invoke s3-cli {:op :ListObjects
                                :request {:Bucket bucket
                                          :Prefix snapshot-path}})
       :Contents
       (some #(= snapshot-path (:Key %)))))

(defn- read-snapshot [s3-cli bucket snapshot-path]
  (if (snapshot-exists? s3-cli bucket snapshot-path)
    (-> (util/aws-invoke s3-cli {:op :GetObject
                                 :request {:Bucket bucket
                                           :Key snapshot-path}})
        :Body
        unmarshal)
    {:partkey 0}))

(defn- save-snapshot! [s3-cli bucket snapshot-path snapshot]
  (util/aws-invoke s3-cli {:op :PutObject
                           :request {:Bucket bucket
                                     :Key snapshot-path
                                     :Body (marshal snapshot)}}))

(defn- read-items [dynamo-cli table partkey page-size]
  (letfn [(read-page [exclusive-start-key]
            (let [result (util/aws-invoke
                          dynamo-cli
                          {:op :Query
                           :request {:TableName table
                                     :KeyConditionExpression "partkey = :partkey"
                                     :ExpressionAttributeValues {":partkey" {:S (str partkey)}}
                                     :Limit page-size
                                     :ExclusiveStartKey exclusive-start-key}})
                  {items :Items last-key :LastEvaluatedKey} result]
              (lazy-cat
               (map (comp unmarshal :B :content) items)
               (if (seq last-key)
                 (read-page last-key)
                 []))))]
    (read-page {:order {:N "0"} :partkey {:S (str partkey)}})))

(defn- restore-events! [dynamo-cli handler state-atom table partkey page-size]
  (let [items (read-items dynamo-cli table partkey page-size)]
    (doseq [[timestamp event] items]
      (swap! state-atom handler event timestamp))))

(defn- write-event! [dynamo-cli table partkey order event]
  (util/aws-invoke dynamo-cli {:op :PutItem
                               :request {:TableName table
                                         :Item {:partkey {:S (str partkey)}
                                                :order {:N (str order)}
                                                :content {:B (marshal event)}}}}))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn aws-opts]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)}}]
  (let [{:keys [dynamodb-client s3-client dynamodb-table snapshot-path s3-bucket page-size]
         :or {dynamodb-client (aws/client {:api :dynamodb})
              s3-client (aws/client {:api :s3})
              snapshot-path "snapshot"
              page-size 1000}} aws-opts
        {state :state old-partkey :partkey} (read-snapshot s3-client s3-bucket snapshot-path)
        state-atom (atom (or state initial-state))
        new-partkey (inc old-partkey)
        order-atom (atom 0)]

    (restore-events! dynamodb-client business-fn state-atom dynamodb-table old-partkey page-size)

    ; since s3 update is atomic, if saving snapshot fails next prevayler will pick the previous state
    ; and restore events from the previous partkey
    (save-snapshot! s3-client s3-bucket snapshot-path {:state @state-atom :partkey new-partkey})

    (reify
      Prevayler
      (handle! [this event]
        (locking this ; (I)solation: strict serializability.
          (let [timestamp (timestamp-fn)
                new-state (business-fn @state-atom event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.)
            (write-event! dynamodb-client dynamodb-table new-partkey (swap! order-atom inc) [timestamp event]) ; (D)urability
            (reset! state-atom new-state)))) ; (A)tomicity
      (timestamp [_] (timestamp-fn))

      IDeref (deref [_] @state-atom)

      Closeable (close [_] (aws/stop dynamodb-client)))))
