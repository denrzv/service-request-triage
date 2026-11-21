# FEATURE-006: Contract review
Status: CONTRACT_PASS
Outcome: CONTRACT_PASS

## Checklist
- Compatibility: additive. New `IncomingCustomerRequest`, `TriageStatus`, `DecisionTraceEvent`; no existing port, record or
  adapter signature changed. `TriageStatus` values match `docs/contracts.md` section 1.
- Schemas: serialized state `triage-graph-state-v1` is strict (unknown fields/enums, wrong schema version, invariant violations
  rejected with messages free of customer text); codec round trips full/minimal/failed states incl. enum-keyed maps. A format change
  requires a new schema version (documented in contracts section 5). Route labels per node are one table (`TriageRoutes`) and match
  the README, architecture and ADR.
- Consumers: FEATURE-007/009 remap labels in the route table; FEATURE-010/011/012 consume the state; FEATURE-011 will persist the
  JSON form. `docs/contracts.md` sections 1 and 5 describe the implemented shape (no separate `investigation` field yet, noted).
- Rollout order: merge; no runtime wiring, configuration or external API change, so no ordering constraints.

## Findings
No Blocking or Important findings.
