(ns logging.store
  "Store -- manages SSoT records (site/permit data, harvest records) and
  append-only audit ledger. Two independent concerns:
  1. SSoT = current state of records (site status, harvest volumes, etc.)
  2. Audit ledger = immutable trace of all decisions

  A record is only written to SSoT via commit-record!. The ledger is never
  modified, only appended. This ensures traceability and auditability.")

(defprotocol Store
  "Protocol for logging operation stores"
  (get-records [this] "Fetch all SSoT records")
  (commit-record! [this record] "Write a record to SSoT")
  (get-ledger [this] "Fetch the append-only audit ledger")
  (append-ledger! [this entry] "Append an entry to the audit ledger"))

(defn mem-store
  "Returns an in-memory store for testing and demos"
  []
  (let [state (atom {:records {} :ledger []})]
    (reify Store
      (get-records [this]
        (:records @state))
      (commit-record! [this record]
        (let [id (or (:id record) (:subject record))]
          (swap! state assoc-in [:records id] record)))
      (get-ledger [this]
        (:ledger @state))
      (append-ledger! [this entry]
        (swap! state update :ledger conj entry)))))

(defn sample-data!
  "Populate a store with sample site/permit records for demos and tests"
  [store]
  (doseq [id ["site-001" "site-001-harvest" "crew-dispatch-001" "shipment-001"
              "felling-hazard-detection"]]
    (commit-record! store
      {:id id
       :type :site-permit
       :status :verified
       :ts (System.currentTimeMillis)}))
  store)
