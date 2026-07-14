(ns logging.phase
  "Phase gates for the logging operations actor: Phase 0->3 rollout.
  Each phase allows different operations to auto-commit vs. require approval.

  Phase 0 (Planning): Most operations require approval, no auto-commit
  Phase 1 (Assessment): Initial site assessments can auto-commit if clean
  Phase 2 (Coordination): Harvest/dispatch operations require approval
  Phase 3 (Operations): No auto-commit, all actuation requires human approval

  CRITICAL: Safety hazards ALWAYS escalate, regardless of phase.")

(def default-phase :phase-0)

(def phase-specs
  {:phase-0 {:name "Planning"
             :auto #{}  ; Nothing auto-commits in phase 0
             :requires-approval #{:log-harvest-record
                                  :schedule-crew-dispatch
                                  :flag-safety-hazard
                                  :coordinate-timber-shipment}}
   :phase-1 {:name "Assessment"
             :auto #{}  ; Assessment only if low-risk
             :requires-approval #{:log-harvest-record
                                  :schedule-crew-dispatch
                                  :flag-safety-hazard
                                  :coordinate-timber-shipment}}
   :phase-2 {:name "Coordination"
             :auto #{}
             :requires-approval #{:log-harvest-record
                                  :schedule-crew-dispatch
                                  :coordinate-timber-shipment}}
   :phase-3 {:name "Operations"
             :auto #{}
             :requires-approval #{:log-harvest-record
                                  :schedule-crew-dispatch
                                  :coordinate-timber-shipment}}})

(defn verdict->disposition [verdict]
  "Map governor verdict to base disposition."
  (if (seq (:violations verdict))
    :hold
    (if (:escalate? verdict)
      :escalate
      :commit)))

(defn gate [phase request base-disposition]
  "Apply phase-specific gating rules. Safety hazards ALWAYS escalate.
  Can only add caution, not remove it."
  (let [is-safety-hazard (= :flag-safety-hazard (:op request))]
    (case base-disposition
      :hold
      {:disposition :hold :reason nil}  ; Hard violations stay held

      :escalate
      {:disposition :escalate :reason nil}  ; Escalation stays escalated

      :commit
      ;; Commit: check if it's a safety hazard (ALWAYS escalate)
      ;; or check phase requirements
      (if is-safety-hazard
        {:disposition :escalate
         :reason "Safety hazards always require human review"}
        (let [phase-spec (get phase-specs phase default-phase)]
          (if (= phase :phase-1)
            ;; Phase 1: some low-risk ops can auto-commit
            {:disposition :commit :reason nil}
            ;; All other phases: require approval for actions
            {:disposition :escalate
             :reason (str "Phase " (:name phase-spec) " requires human approval")}))))))
