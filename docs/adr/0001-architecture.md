# ADR-0001: LoggingAdvisor ⊣ Logging Coordination Governor architecture

## Status

Accepted. `cloud-itonami-isic-0220` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, superseding a
prior REVERTED attempt (missing `deps.edn` and missing
`registry.cljc`/`store.cljc` -- see Context) with a verified
from-scratch redo.

## Context

`cloud-itonami-isic-0220` publishes an OSS blueprint for community
logging operations (harvest-record data logging, felling/skidding/
hauling field-operation scheduling, safety-concern flagging, and fuel/
equipment/permit-fee supply procurement). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet (see
e.g. `cloud-itonami-isic-0210`, the closest domain analog -- forestry
operations coordination).

A prior attempt at this repo (commit `9894cca`, "Initial scaffold:
LoggingAdvisor ⊣ Logging Coordination Governor actor") shipped only
`src/logging/{advisor,governor,operation,phase,sim,store}.cljc` and a
single test file, with NO `deps.edn`, NO `registry.cljc`, and NO
`blueprint.edn`/`LICENSE`/`GOVERNANCE.md`/`CODE_OF_CONDUCT.md`/
`CONTRIBUTING.md`/`SECURITY.md` -- `clojure -M:test` could not even
resolve dependencies, so no test claim from that attempt was
independently verifiable. It also used `System.currentTimeMillis`
(JVM-only, not cljs-portable) and different, unspecified op names
(`:schedule-crew-dispatch`/`:flag-safety-hazard`/`:coordinate-timber-
shipment`) than this domain's actual spec. The `kotoba-lang/industry`
registry entry for `"0220"` records this as a REVERTED attempt: "missing
deps.edn AND missing registry.cljc/facts.cljc -- cannot verify test
claims without deps.edn. Needs a verified from-scratch redo." This ADR
and its accompanying commit ARE that from-scratch redo.

This vertical has NO bespoke domain capability library in `kotoba-lang`
to wrap (verified: no `kotoba-lang/logging`-style repo exists). This
build therefore uses self-contained domain logic — pure functions in
`logging.registry` (site/permit verification, permit-allowance
recompute, species validation, supply-order budget verification) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:logging-coordination-governor`, is grep-verified UNIQUE fleet-wide
(GitHub code search across `org:cloud-itonami`, zero other hits).

## Decision

### Decision 1: Self-contained domain logic (no external logging capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
logging vertical has NO pre-existing capability library to wrap. The
site/permit-verification / permit-allowance / supply-budget validation
functions live as pure functions in `logging.registry` and are
re-verified independently by `logging.governor` — the same "ground
truth, not self-report" discipline established across prior actors
(most directly `cloud-itonami-isic-0210`'s `forestry.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of logging
operations. It does NOT:
- Control felling equipment, skidders, feller-bunchers, or loaders directly
- Make crew-safety or site-hazard decisions (exclusive to the human logging crew supervisor/forester)
- Authorize or finalize a harvest-cut plan

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human logging-manager
approval. This is not a replacement for the crew supervisor's
authority — it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: logging is a safety-critical domain
(real felling hazards, falling timber, terrain risk, equipment weight,
weather). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (terrain hazard, weather concern, equipment
hazard, crew fatigue) ALWAYS escalates, never auto-commits. This is
not a "low-stakes proposal" — it is a circuit-breaker that must reach
human authority.

### Decision 4: Permit-allowance recompute, not just existence

Beyond checking that a site/permit record merely EXISTS and is
`:verified?`, the governor independently recomputes whether a
`:felling` proposal's own claimed volume, added to the site's own
recorded `:harvested-to-date-m3`, would exceed the site's own recorded
`:allowable-cut-m3` (the permit's allowable annual cut). This is part
of the same "site/permit record must be independently verified/
registered before any action" HARD invariant — a permit's own
allowance is as much a permit-record fact as its issued status, and is
never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into nine concrete checks in
`logging.governor`, mirroring `cloud-itonami-isic-0210`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Site/permit record must be independently verified/registered (`:verified?` AND permit `:issued`) before any field operation may be scheduled against it, and a felling operation's volume must independently recompute within the permit's own allowable-cut
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct felling/skidding-equipment control or harvest-cut-plan finalization is permanently blocked
4. The op allowlist is closed — `:log-harvest-record`/`:schedule-field-operation`/`:flag-safety-concern`/`:order-supplies` only

## Consequences

(+) Logging operations back-office now has a documented, governed,
auditable coordination layer that funnels all decisions through
independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human logging-
manager sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into nine concrete governor checks) protect against scope creep into
unauthorized equipment operation or harvest-cut-plan finalization.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real field-operations
control system. Equipment dispatch and felling execution remain
human-controlled via external channels.

(-) No integration with real logging-management databases (permit
registries, equipment telemetry, crew location tracking) — this is a
standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-0220`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, site-not-verified/permit-
  pending, felling-exceeds-permit-allowance, harvest-finalize-blocked,
  already-scheduled, invalid-species, order-total-mismatch) plus the
  over-threshold `order-supplies` ESCALATE (not HOLD) case.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which the prior REVERTED
  attempt used and which is not cljs-portable; that prior attempt also
  used `System.currentTimeMillis`, also not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
