# FEATURE-005: Implementation plan
Status: PLAN_APPROVED

## Overview
Add a versioned triage question set and a strict Jev-answer mapper to the runtime (`application.assessment`), and a
test-scope benchmark harness (`evaluation.benchmark`) that runs `RULES_ONLY` and `JEV_ONLY` through the unchanged
Policy Engine and policy `v1` on both goldset splits, computes metrics, explores thresholds on the calibration split
only, and writes a deterministic report. Offline runs replay committed recordings of one live Jev run; live runs are
opt-in. Decisions: `docs/adr/FEATURE-005-jev-baseline-benchmark.md`.

## Assumptions
- Maven runs use `JAVA_HOME=/usr/lib/jvm/temurin-25` in this environment.
- The live run uses `jev-latest` (resolved `jev-1.14.0` on 2026-11-21) and the price USD 0.042 per million input
  tokens, output free (TypeSafe Models page, retrieved 2026-11-21).
- The committed run ID is `feature-005-jev-baseline`.
- The illustrative wrong-auto-route target for candidates is 0.10; it is an exploration parameter, not policy.

## Architecture / Flow
```
GoldsetLoader(dev, test) + manifest hash check
  -> for each case (both splits), mode in {RULES_ONLY, JEV_ONLY}:
       RULES_ONLY: KeywordRulesAssessor.assess(message) -> SemanticAssessment
       JEV_ONLY:   JevClassificationPort.classify(message, channel, TriageQuestionSets.v1())
                   -> JevAssessmentMapper.toAssessment(result)   (JevException -> provider error)
       ClassificationPolicyEngine.decide(assessment, taxonomy v1, policy v1, attempts = maxInvestigations)
         or modelUnavailable(...) on provider error
       -> CaseOutcome(caseId, split, mode, assessment?, decision, usage/latency?, error?)
  -> BenchmarkMetrics per (mode, split)
  -> ThresholdExplorer(CalibrationSet(dev JEV_ONLY outcomes)) -> sweep rows + candidates
       -> held-out check: candidate policy re-decided on test assessments
  -> CostSummary(usage, latency, prices)
  -> ReportWriter -> reports/evaluation/<runId>/{report.md, metrics.json, confusion-*.csv, cases-*.csv,
                                               threshold-sweep.csv, jev-responses.jsonl}
JEV port: live = RecordingJevPort(adapter) | offline = ReplayJevPort(jev-responses.jsonl) | unit = scripted fake
```
Components (new):
- Main `application.assessment`: `TriageQuestionSets` (question set `triage-jev-v1`, question-name constants),
  `JevAssessmentMapper`.
- Test `evaluation.benchmark`: `BaselineMode`, `KeywordRulesAssessor`, `CaseOutcome`, `BaselineRunner`,
  `BenchmarkMetrics` (+ `ClassMetrics`), `ThresholdExplorer`, `CalibrationSet`, `CostSummary`, `JevRecording`
  (record/replay ports and JSONL codec), `BenchmarkRun` (orchestration + metadata), `ReportWriter`, tests.
- Test `infrastructure.typesafe.LiveJevPorts`: public factory for the package-private adapter (opt-in only).

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | No new actor or external system; Jev already in context | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | No new deployable or store; harness is test code | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): no graph code; the Policy Engine makes every
  formal type/route decision for both baselines; Jev output is only a `SemanticAssessment`.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): Jev only through `JevClassificationPort`; live
  run opt-in via `JEV_BENCHMARK_LIVE=true` + `JEV_API_KEY` with a total call budget; no DeepSeek.
- Abstention / human-review paths covered: `ABSTAIN` and `HUMAN_REVIEW` counted as first-class outcomes in metrics;
  provider errors take the `MODEL_UNAVAILABLE` review path; mapper failures tested.
- Synthetic-data and goldset-isolation impact: only `message` and `channel` go to Jev; recordings and reports live in
  `reports/evaluation/`, outside `src/`; question set contains no goldset text; isolation tests stay green;
  threshold exploration structurally limited to the development split.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: none.
- Request/input: goldset v1 via `GoldsetLoader`; recordings `jev-responses.jsonl` (one JSON object per line:
  `messageSha256`, `resolvedModel`, `inputTokens`, `outputTokens`, `latencyMs`, `attempts`, `answers` or `error`).
- Response/output: `report.md`, `metrics.json`, CSV files (documented in the report and README).
- Error behavior: `JevProtocolException` from the mapper; `IllegalStateException` for missing recordings, hash
  mismatches or exceeded call budget; `IllegalArgumentException` for non-DEV cases in `CalibrationSet`.

