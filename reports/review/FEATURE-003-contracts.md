# FEATURE-003: Contract review
Status: CONTRACT_PASS
Outcome: CONTRACT_PASS

## Checklist
- Compatibility: additive. FEATURE-002 Jev types untouched; `Checks` only gains helpers; no existing public signature changed.
- Schemas: JSON definition schema (`DefinitionWire`) is strict and versioned in-repo; `version`/`taxonomyVersion` cross-checked at load. Shipped `v1` files validate in tests.
- Shared contract deviations from `docs/contracts.md`: typed `ChoiceJudgment<E>`, enum `RationaleCode`, `RoutingDecision.policyCalibrated`, required `taxonomyVersion`/`policyVersion`, extra `investigationAttempts` engine argument, `get(version)`/`active()` ports. All recorded in the ACCEPTED ADR and in contracts.md "Implemented in FEATURE-003" notes (sections 1, 2, 4).
- Consumers: FEATURE-005..010 not yet implemented; no in-repo consumer breaks. Notes tell them to map Jev strings to enums in the mapper.
- Rollout order: merge, then downstream branches rebase; `triage.definitions.*` defaults equal the shipped files, so no config change is required.
- Error contract: `IllegalArgumentException` for invalid records, `DefinitionNotFoundException` for unknown versions, `IllegalStateException` with location for startup failures; matches plan.

## Findings
No Blocking or Important findings.
