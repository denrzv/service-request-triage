# FEATURE-003: Documentation review
Status: DOCS_COMPLETE
Outcome: DOCS_COMPLETE

## Changed paths
- `README.md`: new "Taxonomy and policy (FEATURE-003)" section (definition files, engine evaluation order, `modelUnavailable`, uncalibrated no-op v1 and `THRESHOLDS_UNCALIBRATED`, `triage.definitions.*` property table with defaults matching `DefinitionsProperties`/`application.yml`, publishing a new policy version, startup validation failures).
- `docs/contracts.md`: "Implemented in FEATURE-003" notes in section 1 (typed `ChoiceJudgment<E>`, enum `RationaleCode`, `policyCalibrated`, decision invariants), section 2 (ports, `DefinitionNotFoundException`, engine signature with `investigationAttempts`) and section 4 (implemented engine order).
- `docs/adr/FEATURE-003-taxonomy-policy-engine.md`: ACCEPTED; links PRD, plan and tasklist.
- `src/main/resources/application.yml`: commented `triage.definitions` block.

## Validation
- Property names/defaults cross-checked against `DefinitionsProperties` and `application.yml`.
- Engine order cross-checked against `ClassificationPolicyEngine` Javadoc and `ClassificationPolicyEngineTest`.
- Diagrams: plan declares no impact (no new external system, container or store).

## Findings
No Blocking or Important findings.

## Independent verification
Verifier re-checked the maintained docs against the diff: README section, contracts.md notes (sections 1, 2, 4), ADR links and `application.yml` property names/defaults match `DefinitionsProperties` and the engine. No stale or missing documentation found. Status confirmed: DOCS_COMPLETE.
