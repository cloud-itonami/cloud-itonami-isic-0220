# cloud-itonami-isic-0220: Logging

Open Business Blueprint for **ISIC Rev.5 0220**: logging — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office logging operations: harvest-record data logging, felling/skidding/hauling field-operation scheduling, safety-concern flagging, and fuel/equipment/permit-fee supply procurement.

This repository designs a forkable OSS business for community logging
operations: run by a qualified operator so a logging cooperative or
landowner keeps its own operating records instead of renting a closed
SaaS.

## What this actor does

Proposes **coordination** of logging operations:
- `:log-harvest-record` — timber volume/species/site harvest data logging (administrative, not an operational decision)
- `:schedule-field-operation` — felling/skidding/hauling scheduling proposal
- `:flag-safety-concern` — surface a terrain/weather/equipment-hazard concern (always escalates)
- `:order-supplies` — fuel/equipment/permit-fee procurement proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain** (heavy
felling/skidding equipment, falling timber, terrain and weather risk):

- Does NOT control felling equipment, skidders, feller-bunchers, or loaders directly
- Does NOT make crew-safety or site-hazard decisions (that's the logging crew supervisor's/forester's exclusive human authority)
- Does NOT authorize or finalize a harvest-cut plan (human crew supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`logging.operation/build`, a langgraph-clj StateGraph):
1. **`logging.advisor`** (sealed intelligence node, `LoggingAdvisor`): proposes decisions only, never commits
2. **`logging.governor`** (independent, `Logging Coordination Governor`): validates against domain rules, re-derived from `logging.registry`'s pure functions and `logging.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Site/permit record must be independently verified/registered (`:verified?` AND permit `:issued`) before any field operation may be scheduled against it
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct felling/skidding-equipment control)
     - Finalizing a harvest-cut plan (`:finalize? true`) is a PERMANENT, unconditional block
     - A `:felling` field operation may not push the site's own recorded harvest volume past its own permitted allowable-cut (independently recomputed)
     - No double-scheduling the same field-operation record
     - No fabricated `:species` value on a harvest-record patch
     - A supply order's claimed total must independently recompute correctly from its own line items
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - `:order-supplies` whose independently-recomputed total exceeds `logging.registry/supply-order-cost-threshold`
     - Low-confidence proposals
3. **`logging.phase`** (Phase 0->3 rollout): `:schedule-field-operation`/`:flag-safety-concern`/`:order-supplies` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-harvest-record` may auto-commit at phase 3 when clean
4. **`logging.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
