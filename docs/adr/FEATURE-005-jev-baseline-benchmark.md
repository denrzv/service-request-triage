# ADR: FEATURE-005 — Test-scope baseline harness, versioned triage question set, record/replay offline mode
Status: ACCEPTED
Date: 2026-11-21

## Context
ADR-009 requires measuring rules-only and Jev-only baselines on the frozen goldset before graph or LLM work.
ADR-002/ADR-004 require Companion Noul and a separation between Choice probability and confidence; ADR-003 keeps
semantic assessment, formal type and route separate and makes the Java Policy Engine the only decision owner.
FEATURE-004 put the goldset outside the runtime classpath and its reader in test scope, and its isolation tests
forbid case IDs and messages in `src/main` and `src/test/resources`. CI has no cloud credentials, yet the issue asks
for offline runs and metrics that are meaningful, and FEATURE-006/007 will need the same question set and mapping
the benchmark uses. The policy `v1` thresholds are explicitly uncalibrated no-ops.

## Decision
1. **Harness location.** The benchmark lives in test scope (`src/test/java/.../evaluation/benchmark`) next to the
   goldset reader. JUnit tests are the harness entry points; no Graph, no DeepSeek, no new dependency, no Maven
   profile. The runtime artifact never depends on `..evaluation..` (already enforced by ArchUnit).
2. **Versioned question set and strict mapper in the runtime.** `application.assessment.TriageQuestionSets.v1()`
   (version `triage-jev-v1`) asks, in one Jev call: Choice `primary_intent`, `problem_domain`, `requested_outcome`
   (criteria keys are the lower-case taxonomy enum names) and Noul `refund_requested`, `technical_problem`,
   `multi_intent` and the Companion Noul `taxonomy_fit`. `application.assessment.JevAssessmentMapper` maps a
   `JevClassificationResult` to a `SemanticAssessment` and raises `JevProtocolException` for a wrong question-set
   version, a missing or mistyped answer, or choice probabilities whose keys are not exactly the enum constants.
   Changing any question text or key requires a new question-set version.
3. **Same policy, no fallback.** Both baselines feed a `SemanticAssessment` into the unchanged
   `ClassificationPolicyEngine` with policy `v1` and `investigationAttempts = policy.maxInvestigations()`, so
   ambiguity concerns become `HUMAN_REVIEW` (`INVESTIGATION_BUDGET_EXHAUSTED`) and `LLM_INVESTIGATION` cannot occur.
   `RULES_ONLY` uses a small deterministic keyword assessor (one-hot probabilities, confidence 1.0, Noul 0/1),
   authored from taxonomy descriptions and the development split only. A Jev failure for a case is decided by
   `modelUnavailable` and counted as a provider error.
4. **Offline mode is record/replay of a real run.** The opt-in live run (`JEV_BENCHMARK_LIVE=true` plus
   `JEV_API_KEY`) wraps the real adapter in a recorder that writes `jev-responses.jsonl` (message SHA-256, resolved
   model, usage, latency, attempts, typed answers or error class; no message text, no labels, no case IDs). Offline,
   a replay port serves those answers by message SHA-256 and fails on a missing recording. The default build
   regenerates the committed report from the committed recordings and requires byte-identical artifacts.
5. **Calibration split only.** Threshold exploration accepts a `CalibrationSet` that can only be built from
   `GoldSplit.DEV` cases. It sweeps one threshold at a time (grid 0.00–1.00, step 0.05) over a copy of policy `v1`,
   reports coverage vs wrong auto-route rate and related rates, and names a per-threshold candidate as the highest
   coverage with wrong auto-route rate at most an illustrative target (0.10); ties go to the lower wrong auto-route
   rate, then to the least restrictive value. Candidates are applied jointly and
   checked once on the held-out split. They are **not** written into policy `v1`; FEATURE-007 owns the calibrated
   policy.
6. **Cost as run parameter.** Price per million input/output tokens and its source/date are run parameters
   (`JEV_PRICE_INPUT_USD_PER_MTOK`, `JEV_PRICE_OUTPUT_USD_PER_MTOK`, `JEV_PRICE_SOURCE`); without them cost is
   reported as not estimated. No price appears in runtime code or policy.
7. **Report contents.** `reports/evaluation/<runId>/` holds `report.md`, `metrics.json`, formal-type confusion CSVs,
   per-case CSVs (case ID, split, predictions, raw signals; no message text, no per-case gold labels),
   `threshold-sweep.csv` and the recordings. Run metadata records dataset ID/version, both file SHA-256 values
   verified against the manifest, taxonomy/policy/question-set versions, requested alias and resolved models.

## Alternatives considered
### Option A — Label-driven or keyword-simulated fake Jev for offline runs
- Pros: no live dependency at all.
- Cons: label-driven fakes leak ground truth into "model" output and inflate scores; simulated fakes measure the
  simulator, not Jev. Rejected; fakes remain only for unit tests of failure paths.

### Option B — Question set and mapper in test scope
- Pros: smallest runtime diff.
- Cons: FEATURE-006/007 would have to move them, and the benchmark would no longer measure the runtime contract.
  Rejected.

### Option C — Separate evaluation Maven module or profile
- Pros: stronger physical separation.
- Cons: restructures the build for two baselines; test scope already keeps evaluation code out of the jar. Deferred
  to FEATURE-013 if needed.

### Option D — Joint grid search over all thresholds
- Pros: finds interactions.
- Cons: overfits 165 calibration cases and belongs to the calibrated-policy decision (FEATURE-007). Rejected for
  this ticket.

## Consequences
- Positive: evidence-based baseline reproducible offline in CI; the runtime gains a reusable, versioned question set
  and a strict mapper; held-out labels cannot reach threshold exploration by construction.
- Negative: committed recordings pin one live run; a question-set or goldset change requires a new live run and
  `runId`.
- Risks: small dataset, single author, `jev-latest` drift, illustrative wrong-auto-route target.
- Mitigations: resolved model and hashes recorded, limitations section in every report, candidates labelled
  provisional.

## Rollout / rollback
- Rollout: additive; no runtime behaviour change (no bean uses the question set yet).
- Rollback: revert the PR; no data or configuration migration.

## Links
- PRD: `docs/prd/FEATURE-005.prd.md`
- Plan: `docs/plan/FEATURE-005.md`
- Tasklist: `docs/tasklist/FEATURE-005.md`
