(ns logging.registry-test
  (:require [clojure.test :refer [deftest is]]
            [logging.registry :as r]))

;; ----------------------------- site-verified? / permit-issued? / site-permit-ready? -----------------------------

(deftest site-is-verified-when-flagged
  (is (true? (r/site-verified? {:id "s1" :verified? true}))))

(deftest site-is-not-verified-when-false-or-missing
  (is (false? (r/site-verified? {:id "s1" :verified? false})))
  (is (false? (r/site-verified? {:id "s1"}))))

(deftest permit-is-issued-when-flagged
  (is (true? (r/permit-issued? {:permit-status :issued}))))

(deftest permit-is-not-issued-when-pending-or-missing
  (is (false? (r/permit-issued? {:permit-status :pending})))
  (is (false? (r/permit-issued? {}))))

(deftest site-permit-ready-requires-both
  (is (true? (r/site-permit-ready? {:verified? true :permit-status :issued})))
  (is (false? (r/site-permit-ready? {:verified? true :permit-status :pending})))
  (is (false? (r/site-permit-ready? {:verified? false :permit-status :issued})))
  (is (false? (r/site-permit-ready? {}))))

;; ----------------------------- permit-allowance-exceeded? -----------------------------

(deftest small-felling-within-allowance-does-not-exceed
  (is (false? (r/permit-allowance-exceeded?
               {:allowable-cut-m3 5000.0 :harvested-to-date-m3 1200.0} 500.0))))

(deftest felling-that-pushes-past-allowance-exceeds
  (is (true? (r/permit-allowance-exceeded?
              {:allowable-cut-m3 800.0 :harvested-to-date-m3 700.0} 150.0))))

(deftest felling-exactly-at-allowance-does-not-exceed
  (is (false? (r/permit-allowance-exceeded?
               {:allowable-cut-m3 800.0 :harvested-to-date-m3 700.0} 100.0))
      "exactly at allowance is not over, only strictly beyond"))

(deftest missing-allowance-is-not-flagged-exceeded
  (is (false? (r/permit-allowance-exceeded? {} 100.0)))
  (is (false? (r/permit-allowance-exceeded? {:allowable-cut-m3 800.0} nil))))

;; ----------------------------- species-valid? -----------------------------

(deftest known-species-are-valid
  (doseq [s [:douglas-fir :western-hemlock :sitka-spruce :ponderosa-pine
             :loblolly-pine :red-alder :western-redcedar]]
    (is (r/species-valid? s))))

(deftest fabricated-species-is-invalid
  (is (not (r/species-valid? :unobtainium-pine)))
  (is (not (r/species-valid? nil))))

;; ----------------------------- order-total / order-total-matches-claim? -----------------------------

(deftest order-total-is-a-flat-sum-of-line-items
  (is (= 1750.0 (r/order-total {:items [{:qty 200 :unit-cost 7.5}
                                        {:qty 100 :unit-cost 2.5}]}))))

(deftest order-total-empty-items-is-zero
  (is (= 0.0 (r/order-total {:items []})))
  (is (= 0.0 (r/order-total {}))))

(deftest matches-when-claim-equals-recompute
  (is (r/order-total-matches-claim?
       {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 750.0})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/order-total-matches-claim?
            {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 1000.0}))))

(deftest missing-claimed-total-never-matches
  (is (not (r/order-total-matches-claim? {:items [{:qty 1 :unit-cost 1.0}]}))))

;; ----------------------------- order-exceeds-threshold? -----------------------------

(deftest order-below-threshold-does-not-exceed
  (is (not (r/order-exceeds-threshold? {:items [{:qty 200 :unit-cost 7.5}]}))))

(deftest order-above-threshold-exceeds
  (is (r/order-exceeds-threshold? {:items [{:qty 2 :unit-cost 4000.0}]})))

;; ----------------------------- register-field-operation -----------------------------

(deftest field-operation-is-a-draft-not-a-real-dispatch
  (let [result (r/register-field-operation "op-1" "site-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest field-operation-assigns-operation-number
  (let [result (r/register-field-operation "op-1" "site-001" 7)]
    (is (= (get result "operation_number") "FOP-000007"))
    (is (= (get-in result ["record" "operation_id"]) "op-1"))
    (is (= (get-in result ["record" "site_id"]) "site-001"))
    (is (= (get-in result ["record" "kind"]) "field-operation-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest field-operation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "" "site-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "op-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-field-operation "op-1" "site-001" -1))))

;; ----------------------------- register-supply-order -----------------------------

(deftest supply-order-is-a-draft-not-a-real-purchase
  (let [result (r/register-supply-order "order-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest supply-order-assigns-order-number
  (let [result (r/register-supply-order "order-1" 7)]
    (is (= (get result "order_number") "ORD-000007"))
    (is (= (get-in result ["record" "order_id"]) "order-1"))
    (is (= (get-in result ["record" "kind"]) "supply-order-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest supply-order-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supply-order "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supply-order "order-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-field-operation "op-1" "site-001" 0)
        hist (r/append [] c1)
        c2 (r/register-field-operation "op-2" "site-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "FOP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "FOP-000001" (get-in hist2 [1 "record_id"])))))
