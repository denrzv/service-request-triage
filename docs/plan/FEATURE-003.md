# FEATURE-003: Implementation plan
Status: PLAN_APPROVED

## Overview
Add the synthetic taxonomy vocabulary and decision contracts to `domain.model`,
the `TaxonomyPort` / `ClassificationPolicyPort` to `domain.port`, a pure
`ClassificationPolicyEngine` to `application.policy`, and JSON definition
loading plus Spring wiring to `infrastructure.taxonomy`. Ship
`taxonomy/v1.json` and an uncalibrated `policy/v1.json`. Decisions in
`docs/adr/FEATURE-003-taxonomy-policy-engine.md`.

## Assumptions
- Maven runs use `JAVA_HOME=/usr/lib/jvm/temurin-25` in this environment (default shell JDK is 21).
- Jev-answer to `SemanticAssessment` mapping is FEATURE-005/007; tests build assessments directly.
- Work is on the session-assigned branch `claude/clever-mccarthy-i2uiet`; no parallel FEATURE-003 branch exists.

## Architecture / Flow
```
startup: DefinitionsProperties -> JsonDefinitionLoader (ResourceLoader + ObjectMapper)
         -> TaxonomyRepository (TaxonomyPort) / PolicyRepository (ClassificationPolicyPort)
         -> validate: enum coverage, reason codes, policy<->taxonomy compatibility, unique versions

runtime (later graph node):
  ClassificationPolicyEngine.decide(SemanticAssessment, Taxonomy, ClassificationPolicy, investigationAttempts)
    -> RoutingDecision(action, ClassificationProposal, ruleIds, rationaleCodes, policyVersion, calibrated, humanApprovalRequired)
  ClassificationPolicyEngine.modelUnavailable(Taxonomy, ClassificationPolicy) -> HUMAN_REVIEW
```
Packages:
- `domain.model`: enums `PrimaryIntent`, `ProblemDomain`, `RequestedOutcome`, `ServiceRequestType`, `RouteAction`, `RationaleCode`, `SignalRequirement`; records `ReasonCode`, `Taxonomy`, `ChoiceJudgment<E>`, `SemanticAssessment`, `ClassificationProposal`, `RoutingDecision`, `PolicyThresholds`, `RuleCondition`, `ClassificationRule`, `ClassificationPolicy`.
- `domain.port`: `TaxonomyPort`, `ClassificationPolicyPort`, `DefinitionNotFoundException`.
- `application.policy`: `ClassificationPolicyEngine`.
- `infrastructure.taxonomy`: `DefinitionsProperties`, `DefinitionWire` (JSON DTOs), `DefinitionJsonMapper`, `InMemoryTaxonomyRepository`, `InMemoryPolicyRepository`, `DefinitionsConfiguration`.
- Resources: `taxonomy/v1.json`, `policy/v1.json`; `application.yml` gains `triage.definitions.*`.

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | No new external actor or system | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | No new deployable or store; definitions are classpath resources | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): the engine is the only producer of `RoutingDecision`; pure Java, no model call, no I/O. No graph code in this ticket.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): none; engine bounds LLM investigation via `maxInvestigations`.
- Abstention / human-review paths covered: model unavailable, low taxonomy fit, none-fit, multi-intent after budget, low confidence/near-tie after budget, approval-required type; all table-tested.
- Synthetic-data and goldset-isolation impact: synthetic English definitions only; no goldset or labels involved.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: none (Java API only).
- Request/input: `SemanticAssessment` (three `ChoiceJudgment`s, four Noul values in [0, 1], non-blank `modelUsed`, `questionSetVersion`); `Taxonomy`; `ClassificationPolicy`; `investigationAttempts >= 0`.
- Response/output: `RoutingDecision` with `ClassificationProposal`.
- Error behavior: `IllegalArgumentException` for invalid records or taxonomy/policy version mismatch; `DefinitionNotFoundException` for unknown versions; startup failure (`IllegalStateException` with file location and reason) for invalid definitions.

### Outbound contracts
- Integration: none.
- Timeout: not applicable.
- Retry: not applicable.
- Idempotency: pure function; equal inputs give equal outputs.

### Shared contract changes
- `docs/contracts.md` section 1 gains an "Implemented in FEATURE-003" note: typed `ChoiceJudgment<E>`, enum `RationaleCode`, `RoutingDecision.policyCalibrated`, `ClassificationProposal` requires `taxonomyVersion`. Section 2 ports `TaxonomyPort` / `ClassificationPolicyPort` implemented; `ClassificationModelPort` stays for FEATURE-005/007.

## Data changes
- Schema changes: none (JSON resource schema is new and versioned in-repo).
- Migration/backfill: none.
- Indexes/partitions: none.
- Rollback/forward fix: revert PR; new definitions go to new version files.

## Reliability
- Timeout strategy: not applicable.
- Retry/backoff strategy: not applicable.
- Duplicate handling: duplicate versions or rule IDs rejected at load.
- Ordering assumptions: rule order in the file is significant (first match wins) and documented.
- Partial failure behavior: any invalid definition fails startup; no partially loaded state.

## Security & Privacy
- AuthN/AuthZ: not applicable.
- Input validation: strict JSON mapping (unknown enum names rejected, unknown properties rejected), ranges, cross-references.
- Sensitive data/logging: definitions contain no secrets/customer data; log only loaded versions and file locations.
- Audit trail: decision includes policy/taxonomy versions, rule IDs, rationale codes.

## Observability
- Logs: one INFO line per loaded definition (version, location).
- Metrics: none (FEATURE-012).
- Traces/correlation: decision fields ready for `DecisionTraceEvent` (FEATURE-012).
- Alerts/runbook: README section on publishing a new policy version.

## Testing strategy
- Unit tests: record invariants (`TaxonomyModelTest`, `DecisionContractsTest`, `PolicyModelTest`); JSON mapping errors (`DefinitionJsonMapperTest`).
- Table tests: `ClassificationPolicyEngineTest` with `@ParameterizedTest` rows for complaint, technical issue, mixed intent, none-fit on shipped v1 and on a test-only thresholds policy (`src/test/resources/policy/test-thresholds.json`); determinism test.
- Integration tests: `DefinitionsConfigurationTest` (Spring context loads v1; alternate `file:`/classpath policy location changes decision without source change; invalid definition fails startup; unknown version raises not-found).
- Regression tests: `ArchitectureBoundaryTest`, `StartupHealthTest` stay green.
- Manual checks: full `./mvnw -B verify` on JDK 25.

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: not-required
Docs update: required
Contract review: required
DB review: not-required
Reliability review: not-required

### Rationale
- Review: Always required.
- QA: Always required; focused on the decision table and startup validation.
- Security review: No secrets, network, auth, logging of customer text or new dependencies; definitions are trusted classpath config.
- Docs update: README taxonomy/policy section, contracts.md implemented-notes, ADR link.
- Contract review: New shared domain contracts and ports consumed by FEATURE-005..010; refinements of contracts.md.
- DB review: No database or migrations.
- Reliability review: No I/O at runtime, no retries/timeouts; startup fail-fast is covered by QA.

## Rollout plan
- Deployment order: merge PR; FEATURE-005/006/007 rebase onto it.
- Feature flags/config: `triage.definitions.active-policy-version=v1`.
- Backward compatibility: additive; FEATURE-002 types untouched.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-003-taxonomy-policy-engine.md`
- Baseline: ADR-003, ADR-004, ADR-010.

## Open Questions
- None blocking.
