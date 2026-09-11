(ns logging.governor
  "Logging Coordination Governor -- the independent compliance layer
  that earns the LoggingAdvisor the right to commit. The advisor has
  no notion of whether a site it wants to schedule a field operation
  against has actually been surveyed/verified and permitted, whether a
  felling proposal secretly tries to FINALIZE (rather than merely
  draft-schedule) a harvest-cut plan, whether a felling proposal's own
  claimed volume would blow through the site's own permitted
  allowable cut, whether a supply order's own claimed total actually
  equals the sum of its own line items, or when an act stops being a
  coordination proposal and becomes direct felling/skidding-equipment
  control, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:logging-coordination-governor`
  (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:equipment/actuate`
                                       or `:harvest/finalize`) is the
                                       'direct felling/skidding-
                                       equipment control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Harvest-finalize blocked    -- for `:schedule-field-operation`,
                                       does the proposal's own `:value`
                                       declare `:finalize? true`?
                                       Finalizing a harvest-cut plan is
                                       this actor's other permanent
                                       scope boundary (see README `What
                                       this actor does NOT do`) --
                                       HARD, PERMANENT, unconditional.
                                       NO phase and NO human approval
                                       can ever override this (see
                                       `logging.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Site not verified/permitted -- for `:schedule-field-operation`,
                                       INDEPENDENTLY verify the
                                       referenced site's own
                                       `:verified?` AND `:permit-
                                       status` are both ready
                                       (`logging.registry/site-permit-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/permit status.
                                       Grounded in this blueprint's own
                                       HARD invariant ('site/permit
                                       record must be independently
                                       verified/registered before any
                                       action'): field operations must
                                       never be scheduled against a
                                       site whose site conditions have
                                       not actually been surveyed or
                                       whose permit has not actually
                                       been issued.
    6. Permit allowance exceeded   -- for a `:felling`
                                       `:schedule-field-operation`,
                                       INDEPENDENTLY recompute whether
                                       the site's own recorded
                                       `:harvested-to-date-m3` plus the
                                       proposal's own claimed
                                       `:volume-m3` would exceed the
                                       site's own `:allowable-cut-m3`
                                       (`logging.registry/permit-
                                       allowance-exceeded?`) -- ground
                                       truth from the site's own
                                       permanent permit fields, never a
                                       self-reported volume claim. Also
                                       part of the 'site/permit record'
                                       HARD invariant: a permit's own
                                       recorded allowance is as much a
                                       permit-record fact as its issued
                                       status.
    7. Already scheduled           -- for `:schedule-field-operation`,
                                       refuses to schedule the SAME
                                       field-operation record twice, off
                                       a dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Invalid species             -- for `:log-harvest-record`, if the
                                       patch declares a `:species`
                                       outside the closed known set
                                       (`logging.registry/species-
                                       valid?`), the harvest record is
                                       rejected rather than let a
                                       fabricated species through.
    9. Supply-order total mismatch -- for `:order-supplies`,
                                       INDEPENDENTLY recompute whether
                                       the order's own `:claimed-total`
                                       equals the sum of its own
                                       `:items` (`logging.registry/
                                       order-total-matches-claim?`) --
                                       an honest reapplication of the
                                       SAME ground-truth-recompute
                                       discipline every sibling actor's
                                       own cost/total-matching check
                                       establishes.
   10. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`), OR (for
                                       `:order-supplies`) the order's
                                       own independently-recomputed
                                       total exceeds `logging.registry/
                                       supply-order-cost-threshold` --
                                       escalate to a human logging
                                       manager/purchasing approver.
                                       SOFT: the human may approve."
  (:require [logging.registry :as registry]
            [logging.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-harvest-record :schedule-field-operation
    :flag-safety-concern :order-supplies})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct felling/
  skidding-equipment-control or harvest-finalize effect."
  #{:site/upsert :field-operation/schedule
    :safety-concern/flag :supply-order/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct felling/skidding-equipment control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は伐採・集材機材の直接操作/伐採計画確定に該当する可能性があり、恒久的に禁止")}]))

(defn- harvest-finalize-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-field-operation`
  proposal whose own `:value` declares `:finalize? true` is attempting
  to finalize a harvest-cut plan directly -- this actor may only ever
  propose/schedule a DRAFT, never finalize one. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-field-operation)
             (true? (:finalize? (:value proposal))))
    [{:rule :harvest-finalize-blocked
      :detail "伐採計画の確定(finalize)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- site-not-verified-violations
  "For `:schedule-field-operation`, INDEPENDENTLY verify the
  referenced site exists and is both `:verified?` AND permit-issued --
  never trust the advisor's own report. This is the HARD invariant
  ('site/permit record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [site-id (:site-id (:value proposal))
          s (and site-id (store/site st site-id))]
      (when-not (and s (registry/site-permit-ready? s))
        [{:rule :site-not-verified
          :detail (str site-id " は未検証または許可未取得、もしくは存在しない -- 検証済み・許可済みサイト記録が無い状態での作業予定提案")}]))))

(defn- permit-allowance-exceeded-violations
  "For a `:felling` `:schedule-field-operation`, INDEPENDENTLY
  recompute whether the site's own recorded harvested-to-date volume
  plus the proposal's own claimed volume would exceed the permit's
  own allowable-cut -- ground truth from the site's own permanent
  permit fields."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [{:keys [site-id operation-type volume-m3]} (:value proposal)
          s (and site-id (store/site st site-id))]
      (when (and s (= operation-type :felling)
                 (registry/permit-allowance-exceeded? s volume-m3))
        [{:rule :permit-allowance-exceeded
          :detail (str site-id " の許可済み伐採量(" (:allowable-cut-m3 s)
                       "m3)を、既存実績(" (:harvested-to-date-m3 s 0.0)
                       "m3)+今回申請(" volume-m3 "m3)が超過")}]))))

(defn- already-scheduled-violations
  "For `:schedule-field-operation`, refuses to schedule the SAME
  field-operation record twice, off a dedicated `:scheduled?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-field-operation)
    (when (store/field-operation-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-species-violations
  "For `:log-harvest-record`, if the patch declares a `:species`
  outside the closed known set, reject rather than let a fabricated
  species through."
  [{:keys [op]} proposal]
  (when (= op :log-harvest-record)
    (let [species (:species (:value proposal))]
      (when (and (some? species) (not (registry/species-valid? species)))
        [{:rule :invalid-species
          :detail (str species " は既知の species 値ではない")}]))))

(defn- order-total-mismatch-violations
  "For `:order-supplies`, INDEPENDENTLY recompute whether the order's
  own claimed total equals the sum of its own line items via
  `logging.registry/order-total-matches-claim?` -- needs no store
  lookup at all, an honest reapplication of the same discipline every
  sibling actor's own cost/total-matching check establishes."
  [{:keys [op]} proposal]
  (when (= op :order-supplies)
    (let [order (:value proposal)]
      (when-not (registry/order-total-matches-claim? order)
        [{:rule :order-total-mismatch
          :detail (str "申告合計(" (:claimed-total order)
                       ")が独立再計算値(" (registry/order-total order) ")と一致しない")}]))))

(defn check
  "Censors a LoggingAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (harvest-finalize-blocked-violations request proposal)
                           (site-not-verified-violations request proposal st)
                           (permit-allowance-exceeded-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-species-violations request proposal)
                           (order-total-mismatch-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        high-cost-order? (and (= (:op request) :order-supplies)
                               (registry/order-exceeds-threshold? (:value proposal)))
        stakes? (or (boolean (high-stakes (:stake proposal))) high-cost-order?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
