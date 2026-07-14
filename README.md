# cloud-itonami-isic-0220: Logging

An autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office logging operations: harvest record logging, crew dispatch scheduling, safety hazard flagging, and timber shipment coordination.

## What this actor does

Proposes **coordination** of logging operations:
- `:log-harvest-record` — timber volume/species harvest data logging (proposal only)
- `:schedule-crew-dispatch` — logging crew/equipment dispatch scheduling proposal
- `:flag-safety-hazard` — surface a felling/terrain/weather safety hazard (always escalates)
- `:coordinate-timber-shipment` — outbound timber shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY:**
- Does NOT control felling equipment, skidders, or loaders directly
- Does NOT make crew-safety decisions or site-hazard assessments (that's the logging manager's exclusive human authority)
- Does NOT authorize or execute felling operations (human crew lead decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety hazards ALWAYS escalate — never auto-decided, no thresholds below escalation

## Architecture

Classic governed-actor pattern:
1. **LoggingAdvisor** (sealed LLM node): proposes decisions
2. **Logging Coordination Governor** (independent): validates against domain rules
   - HARD invariants (always `:hold`, no override):
     - Site/permit record must be verified in SSoT
     - All proposals are `:effect :propose` only
     - No direct felling-equipment control or crew-operation authorization
     - Safety hazard flagging ALWAYS escalates (no auto-commit)
   - ESCALATE (always human sign-off):
     - Safety hazards always escalate
     - Crew dispatch orders
     - High-volume harvest records
3. **Phase gates** (Phase 0->3 rollout): only human-approved paths
4. **Audit ledger** (append-only): complete decision trace

## Development

```bash
# Install dependencies (workspace offline mode)
clojure -M:dev

# Run tests
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
