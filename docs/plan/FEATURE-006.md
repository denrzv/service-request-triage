# FEATURE-006: Implementation plan
Status: PLAN_APPROVED

## Overview
Add the first runtime orchestration: a conditional Spring AI Alibaba `StateGraph` (package `graph`) with nodes
`ValidateInput`, `LoadTaxonomy`, `JevClassify`, `ConfidenceGate`, `FinalPolicyGate`, `BuildTrace`, an immutable
`TriageGraphState` serialized by a strict versioned codec, deterministic route labels recorded in a trace, and
explicit `PARTIAL`/`FAILED` statuses. It reuses the existing ports, `TriageQuestionSets`, `JevAssessmentMapper` and
the pure `ClassificationPolicyEngine`. Decisions: `docs/adr/FEATURE-006-stategraph-foundation.md`.

## Assumptions
- Maven runs use `JAVA_HOME=/usr/lib/jvm/temurin-25` in this environment.
- Default policy is `ClassificationPolicyPort.active()` (`v1`); taxonomy is `policy.taxonomyVersion()`. Callers may
  pass an explicit policy version.
- Input bound: message 1..4,000 characters after trimming, no control characters other than tab/CR/LF; `triageId`
  matches `[A-Za-z0-9._-]{1,64}`; `channel` optional; `syntheticCustomerId` optional and, if present, the same
  pattern as `triageId`. PoC bounds, constructor-configurable message limit.
- Default `recursionLimit` 12.

## Architecture / Flow
```
TriageGraph.run(IncomingCustomerRequest[, policyVersion])
  initial TriageGraphState(status=RECEIVED) --codec--> OverAllState{"triage": json}
  START -> ValidateInput --valid--> LoadTaxonomy --loaded--> JevClassify --classified|error--> ConfidenceGate
             |invalid                 |failed                 |failed                         |
             v                        v                       v                               v
           BuildTrace <-------------------------------------------------------- failed --------+
  ConfidenceGate --clear|ambiguous|abstain|error--> FinalPolicyGate --decided|failed--> BuildTrace -> END
  after invoke: no BuildTrace event -> PARTIAL(ITERATION_LIMIT_REACHED); exception/empty -> FAILED(GRAPH_RUNTIME_ERROR)
```
Components (new, main):
- `domain.model`: `IncomingCustomerRequest(triageId, message, channel, syntheticCustomerId)` (carrier; `toString`
  omits the message), `TriageStatus` (contracts §1), `DecisionTraceEvent(sequence, node, routeLabel, nextNode)`.
- `graph`: `TriageNode` (node ids), `TriageRoutes` (label constants and label→target maps), `GateOutcome`,
  `FailureCode` (`INPUT_REJECTED`, `DEFINITIONS_UNAVAILABLE`, `NODE_FAILED`, `INVARIANT_VIOLATED`,
  `ITERATION_LIMIT_REACHED`, `GRAPH_RUNTIME_ERROR`), `TriageFailure(code, node, detail)`, `TriageGraphState`
  (record with `with...` transitions), `TriageGraphStateCodec`, `InputValidator`, node actions
  (`ValidateInputNode`, `LoadTaxonomyNode`, `JevClassifyNode`, `ConfidenceGateNode`, `FinalPolicyGateNode`,
  `BuildTraceNode`), `GuardedNode` (exception guard + decode/encode), `TriageGraph` (build/compile/run).
- `TriageGraphState` fields: `input`, `policyVersion`, `taxonomyVersion`, `questionSetVersion`, `resolvedModel`,
  `semanticAssessment`, `providerError` (exception class simple name only),
  `gateOutcome`, `routingDecision`, `investigationAttempts` (0 in v1), `status`, `failure`, `route`, `trace`.
  `triageId()` is derived from `input`; `schemaVersion` exists only in the JSON form. `Taxonomy`/
  `ClassificationPolicy` objects are not stored; nodes reload them by version from the ports (`DefinitionSource`).
- Checkpoints: the graph owns a `MemorySaver` registered with `releaseThread(true)` and uses a fresh checkpoint
  thread per run, so in-memory checkpoints do not accumulate.

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | No new actor or external system | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | Graph already part of the Spring Boot container | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): orchestration via released SAA
  `StateGraph`/`addConditionalEdges`; only `FinalPolicyGate` writes `routingDecision`, always from
  `ClassificationPolicyEngine`; `ConfidenceGate` only labels the path.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): Jev only via `JevClassificationPort`, at most
  one call per run, only `message` and `channel` sent with `TriageQuestionSets.v1()`; no DeepSeek; no live test
  added (graph tests use a fake port).
- Abstention / human-review paths covered: tests for `ABSTAIN`, `HUMAN_REVIEW` (approval type, ambiguity with
  budget exhausted, model unavailable), plus `FAILED`/`PARTIAL`.
- Synthetic-data and goldset-isolation impact: tests use hand-written synthetic messages; no goldset access from
  `src/main`; `GoldsetIsolationTest` stays green.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: none (Java API `TriageGraph.run`).
