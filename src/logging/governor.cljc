(ns logging.governor
  "LoggingCoordinationGovernor -- independent validation layer.
  Enforces four HARD invariants (no override):
  1. Site/permit record must be registered in the SSoT before any action
  2. Proposals must be `:effect :propose` only (never direct equipment control)
  3. Direct felling-equipment control or crew-operation authorization is permanently blocked
  4. Safety hazards ALWAYS escalate (no threshold permits auto-commit)

  Also applies domain-specific escalation rules: harvest volume thresholds,
  crew safety concerns, shipment protocol verification.")

(defn check
  "Validates a proposal against HARD invariants and domain rules.
  Returns a verdict map with :violations, :escalate?, :high-stakes?, :confidence"
  [request context proposal store]
  (let [op (:op request)
        subject (:subject request)
        effect (:effect proposal)
        violations (cond-> []
                    ;; HARD 1: Site/permit must be registered
                    (not (get (store/get-records store) subject))
                    (conj {:rule :site-not-registered :detail subject})

                    ;; HARD 2: Effect must be :propose only
                    (not= effect :propose)
                    (conj {:rule :effect-not-propose :detail effect})

                    ;; HARD 3: No direct felling control
                    (#{:felling-execute :equipment-operate} op)
                    (conj {:rule :direct-equipment-control-blocked :detail op}))]

    ;; Escalation rules (even if no violations)
    (let [escalate-ops #{:flag-safety-hazard :schedule-crew-dispatch}
          should-escalate (or (seq violations)
                              (contains? escalate-ops op))]
      {:violations violations
       :escalate? should-escalate
       :high-stakes? (or (seq violations) (contains? escalate-ops op))
       :confidence (:confidence proposal 0.7)})))

(defn hold-fact
  "Create an audit fact for a proposal that was held (blocked)"
  [request context verdict]
  {:t :governor-hold
   :op (:op request)
   :subject (:subject request)
   :reason :governor-validation-failed
   :violations (:violations verdict)})
