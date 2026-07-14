(ns logging.store
  "SSoT for the community-logging coordination actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every `cloud-itonami-isic-*` actor in this fleet uses.

  Scope note: unlike some sibling actors, this build ships a single
  `MemStore` backend only (atom of EDN) -- the deterministic default
  for dev/tests/demo, no deps. Per docs/adr/0001-architecture.md
  Decision 1, this vertical is self-contained (no external logging
  capability library, no jurisdiction-scoped Datomic-parity
  requirement driving a second backend); a `langchain.db`-backed store
  can be added later behind the same protocol without changing any
  caller.

  Three kinds of entity live here:
    - `sites`             -- the central entity. A logging site's
                             (parcel/unit) inventory/permit record.
                             `:verified?` marks whether the site's own
                             boundary/site conditions have actually
                             been surveyed (never inferred from a
                             routine intake patch); `:permit-status`/
                             `:allowable-cut-m3`/`:harvested-to-date-
                             m3` track the site's own permit ground
                             truth.
    - `field-operations`  -- a scheduled felling/skidding/hauling
                             DRAFT against a site (`logging.registry`'s
                             `register-field-operation`). Dedicated
                             `:scheduled?` double-schedule guard (never
                             a `:status` value -- the same discipline
                             every prior governor's guards establish,
                             informed by `cloud-itonami-isic-6492`'s
                             status-lifecycle bug, ADR-2607071320).
    - `supply-orders`     -- a proposed fuel/equipment/permit-fee
                             procurement DRAFT (`logging.registry`'s
                             `register-supply-order`).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which site was logged, which field
  operation was scheduled against a verified/permitted site, which
  supply order was placed and at what independently-recomputed total,
  approved by whom, which safety concern was flagged' is always a
  query over an immutable log -- the audit trail a logging cooperative
  or landowner trusting this coordinator needs."
  (:require [logging.registry :as registry]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (field-operation [s id])
  (all-field-operations [s])
  (supply-order [s id])
  (safety-concerns [s] "the append-only safety-concern log")
  (ledger [s])
  (operation-history [s] "the append-only field-operation-schedule history (logging.registry drafts)")
  (order-history [s] "the append-only supply-order history (logging.registry drafts)")
  (next-operation-sequence [s] "next field-operation-number sequence")
  (next-order-sequence [s] "next supply-order-number sequence")
  (field-operation-already-scheduled? [s operation-id] "has this field operation already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-sites []
  {"site-001" {:id "site-001" :parcel "North Ridge Unit 4" :species "Douglas Fir"
               :area-ha 40.0 :verified? true :permit-status :issued
               :allowable-cut-m3 5000.0 :harvested-to-date-m3 1200.0
               :last-assessed "2026-06-01"}
   "site-002" {:id "site-002" :parcel "East Hollow Unit 2" :species "Sitka Spruce"
               :area-ha 22.0 :verified? true :permit-status :issued
               :allowable-cut-m3 800.0 :harvested-to-date-m3 700.0
               :last-assessed "2026-06-01"}
   "site-003" {:id "site-003" :parcel "South Basin Unit 1" :species "Western Hemlock"
               :area-ha 55.0 :verified? false :permit-status :pending
               :allowable-cut-m3 6000.0 :harvested-to-date-m3 0.0
               :last-assessed "2026-05-15"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-field-operation!
  "Backend-agnostic `:field-operation/schedule` -- drafts the field-
  operation-schedule record via `logging.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s operation-id site-id]
  (let [seq-n (next-operation-sequence s)
        result (registry/register-field-operation operation-id site-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :operation-number (get result "operation_number")}}))

(defn- propose-supply-order!
  "Backend-agnostic `:supply-order/propose` -- drafts the supply-order
  record via `logging.registry` and returns {:result .. :patch ..} for
  the caller to persist."
  [s order-id]
  (let [seq-n (next-order-sequence s)
        result (registry/register-supply-order order-id seq-n)]
    {:result result
     :patch {:order-number (get result "order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (field-operation [_ id] (get-in @a [:field-operations id]))
  (all-field-operations [_] (sort-by :id (vals (:field-operations @a))))
  (supply-order [_ id] (get-in @a [:supply-orders id]))
  (safety-concerns [_] (:safety-concerns @a))
  (ledger [_] (:ledger @a))
  (operation-history [_] (:operation-history @a))
  (order-history [_] (:order-history @a))
  (next-operation-sequence [_] (:operation-sequence @a 0))
  (next-order-sequence [_] (:order-sequence @a 0))
  (field-operation-already-scheduled? [_ operation-id]
    (boolean (get-in @a [:field-operations operation-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :site/upsert)
      (swap! a update-in [:sites (first path)] merge (assoc value :id (first path)))

      (= effect :field-operation/schedule)
      (let [operation-id (first path)
            site-id (:site-id value)
            {:keys [result patch]} (schedule-field-operation! s operation-id site-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :operation-sequence (fnil inc 0))
                       (update-in [:field-operations operation-id] merge (assoc value :id operation-id) patch)
                       (update :operation-history registry/append result)
                       (update-in [:sites site-id :harvested-to-date-m3]
                                  (fn [prev]
                                    (if (= :felling (:operation-type value))
                                      (+ (double (or prev 0.0)) (double (or (:volume-m3 value) 0.0)))
                                      (or prev 0.0)))))))
        result)

      (= effect :safety-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :safety-concerns conj concern)
        concern)

      (= effect :supply-order/propose)
      (let [order-id (first path)
            {:keys [result patch]} (propose-supply-order! s order-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :order-sequence (fnil inc 0))
                       (update-in [:supply-orders order-id] merge (assoc value :id order-id) patch)
                       (update :order-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `forestry`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:sites {} :field-operations {} :supply-orders {}
                      :records {} :safety-concerns []
                      :ledger [] :operation-sequence 0 :operation-history []
                      :order-sequence 0 :order-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained site set -- one
  verified+permitted site with allowance remaining (schedulable), one
  verified+permitted site that is nearly at its allowable cut (a
  small felling proposal blows through the allowance -- HARD hold),
  one UNVERIFIED/pending-permit site (blocks any field-operation
  scheduling against it) -- so the actor + demo + tests run offline.
  Returns `s` (thread-friendly with `->`)."
  [s]
  (with-sites s (sample-sites))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