- Request/input: `IncomingCustomerRequest`; optional policy version.
- Response/output: final `TriageGraphState` (status, failure, assessment, decision, versions, trace).
- Error behavior: never throws for request or provider problems; returns `FAILED`/`PARTIAL`/`AWAITING_HUMAN`.
  `IllegalArgumentException` only for a null request or invalid constructor arguments.

### Outbound contracts
- Integration: `JevClassificationPort.classify(JevClassificationRequest(message, channel, v1))`.
- Timeout: adapter defaults (connect 5 s, request 30 s).
- Retry: adapter's bounded retries; the graph adds none.
- Idempotency: read-only judgment; no side effects in this ticket.

### Shared contract changes
- New domain types `IncomingCustomerRequest`, `TriageStatus`, `DecisionTraceEvent` (additive, per contracts §1).
- Serialized state `triage-graph-state-v1` (in-memory only now; the format FEATURE-011 checkpoints will persist).
- `docs/contracts.md` §1/§5 updated to the implemented shape.

## Data changes
- Schema changes: none.
- Migration/backfill: none.
- Indexes/partitions: none.
- Rollback/forward fix: revert PR; a state format change requires a new `schemaVersion`.

## Reliability
- Timeout strategy: inherited from the Jev adapter.
- Retry/backoff strategy: none added; `JevException` → `error` route on first surfaced failure.
- Duplicate handling: not applicable (no side effects); `triageId` reserved as future idempotency key.
- Ordering assumptions: single-threaded sequential graph; compiled graph is reused, per-run state only.
- Partial failure behavior: node guard → `NODE_FAILED` → `BuildTrace`; iteration limit → `PARTIAL`; framework
  failure → `FAILED`. Trace preserved in all but the framework-failure case (which carries the failure only).

## Security & Privacy
- AuthN/AuthZ: none (no endpoint).
- Input validation: `InputValidator` in `ValidateInput` (length, characters, identifier pattern); strict codec.
- Sensitive data/logging: no logging of message text; failure detail never contains exception messages or input;
  `IncomingCustomerRequest`/`TriageGraphState.toString` omit the message.
- Audit trail: trace events and versions (policy, taxonomy, question set, resolved model) in the result.

## Observability
- Logs: none added beyond existing adapter metadata logs.
- Metrics: none (FEATURE-012).
- Traces/correlation: `triageId` and `DecisionTraceEvent` sequence per run.
- Alerts/runbook: README "Triage graph" section.

## Testing strategy
- Unit tests: `InputValidatorTest`; `TriageGraphStateCodecTest` (round trip of full/minimal/failed states incl. enum
  maps, rejects unknown schema/fields/enums, message absent from `toString`); `ConfidenceGateNodeTest` /
  `FinalPolicyGateNodeTest` (label per decision, `LLM_INVESTIGATION` invariant); `DomainTypesTest` for new records.
- Integration tests (offline, real SAA runtime): `TriageGraphTest` with fake Jev and shipped `v1` definitions:
  clear → `CLASSIFIED`/`AWAITING_HUMAN`, ambiguous → `AWAITING_HUMAN` + `INVESTIGATION_BUDGET_EXHAUSTED`,
  abstain (low fit) → `ABSTAIN`/`COMPLETED`, no rule → configured action, Jev unavailable/protocol/mapper error →
  `MODEL_UNAVAILABLE`, invalid input → `FAILED` with no Jev call, unknown policy → `FAILED`, throwing fake → 
  `NODE_FAILED`, small recursion limit → `PARTIAL`; trace node sequence equals SAA-streamed node sequence; graph
  reused for multiple runs; state survives default `MemorySaver` cloning.
- Architecture tests: graph depends only on allowed ports; no SR lifecycle/task-management types in graph.
- Regression tests: full `./mvnw verify` incl. benchmark reproducibility and goldset isolation.
- Manual checks: none (no live call needed; Jev path already live-smoked in FEATURE-002/005).

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: required
Docs update: required
Contract review: required
DB review: not-required
Reliability review: required

### Rationale
- Review: Always required.
- QA: Always required; focus on every route label, status mapping and failure path.
- Security review: Untrusted customer text flows through graph state to an external provider; must not leak into
  trace, failure details or logs.
- Docs update: README, architecture/contracts updates, in-memory durability limitation.
- Contract review: New shared domain types and the serialized graph state format consumed by FEATURE-007/009/011/012.
- DB review: No database or migrations.
- Reliability review: Bounded iterations, failure/partial semantics, provider-error handling, thread-safety of the
  reused compiled graph.

## Rollout plan
- Deployment order: merge PR; FEATURE-007/009 extend routes, FEATURE-012 wires the graph into Spring/API.
- Feature flags/config: none.
- Backward compatibility: additive.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-006-stategraph-foundation.md`
- Baseline: ADR-001, ADR-003, ADR-004, ADR-005, ADR-007.

## Open Questions
- None blocking.