### Outbound contracts
- Integration: TypeSafe `POST /v1/systemone` via the existing adapter; one request per case with the seven questions
  of `triage-jev-v1`, state `{customerMessage, channel}`.
- Timeout: adapter defaults (connect 5 s, request 30 s).
- Retry: adapter defaults (3 attempts, bounded backoff); no additional retry.
- Idempotency: read-only judgments; re-running a live run creates new recordings under the given `runId`.

### Shared contract changes
- New runtime contract `triage-jev-v1` question set (question names, criteria keys, Noul semantics) and
  `JevAssessmentMapper`; additive. `docs/contracts.md` gains a section; FEATURE-006/007 consume it.

## Data changes
- Schema changes: none (files only).
- Migration/backfill: none.
- Indexes/partitions: none.
- Rollback/forward fix: revert PR; a new question set requires a new live run and run ID.

## Reliability
- Timeout strategy: inherited from the adapter.
- Retry/backoff strategy: inherited; the live run aborts before the first call if `cases > JEV_BENCHMARK_MAX_CALLS`
  (default 300).
- Duplicate handling: replay keyed by message SHA-256; duplicate recordings for one hash are rejected.
- Ordering assumptions: cases processed in file order; all report tables sorted deterministically.
- Partial failure behavior: a failing case is recorded as an error and decided by `modelUnavailable`; the run
  continues and reports the provider error count.

## Security & Privacy
- AuthN/AuthZ: Bearer key from `JEV_API_KEY` only (existing adapter); never written to recordings or reports.
- Input validation: strict mapper; strict recording parser (unknown fields rejected).
- Sensitive data/logging: synthetic data only; reports hold no message text and no per-case gold labels; the
  harness logs nothing per case beyond what the adapter already logs.
- Audit trail: run metadata (hashes, versions, resolved models, price source) in `metrics.json` and `report.md`.

## Observability
- Logs: adapter metadata logs only.
- Metrics: offline report metrics (quality, abstention, cost, latency p50/p95).
- Traces/correlation: `caseId` in per-case CSV; message SHA-256 in recordings.
- Alerts/runbook: README section "Jev baseline benchmark" with exact commands.

## Testing strategy
- Unit tests: `TriageQuestionSetsTest` (criteria cover each enum exactly, versions), `JevAssessmentMapperTest`
  (happy path, missing/mistyped answer, unknown/missing keys, wrong version), `KeywordRulesAssessorTest`,
  `BenchmarkMetricsTest` (hand-computed F1, confusion, recall, abstention, wrong auto-route, OOT),
  `ThresholdExplorerTest` (DEV-only guard, candidate rule, no-candidate case), `JevRecordingTest` (round trip, error
  replay, missing recording, duplicate hash), `BaselineRunnerTest` (provider error -> `MODEL_UNAVAILABLE`, no
  `LLM_INVESTIGATION`), `CostSummaryTest` (percentiles, price absent).
- Integration/contract tests: `BaselineBenchmarkReproducibilityTest` replays the committed run and the rules-only
  baseline over the real goldset and compares every generated file with `reports/evaluation/feature-005-jev-baseline/`.
- Regression tests: full suite incl. `GoldsetIsolationTest`, `ArchitectureBoundaryTest`.
- Manual checks: one opt-in live run (`JEV_BENCHMARK_LIVE=true`) whose output is committed; result recorded in the PR.

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: required
Docs update: required
Contract review: required
DB review: not-required
Reliability review: not-required

### Rationale
- Review: Always required.
- QA: Always required; focus on metric correctness, DEV-only calibration guard and failure paths.
- Security review: Synthetic messages are sent to an external provider in the opt-in run, the API key is handled, and
  goldset labels must not leak into requests, recordings or tools.
- Docs update: README, evaluation/contracts notes, ADR, committed report.
- Contract review: New runtime question-set contract and mapper consumed by FEATURE-006/007; recording file format.
- DB review: No database or migrations.
- Reliability review: No runtime I/O path, timeout or retry change; the opt-in harness reuses the adapter and adds a
  call budget.

## Rollout plan
- Deployment order: merge PR; FEATURE-006/007 consume `TriageQuestionSets` and the report.
- Feature flags/config: none in runtime; harness env flags `JEV_BENCHMARK_LIVE`, `JEV_BENCHMARK_RUN_ID`,
  `JEV_BENCHMARK_MAX_CALLS`, price variables.
- Backward compatibility: additive.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-005-jev-baseline-benchmark.md`
- Baseline: ADR-002, ADR-003, ADR-004, ADR-009, ADR-010.

## Open Questions
- None blocking.
