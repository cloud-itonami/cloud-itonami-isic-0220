(ns logging.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [logging.operation :as op]
            [logging.store :as store]
            [logging.phase :as phase]))

(deftest test-actor-builds
  (testing "LoggingOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-harvest-record-proposal
  (testing "Proposing a harvest record logs correctly"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :log-harvest-record
                   :effect :propose
                   :subject "site-001-harvest"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          initial-ledger-size (count (store/get-ledger s))
          result (-> actor (.invoke {:request request :context context}))
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (some? result)))))

(deftest test-crew-dispatch-scheduling
  (testing "Crew dispatch scheduling is proposed"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :schedule-crew-dispatch
                   :effect :propose
                   :subject "crew-dispatch-001"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result)))))

(deftest test-safety-hazard-escalation
  (testing "Safety hazards always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :flag-safety-hazard
                   :effect :propose
                   :subject "felling-hazard-detection"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result))
      ;; Verify escalation happened
      (is (= :escalate (:disposition result))))))

(deftest test-timber-shipment-proposal
  (testing "Timber shipment coordination proposal is submitted"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          request {:op :coordinate-timber-shipment
                   :effect :propose
                   :subject "shipment-001"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (is (some? result)))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "Records can be committed to store"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest test-safety-hazard-escalation-phase-independent
  (testing "Safety hazards escalate in all phases"
    (doseq [ph [:phase-0 :phase-1 :phase-2 :phase-3]]
      (let [s (-> (store/mem-store) (store/sample-data!))
            actor (op/build s)
            request {:op :flag-safety-hazard
                     :effect :propose
                     :subject "felling-hazard"}
            context {:actor-id "logging-actor-01"
                     :role :coordinator
                     :phase ph}
            result (-> actor (.invoke {:request request :context context}))]
        (is (= :escalate (:disposition result))
            (str "Safety hazard must escalate in phase " ph))))))
