(ns logging.sim
  "Demo driver for the LoggingOperationActor. Walk through a few logging
  scenarios: harvest record logging, crew dispatch scheduling, safety hazard flagging."
  (:require [logging.operation :as op]
            [logging.store :as store]
            [logging.phase :as phase]))

(defn -main [& _args]
  (let [s (-> (store/mem-store) (store/sample-data!))
        actor (op/build s)]

    (println "=== LoggingOperationActor Demo ===\n")

    ;; Scenario 1: Log a harvest record (data logging proposal)
    (println "Scenario 1: Proposing harvest record log")
    (let [request {:op :log-harvest-record
                   :effect :propose
                   :subject "site-001-harvest"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Ledger entry count:" (count (store/get-ledger s)))
      (println))

    ;; Scenario 2: Schedule crew dispatch (always requires approval)
    (println "Scenario 2: Proposing crew dispatch schedule")
    (let [request {:op :schedule-crew-dispatch
                   :effect :propose
                   :subject "crew-dispatch-001"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Reason:" (first (filter #(= :approval-requested (:t %))
                                           (store/get-ledger s))))
      (println))

    ;; Scenario 3: Safety hazard flagging (always escalates)
    (println "Scenario 3: Flagging safety hazard (always escalates)")
    (let [request {:op :flag-safety-hazard
                   :effect :propose
                   :subject "felling-hazard-detection"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Escalation reason: Safety hazard requires human review")
      (println "  Ledger entries:" (count (store/get-ledger s)))
      (println))

    ;; Scenario 4: Timber shipment coordination
    (println "Scenario 4: Proposing timber shipment coordination")
    (let [request {:op :coordinate-timber-shipment
                   :effect :propose
                   :subject "shipment-001"}
          context {:actor-id "logging-actor-01"
                   :role :coordinator
                   :phase phase/default-phase}
          result (-> actor (.invoke {:request request :context context}))]
      (println "  Disposition:" (:disposition result))
      (println "  Ledger entries:" (count (store/get-ledger s)))
      (println))

    (println "=== Demo Complete ===")
    (println "\nFinal audit ledger:")
    (doseq [entry (store/get-ledger s)]
      (println "  " entry))))
