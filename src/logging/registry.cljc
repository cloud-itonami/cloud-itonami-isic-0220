(ns logging.registry
  "Pure-function domain logic for the community-logging coordination
  actor -- site/permit verification, permit-allowance recompute,
  species validation, supply-order budget verification, and draft
  field-operation/supply-order record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/logging`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `logging.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `forestry.registry/stand-immature-for-harvest?`,
  `chemmineops.registry/royalty-matches-claim?`): never trust a
  proposal's own self-reported total/verdict when the inputs needed to
  recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real logging-operations system. It builds the DRAFT
  record a logging coordinator would keep (a scheduled field
  operation, a supply order), not the act of dispatching felling/
  skidding equipment, executing a felling cut, or placing a real
  purchase order (this actor NEVER does either -- see README `What
  this actor does NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-species
  "The closed set of species values a harvest record may declare.
  Anything else is a fabricated/unrecognized species -- the governor
  HARD-holds rather than let an invented species pass through."
  #{:douglas-fir :western-hemlock :sitka-spruce :ponderosa-pine
    :loblolly-pine :red-alder :western-redcedar})

(def supply-order-cost-threshold
  "Supply orders whose independently-recomputed total exceeds this
  amount always escalate to a human logging manager/purchasing
  approver, regardless of confidence -- see `logging.governor`'s
  high-stakes gate."
  5000.0)

;; ----------------------------- site/permit checks -----------------------------

(defn site-verified?
  "Ground-truth check: has `site`'s own record been marked verified
  (i.e. its boundary/site conditions have actually been surveyed and
  registered in the SSoT, not merely logged from an unverified
  intake patch)? A pure predicate over the site's own permanent
  field -- no proposal inspection needed."
  [site]
  (true? (:verified? site)))

(defn permit-issued?
  "Ground-truth check: does `site`'s own permit record carry an
  ISSUED status? Scheduling felling/skidding/hauling against a site
  whose permit is not on file and issued is the exact scope violation
  this actor's HARD invariant ('site/permit record must be
  independently verified/registered before any action') exists to
  block."
  [site]
  (= :issued (:permit-status site)))

(defn site-permit-ready?
  "Combined ground-truth gate: the site must be both `verified?` AND
  carry an ISSUED permit before ANY field operation may be scheduled
  against it. Two independent facts on the site's own permanent
  record, neither inferred from the advisor's own rationale."
  [site]
  (and (site-verified? site) (permit-issued? site)))

(defn permit-allowance-exceeded?
  "Ground-truth check for a `:felling` field-operation proposal: would
  `harvested-so-far-m3` + `new-volume-m3` exceed `site`'s own recorded
  `:allowable-cut-m3` (the permit's own allowable annual cut, AAC)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the site's own permit record, the
  same shape every sibling actor's own cost/total-matching check
  uses. Skidding/hauling operations carry no volume of their own (the
  volume was already counted at felling time), so this check only
  ever applies to `:felling`."
  [site new-volume-m3]
  (let [allowance (:allowable-cut-m3 site)
        so-far (:harvested-to-date-m3 site 0.0)]
    (and (number? allowance)
         (number? new-volume-m3)
         (> (+ (double so-far) (double new-volume-m3)) (double allowance)))))

(defn species-valid?
  "Is `species` one of the closed, known species values? nil/blank is
  treated as invalid (a harvest-record patch must declare a real
  species, not omit it silently)."
  [species]
  (contains? valid-species species))

;; ----------------------------- supply-order checks -----------------------------

(defn order-total
  "The ground-truth total for `order`'s own `:items` (each `{:qty n
  :unit-cost c}`) -- independent of whatever `:claimed-total` the
  proposal itself carries."
  [{:keys [items]}]
  (reduce (fn [acc {:keys [qty unit-cost]}]
            (+ acc (* (double (or qty 0)) (double (or unit-cost 0)))))
          0.0
          items))

(defn order-total-matches-claim?
  "Does `order`'s own `:claimed-total` equal the independently
  recomputed `order-total`? An honest reapplication of the SAME
  ground-truth-recompute discipline every sibling actor's own cost/
  total-matching check establishes (e.g. `forestry.registry/order-
  total-matches-claim?`), reapplied to a fuel/equipment/permit-fee
  supply order rather than a seedling order."
  [{:keys [claimed-total] :as order}]
  (and (number? claimed-total)
       (== (double claimed-total) (order-total order))))

(defn order-exceeds-threshold?
  "Does `order`'s own independently-recomputed total exceed
  `supply-order-cost-threshold`? Computed from the order's own line
  items, never from a self-reported `:stake` or confidence value."
  [order]
  (> (order-total order) supply-order-cost-threshold))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human logging manager's/purchasing approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-field-operation
  "Validate + construct the FIELD-OPERATION SCHEDULING DRAFT -- a
  proposed felling/skidding/hauling date on a verified, permitted
  site. Pure function -- does not dispatch felling/skidding equipment
  or execute any field operation; it builds the RECORD a coordinator
  would keep. `logging.governor` independently re-verifies the site's
  own verified/permit ground truth, and permanently blocks any
  attempt to set `:finalize? true` on a harvest-cut plan (see README
  `Actuation`), before this is ever allowed to commit."
  [operation-id site-id sequence]
  (when-not (and operation-id (not= operation-id ""))
    (throw (ex-info "field-operation: operation_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "field-operation: site_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "field-operation: sequence must be >= 0" {})))
  (let [operation-number (str "FOP-" (zero-pad sequence 6))
        record {"record_id" operation-number
                "kind" "field-operation-schedule-draft"
                "operation_id" operation-id
                "site_id" site-id
                "immutable" true}]
    {"record" record "operation_number" operation-number
     "certificate" (unsigned-certificate "FieldOperationSchedule" operation-number operation-number)}))

(defn register-supply-order
  "Validate + construct the SUPPLY-ORDER DRAFT -- a proposed fuel/
  equipment/permit-fee procurement. Pure function -- does not place
  any real purchase order; it builds the RECORD a coordinator would
  keep. `logging.governor` independently re-verifies the order's own
  claimed-total against `order-total`, and escalates (never
  auto-commits) any order whose recomputed total exceeds
  `supply-order-cost-threshold`, before this is ever allowed to
  commit."
  [order-id sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "supply-order: order_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "supply-order: sequence must be >= 0" {})))
  (let [order-number (str "ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "supply-order-draft"
                "order_id" order-id
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "SupplyOrder" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
