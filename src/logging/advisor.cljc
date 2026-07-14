(ns logging.advisor
  "LoggingAdvisor -- sealed LLM inference node. Proposes coordination actions
  for logging operations but cannot directly execute or authorize felling.
  Proposals are ALWAYS routed through the Logging Coordination Governor.")

(defprotocol Advisor
  "Protocol for logging advisors"
  (-advise [this store request] "Propose a coordination action"))

(defn mock-advisor
  "Returns a mock advisor that generates basic proposals"
  []
  (reify Advisor
    (-advise [this store request]
      (let [op (:op request)
            subject (:subject request)]
        {:op op
         :subject subject
         :effect :propose
         :cites {:advisor :mock}
         :summary (format "Proposed %s for %s" (name op) subject)
         :value {:op op :subject subject :ts (System.currentTimeMillis)}
         :confidence 0.7}))))

(defn trace [request proposal]
  "Create an audit trace of an advisor proposal"
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :confidence (:confidence proposal)})
