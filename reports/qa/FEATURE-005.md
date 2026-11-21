# FEATURE-005: QA
Status: QA_PASSED
Outcome: QA_PASSED

Evidence: deterministic check current PASS; pre-commit evidence 266 tests, 0 failures, 0 errors, 2 skipped (opt-in live tests). Verifier
re-ran the benchmark, mapper, question-set, runner, recording, threshold, metrics, isolation and architecture suites offline: pass.
Live provider calls by the verifier: none (committed run replayed).

## Acceptance mapping
- AC1 standalone harness for RULES_ONLY and JEV_ONLY: `BaselineRunner`, `BaselineRunnerTest`, reproducibility test. Pass.
- AC2 offline replay without key, live opt-in: `JevRecording` replay, `JevBaselineLiveBenchmarkTest` gated by env and call budget. Pass.
- AC3 macro-F1, complaint recall, confusion, abstention, OOT success: `BenchmarkMetricsTest` (hand-computed), committed CSV/metrics. Pass.
- AC4 Choice probability/confidence/margin and Companion Noul separate: per-case CSV columns and report signal summaries. Pass.
- AC5 calibration on DEV only: `CalibrationSet` guard, `ThresholdExplorerTest`; held-out only re-decides candidate policy. Pass.
- AC6 tokens, latency, cost with alias, resolved model, question-set version: `CostSummaryTest`, report section. Pass.
- AC7 English Markdown/CSV with dataset hashes and limitations: `report.md` hashes and Limitations section. Pass.

## Scenarios
- Positive: byte-for-byte regeneration of all report files from `jev-responses.jsonl` and `run.json`.
- Negative/edge: provider error to `MODEL_UNAVAILABLE`; mapper missing/mistyped/unknown-key/wrong-version; missing or duplicate recording;
  non-DEV calibration rejected; no-candidate threshold; price absent; undefined ratios reported as `n/a`.
- Regression: goldset isolation and architecture boundary suites green; goldset unchanged.

## Findings
No Blocking or Important findings.
