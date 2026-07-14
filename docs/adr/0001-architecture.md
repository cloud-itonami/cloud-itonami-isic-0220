# ADR-0001: LoggingAdvisor ⊣ Logging Coordination Governor architecture

## Status

Accepted. `cloud-itonami-isic-0220` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-0220` publishes an OSS blueprint for community
logging operations (harvest record logging, crew dispatch scheduling, safety
hazard flagging, and timber shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code, following
the same langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

This vertical has NO bespoke domain capability library in `kotoba-lang` to
wrap (verified: no `kotoba-lang/logging`-style repo exists). This build
therefore uses self-contained domain logic — pure functions in
`logging.registry` (site verification, harvest validation, shipment protocol
verification) are re-verified independently by the governor.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:logging-coordination-governor`, is grep-verified UNIQUE fleet-wide.

## Decision

### Decision 1: Self-contained domain logic (no external logging capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
logging vertical has NO pre-existing capability library to wrap. The
site-verification / harvest-validation / shipment-protocol validation functions
live as pure functions in `logging.registry` and are re-verified independently
by `logging.governor` — the same "ground truth, not self-report" discipline
established across prior actors.

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of logging operations.
It does NOT:
- Control felling equipment, skidders, or loaders directly
- Make crew-safety decisions or site-hazard assessments (exclusive to human logging manager)
- Authorize or execute felling operations

All proposals are `:effect :propose` only. The advisor proposes; the governor
validates; escalation paths funnel to human logging-manager approval. This is
not a replacement for manager authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: Logging is a safety-critical domain (real felling
hazards, terrain risk, equipment weight, fatigue). Safety hazard flagging
NEVER auto-commits. All health/safety concerns escalate immediately to human
review.

### Decision 3: Safety-hazard escalation — always human sign-off

`:flag-safety-hazard` (felling risk, terrain hazard, weather concern, crew
fatigue) ALWAYS escalates, never auto-commits. This is not a "low-stakes
proposal" — it is a circuit-breaker that must reach human authority.

### Decision 4: HARD invariants (no override)

Four HARD governor checks that block proposals and cannot be overridden by
human approval:
1. Site/permit record must be registered in the SSoT before any action
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct felling-equipment control or crew-operation authorization is permanently blocked
4. Safety hazards ALWAYS escalate (no threshold that permits auto-commit)

## Consequences

(+) Logging operations back-office now has a documented, governed, auditable
coordination layer that funnels all decisions through independent validation
before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human logging-manager
sign-off.

(+) Scope is bounded and verifiable: four HARD invariants protect against
scope creep into unauthorized equipment operation or crew control. Safety
hazards are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: hazard flagging cannot be
rate-limited, suppressed, or auto-decided by phase gate. Human review is
mandatory.

(-) Still a simulation/proposal layer, not a real field-operations control
system. Equipment dispatch and felling execution remain human-controlled via
external channels.

(-) No integration with real logging-management databases (site permits,
equipment status, crew location tracking) — this is a standalone coordinator
blueprint.

## Verification

- `cloud-itonami-isic-0220`: `clojure -M:dev:test` green (all tests pass),
  `clojure -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and HARD hold scenarios.
- All source is `.cljc` (portable ClojureScript / JVM / nbb).
- Audit ledger is append-only, all decisions are traced.
- Safety hazard flagging ALWAYS escalates (verified in phase gates and governor checks).
