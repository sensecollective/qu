(ns ^:integration integration.test.cache
  (:require [clojure.test :refer :all]
            [cfpb.qu.test-util :refer :all]
            [cfpb.qu.data :as data]
            [cfpb.qu.query :as q]
            [cfpb.qu.cache :as c]
            [cfpb.qu.loader :as loader]
            [monger.core :as mongo]
            [monger.collection :as coll]
            [monger.conversion :as conv]
            [monger.db :as db]))

(def db "integration_test")
(def coll "incomes")
(def qmap {:dataset db
           :slice coll
           :select "state_abbr, COUNT()"
           :group "state_abbr"})
(def ^:dynamic cache nil)
(def ^:dynamic worker nil)
(def worker-agent (atom nil))
(def ^:dynamic query nil)
(def ^:dynamic agg nil)


(defn run-all-jobs [worker]
  (reset! worker-agent (c/start-worker worker))
  (await @worker-agent)
  (swap! worker-agent c/stop-worker))

(defn mongo-setup
  [test]
  (data/connect-mongo)
  (loader/load-dataset db)
  (binding [cache (c/create-query-cache)]
    (db/drop-db (:database cache))
    (let [query (q/prepare (q/make-query qmap))]
      (binding [worker (c/create-worker cache)
                agg (q/mongo-aggregation query)]
        (test)))))

(use-fixtures :once mongo-setup)

(deftest ^:integration test-cache
  (testing "the default cache uses the query-cache db"
    (does= (str (:database cache)) "query_cache"))

  (testing "you can use other databases"
    (does= (str (:database (c/create-query-cache "cashhhh")))
           "cashhhh"))
  
  (testing "it can be wiped"
    (data/get-aggregation db coll agg)
    (run-all-jobs worker)
    (is (coll/exists? (:database cache) (:to agg)))

    (c/wipe-cache cache)
    (is (not (coll/exists? (:database cache) (:to agg))))))

(deftest ^:integration test-cleaning-cache
  (testing "it can be cleaned"
    (data/get-aggregation db coll agg)
    (run-all-jobs worker)
    (is (coll/exists? (:database cache) (:to agg)))

    (c/clean-cache cache (constantly [(:to agg)]))
    (is (not (coll/exists? (:database cache) (:to agg)))))

  (testing "by default, it cleans nothing"
    (data/get-aggregation db coll agg)
    (run-all-jobs worker)
    (is (coll/exists? (:database cache) (:to agg)))

    (c/clean-cache cache)
    (is (coll/exists? (:database cache) (:to agg))))

  (testing "it runs cleaning operations as part of the worker cycle"
    (let [cleanups (atom 0)
          cache (c/create-query-cache "query_cache" (fn [_] (swap! cleanups inc)))
          worker (c/create-worker cache)]                 
      (run-all-jobs worker)
      (does= @cleanups 1))))

(deftest ^:integration test-add-to-queue
  (testing "it adds a document to jobs"
    (c/clean-cache cache (constantly [(:to agg)]))    
    (does-contain (conv/from-db-object (c/add-to-queue cache agg) true)
                  {:_id (:to agg) :status "unprocessed"})))

(deftest ^:integration test-worker
  (testing "it will process jobs"
    (c/clean-cache cache (constantly [(:to agg)]))    
    (does-contain (data/get-aggregation db coll agg) {:data :computing})
             
    (run-all-jobs worker)
    (does-not= (:data (data/get-aggregation db coll agg)) :computing)))

;; (run-tests)