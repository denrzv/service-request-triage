# FEATURE-006: QA
Status: QA_PASSED
Outcome: QA_PASSED

Evidence: deterministic check current PASS; pre-commit evidence 338 tests, 0 failures, 0 errors, 2 skipped (opt-in live tests).
Verifier re-ran `TriageGraphTest`, `DecisionNodesTest`, `IntakeNodesTest`, `TriageGraphStateCodecTest`, `InputValidatorTest`,
`TriageExecutionTypesTest`, `ArchitectureBoundaryTest`, `GoldsetIsolationTest` offline: pass. Live provider calls: none.

## Acceptance mapping
- AC1 nodes ValidateInput, LoadTaxonomy, JevClassify, ConfidenceGate, FinalPolicyGate, BuildTrace: `TriageNode`, `TriageGraph.build`;
  `traceMatchesNodesObservedByTheFrameworkAndCheckpointsAreReleased` compares the trace with SAA-streamed nodes. Pass.
- AC2 clear/ambiguous/abstain/error from deterministic state: `clearComplaint...`, `clearInquiry...`, `ambiguousRequest...`,
  `lowTaxonomyFitAbstains`, `noMatchingRuleAbstains...`, `providerFailureTakesTheErrorPath...`, `DecisionNodesTest`. Pass.
- AC3 end-to-end with fake Jev and shipped v1 definitions, no credentials: `TriageGraphTest`. Pass.
- AC4 bounded iterations, explicit PARTIAL/FAILED: `iterationLimitStopsTheRunAsPartial...`, `unexpectedNodeException...`,
  `runtimeFailureOutsideNodes...`, invalid input and unknown policy tests. Pass.
- AC5 state serialization and node/edge trace: `TriageGraphStateCodecTest` (round trip, strict rejects, default SAA runtime). Pass.
- AC6 in-memory limitation documented: README "Triage graph", `docs/architecture.md`, `docs/assumptions-and-risks.md` A-09. Pass.
- AC7 no SR status mutation: state has execution status only; ArchUnit allow-list excludes task-management/lifecycle types. Pass.

## Scenarios
- Positive: clear -> AWAITING_HUMAN / CLASSIFIED, abstain -> COMPLETED, reused graph for concurrent runs.
- Negative: invalid input and unknown policy (no Jev call), provider/protocol failure -> MODEL_UNAVAILABLE, throwing fake -> NODE_FAILED.
- Edge: tiny recursion limit -> PARTIAL with trace so far; invariant violation for LLM_INVESTIGATION; checkpoints released per run.
- Regression: full `./mvnw verify` incl. benchmark reproducibility, goldset isolation, start-up health green per pre-commit report.

## Findings
No Blocking or Important findings.
