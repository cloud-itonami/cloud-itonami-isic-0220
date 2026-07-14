(ns logging.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean site through
  intake -> field-operation scheduling (escalate/approve) -> safety-
  concern flag (escalate/approve) -> supply-order proposal
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  field-operation scheduled against an UNVERIFIED site, a felling
  scheduled against a site whose permit is only PENDING, a felling
  proposal that would exceed the site's own permitted allowance, a
  proposal that tries to FINALIZE a harvest-cut plan (permanently
  blocked, no override), a double-schedule of the same field
  operation, a harvest-record patch with a fabricated species, and a
  supply order whose claimed total doesn't match its own line items --
  plus one supply order that is CLEAN but exceeds the cost threshold,
  which escalates rather than auto-commits.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [logging.store :as store]
            [logging.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :logging-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-harvest-record site-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-harvest-record :effect :propose :subject "site-001"
                        :patch {:species :douglas-fir :last-assessed "2026-07-14"}}
                       coordinator))

    (println "== schedule-field-operation op-1 on site-001 (verified, permitted, skidding -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-field-operation :effect :propose :subject "op-1"
                       :value {:site-id "site-001" :operation-type :skidding
                               :scheduled-date "2026-08-01" :finalize? false}}
                      coordinator)]
      (println r)
      (println "-- human logging manager approves --")
      (println (approve! actor "t2")))

    (println "== schedule-field-operation op-2 on site-001 (verified, permitted, felling DRAFT within allowance -- escalates, approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :schedule-field-operation :effect :propose :subject "op-2"
                       :value {:site-id "site-001" :operation-type :felling
                               :volume-m3 500.0
                               :scheduled-date "2026-09-01" :finalize? false}}
                      coordinator)]
      (println r)
      (println "-- human logging manager approves --")
      (println (approve! actor "t3")))

    (println "== flag-safety-concern concern-1 on site-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:site-id "site-001" :severity :moderate
                               :description "急傾斜地での降雨後の地盤緩み"}}
                      coordinator)]
      (println r)
      (println "-- human logging manager approves --")
      (println (approve! actor "t4")))

    (println "== order-supplies order-1 (clean, matching total, below threshold -- escalates, approve) ==")
    (let [r (exec-op actor "t5"
                      {:op :order-supplies :effect :propose :subject "order-1"
                       :value {:items [{:name "chainsaw-fuel" :qty 200 :unit-cost 7.5}
                                       {:name "bar-oil" :qty 100 :unit-cost 2.5}]
                               :claimed-total 1750.0}}
                      coordinator)]
      (println r)
      (println "-- human purchasing approver approves --")
      (println (approve! actor "t5")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-harvest-record with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t6"
                       {:op :log-harvest-record :effect :direct-write :subject "site-001"
                        :patch {:species :douglas-fir}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t7"
                       {:op :dispatch-feller-buncher :effect :propose :subject "site-001"}
                       coordinator))

    (println "== schedule-field-operation op-3 on site-003 (UNVERIFIED, permit pending -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :schedule-field-operation :effect :propose :subject "op-3"
                        :value {:site-id "site-003" :operation-type :skidding
                                :scheduled-date "2026-08-01" :finalize? false}}
                       coordinator))

    (println "== schedule-field-operation op-4 felling on site-002 (100m3 would exceed allowance 800 vs harvested 700 -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :schedule-field-operation :effect :propose :subject "op-4"
                        :value {:site-id "site-002" :operation-type :felling
                                :volume-m3 150.0
                                :scheduled-date "2026-08-01" :finalize? false}}
                       coordinator))

    (println "== schedule-field-operation op-5 on site-001 with :finalize? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-field-operation :effect :propose :subject "op-5"
                        :value {:site-id "site-001" :operation-type :felling
                                :volume-m3 100.0
                                :scheduled-date "2026-09-01" :finalize? true}}
                       coordinator))

    (println "== schedule-field-operation op-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-field-operation :effect :propose :subject "op-1"
                        :value {:site-id "site-001" :operation-type :skidding
                                :scheduled-date "2026-08-01" :finalize? false}}
                       coordinator))

    (println "== log-harvest-record site-001 with a fabricated species -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-harvest-record :effect :propose :subject "site-001"
                        :patch {:species :unobtainium-pine}}
                       coordinator))

    (println "== order-supplies order-2 (claimed 1000.0 vs recompute 750.0 -> HARD hold) ==")
    (println (exec-op actor "t13"
                       {:op :order-supplies :effect :propose :subject "order-2"
                        :value {:items [{:name "hydraulic-fluid" :qty 100 :unit-cost 7.5}]
                                :claimed-total 1000.0}}
                       coordinator))

    (println "== order-supplies order-3 (clean but exceeds cost threshold -> ESCALATE, not HOLD -- approve) ==")
    (let [r (exec-op actor "t14"
                      {:op :order-supplies :effect :propose :subject "order-3"
                       :value {:items [{:name "replacement-skidder-winch" :qty 2 :unit-cost 4000.0}]
                               :claimed-total 8000.0}}
                      coordinator)]
      (println r)
      (println "-- human purchasing approver approves --")
      (println (approve! actor "t14")))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft field-operation records ==")
    (doseq [r (store/operation-history db)] (println r))

    (println "\n== draft supply-order records ==")
    (doseq [r (store/order-history db)] (println r))))
