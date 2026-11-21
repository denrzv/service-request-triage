# FEATURE-006: Code review
Status: REVIEW_APPROVED
Outcome: REVIEW_APPROVED

Evidence: deterministic check current PASS (fingerprint f66e2e69...a9a8); verifier re-ran the graph, codec, validator, domain-type,
ArchUnit boundary and goldset-isolation suites offline with JDK 25: pass (`TriageGraphTest` 14, `ArchitectureBoundaryTest` 5, 0 failures).

## Scope reviewed
`graph.*` (graph runner, nodes, route table, state, strict codec, guard, validator), new `domain.model` records
(`IncomingCustomerRequest`, `TriageStatus`, `DecisionTraceEvent`), ArchUnit allow-list, README/architecture/contracts/risks edits.
ADR `docs/adr/FEATURE-006-stategraph-foundation.md` is `ACCEPTED`; baseline ADR-001/003/004/005/007 referenced by the plan are
present in `docs/adr/README.md`. Implementation matches the ADR (single `triage` key, node-selected routes, Policy Engine authority,
runner-derived PARTIAL, recursionLimit 12, per-run checkpoint thread with `releaseThread(true)`).

## Checks
- Decision authority: only `FinalPolicyGateNode` writes `routingDecision`, always from `ClassificationPolicyEngine`
  (`decide` / `modelUnavailable`). `ConfidenceGateNode` only derives a label from a preliminary pure-engine call and never stores
  a decision; no threshold is duplicated in the graph. No model makes a classification or routing decision.
- Jev usage: one call in `JevClassifyNode`, only message and channel with `TriageQuestionSets.v1()`; `JevException`
  (including `JevProtocolException` from the strict mapper) maps to `error` and `modelUnavailable`; no defaulted confidence.
  No DeepSeek, no live provider call in default tests (fake port), no Task Management access (ArchUnit allow-list enforces
  ports `JevClassificationPort`, `TaxonomyPort`, `ClassificationPolicyPort` only).
- Goldset isolation: no goldset or evaluation reference in `graph` main code; `GoldsetIsolationTest` green.
- Error handling: `GuardedNode` converts `RuntimeException` into `NODE_FAILED` (class name only); invariant guard for
  `LLM_INVESTIGATION`; framework failure / empty result -> `FAILED`/`GRAPH_RUNTIME_ERROR`; early stop -> `PARTIAL`.
  `run` throws only for a null request.
- Maintainability: single route table shared by nodes, trace and compiled edges; immutable state with invariants; wire records
  keep the format independent of SAA serializer internals.
- Compatibility: additive; no new dependency; no Spring wiring, so keyless start-up is unchanged.

## Non-blocking notes
- A JVM `Error` inside a node can block `run` in SAA 1.1.2.0; documented in Javadoc and README, deferred to FEATURE-012 deadline.
- `DRAFT_PROPOSAL -> CLASSIFIED` naming may need revisiting at FEATURE-010 (recorded in the PRD risks).

## Findings
No Blocking or Important findings.
