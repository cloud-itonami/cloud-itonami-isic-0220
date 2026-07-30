(ns logging.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout). This repo previously had NO demo page and no generator.
  Drives the REAL actor stack (`logging.operation` -> `logging.governor`
  -> `logging.store`) through a scenario adapted from this repo's own
  `logging.sim` demo driver (`clojure -M:dev:run`, whose request ids
  match `logging.store`'s real seeded sites), trimmed to a representative
  subset and rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [logging.store :as store]
            [logging.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "coord-1" :actor-role :logging-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through a scenario mixing every disposition
  this actor can reach: site-001 logs a clean harvest record (phase-3
  auto-commit), a field-operation schedule and a safety-concern flag (both
  ALWAYS escalate -- both human-approved); then two distinct HARD holds
  that never reach a human: site-002 with a mis-wired :effect
  (:direct-write, not :propose) and site-003 with an unrecognized op.
  Every id and op keyword is read from this repo's own store/governor --
  no invented values. Returns the resulting store."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    ;; clean harvest record on site-001 -> phase-3 auto-commit
    (exec! actor "s1" {:op :log-harvest-record :effect :propose :subject "site-001"
                       :patch {:species :douglas-fir :last-assessed "2026-07-14"}})
    ;; schedule field operation op-1 on site-001 (verified, skidding) -> escalate -> approve
    (exec! actor "s2" {:op :schedule-field-operation :effect :propose :subject "op-1"
                       :value {:site-id "site-001" :operation-type :skidding
                               :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "s2")
    ;; flag a safety concern (always escalates) -> approve
    (exec! actor "s3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:site-id "site-001" :severity :moderate
                               :description "slope instability after rain"}})
    (approve! actor "s3")
    ;; HARD hold: site-002 request with :effect other than :propose (structural)
    (exec! actor "s4" {:op :log-harvest-record :effect :direct-write :subject "site-002"
                       :patch {:species :sitka-spruce}})
    ;; HARD hold: unrecognized op on site-003
    (exec! actor "s5" {:op :fell-trees :effect :propose :subject "site-003"
                       :patch {:species :western-hemlock}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-row [ledger {:keys [id parcel species allowable-cut-m3 harvested-to-date-m3]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%,.0f</td><td>%,.0f</td><td>%s</td></tr>"
          (esc id) (esc parcel) (esc (name (or species :n-a)))
          (or allowable-cut-m3 0) (or harvested-to-date-m3 0)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis violations]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", "))
                   (some-> disposition name)
                   (some->> violations first :rule name)
                   ""))))

(def ^:private action-gate-rows
  ["        <tr><td><code>:log-harvest-record</code></td><td><span class=\"ok\">auto-commit when clean</span></td></tr>"
   "        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"warn\">ALWAYS human approval &middot; permit/allowance checked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval (safety)</span></td></tr>"
   "        <tr><td><code>:order-supplies</code></td><td><span class=\"warn\">ALWAYS human approval &middot; cost-threshold + total-recompute</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!`."
  [db]
  (let [ledger (vec (store/ledger db))
        sites (->> (store/all-sites db) (sort-by :id))
        site-rows (str/join "\n" (map (partial site-row ledger) sites))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0220 &middot; logging coordination</title><style>"
     "body{font:14px/1.5 -apple-system,system-ui,sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#0d2b1e;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem;font-weight:600}"
     ".badge{display:inline-block;margin-top:.4rem;font-size:.75rem;opacity:.8}"
     "main{max-width:980px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".card h2{margin-top:0;font-size:1rem}.muted{color:#777;font-size:.82rem}"
     "table{border-collapse:collapse;width:100%;font-size:.85rem}th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee}th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Logging coordination (ISIC 0220) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · field-op / safety / supply actuation always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Logging sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>logging.store</code> via <code>logging.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly. No invented usage or revenue metrics.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Parcel</th><th>Species</th><th>Allowable (m³)</th><th>Harvested (m³)</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Logging Coordination Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Permit allowance and site verification are checked against the site's own record; equipment-control and harvest-finalize effects are permanently out of scope.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
