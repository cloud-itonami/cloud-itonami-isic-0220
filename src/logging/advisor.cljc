(ns logging.advisor
  "LoggingAdvisor -- the *contained intelligence node* for the
  community-logging coordination actor.

  It normalizes harvest-record patches, drafts a felling/skidding/
  hauling field-operation scheduling proposal against a site, drafts a
  safety-concern flag, and drafts a fuel/equipment/permit-fee
  supply-order proposal. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a real felling/skidding-
  equipment dispatch, felling-cut execution, or purchase order. Every
  output is censored downstream by `logging.governor` before anything
  touches the SSoT -- see README `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `logging.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:site/upsert :field-operation/
                                 ; schedule :safety-concern/flag
                                 ; :supply-order/propose} propose-shaped
                                 ; effects, NEVER a direct felling/
                                 ; skidding-equipment-control or
                                 ; harvest-cut-finalize effect
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `logging.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [logging.registry :as registry]
            [logging.store :as store]
            [langchain.model :as model]))

(defn- log-harvest-record
  "Harvest-record intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the site's parcel, species,
  volume or verification status. High confidence, low stakes --
  administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "伐採記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :site/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-field-operation
  "Draft a felling/skidding/hauling scheduling proposal against a
  site. The advisor reports what it can see (site verified?/permit-
  status/allowance) in its rationale, but `logging.governor` NEVER
  trusts this report -- it independently re-derives verified?/permit-
  issued?/allowance-remaining from the site's own stored fields before
  any commit is possible."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        st (store/site db site-id)
        ready? (and st (registry/site-permit-ready? st))
        over-allowance? (and st (= :felling (:operation-type value))
                              (registry/permit-allowance-exceeded?
                               st (:volume-m3 value)))]
    {:summary    (str subject " 向け作業予定提案 (" (:operation-type value) ")"
                      (when st (str " site=" site-id)))
     :rationale  (if st
                   (str "site-verified?=" (registry/site-verified? st)
                        " permit-status=" (:permit-status st)
                        " over-allowance?=" over-allowance?
                        " finalize?=" (boolean (:finalize? value)))
                   (str site-id " が見つかりません"))
     :cites      (if st [site-id] [])
     :effect     :field-operation/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not over-allowance?) (not (:finalize? value))) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a terrain/weather/equipment-hazard safety-concern flag.
  ALWAYS `:stake :coordination/safety-concern` -- a safety concern is
  NEVER a proposal the advisor may quietly downgrade to low-stakes,
  and it is never gated on the referenced site being verified (a
  concern can be raised about ANY site, verified or not -- see README
  `What this actor does NOT do` re: never blocking safety-relevant
  reporting on an administrative technicality). See `logging.phase`:
  no phase ever adds this op to a phase's `:auto` set;
  `logging.governor` also always escalates on `:coordination/safety-
  concern`. Two independent layers agree, deliberately."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        st (and site-id (store/site db site-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:severity value) ")"
                      (when st (str " site=" site-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if st [site-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- order-supplies
  "Draft a fuel/equipment/permit-fee procurement proposal. The
  advisor passes through the caller's own claimed total -- it does
  NOT invent one, and `logging.governor` NEVER trusts it: it
  independently recomputes the total from the order's own line items
  via `logging.registry/order-total` before any commit is possible."
  [_db {:keys [subject value]}]
  (let [total (registry/order-total value)
        matches? (registry/order-total-matches-claim? value)]
    {:summary    (str subject " 向け資材発注提案 (" (count (:items value)) " 品目)")
     :rationale  (str "claimed-total=" (:claimed-total value)
                      " independent-recompute=" total
                      " matches?=" matches?)
     :cites      (if (seq (:items value)) [subject] [])
     :effect     :supply-order/propose
     :value      value
     :stake      nil
     :confidence (if matches? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-harvest-record        (log-harvest-record db request)
    :schedule-field-operation  (schedule-field-operation db request)
    :flag-safety-concern       (flag-safety-concern db request)
    :order-supplies            (order-supplies db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域林業(伐採)コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:site/upsert|:field-operation/schedule|"
       ":safety-concern/flag|:supply-order/propose) "
       ":stake(:coordination/safety-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または許可未取得のサイトに対する作業予定を提案してはいけません。"
       "伐採計画の確定(finalize)や伐採・集材機材の直接操作を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "資材発注の合計金額を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-harvest-record        {:site (store/site st subject)}
    :schedule-field-operation  {:site (store/site st (:site-id value))}
    :flag-safety-concern       {:site (and (:site-id value) (store/site st (:site-id value)))}
    :order-supplies            {:order-total (registry/order-total value)}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `logging.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a field
  operation, auto-flag a concern, or auto-place a supply order."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :logging-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
