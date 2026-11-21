# FEATURE-005: Pre-commit verification
Status: PRE_COMMIT_PASSED
Outcome: PRE_COMMIT_PASSED

## Command
`python3 scripts/aidd_verification.py --ticket FEATURE-005 run` (JDK 25 at `/usr/lib/jvm/temurin-25`, Maven Wrapper 3.9.11)

## Checks covered
- Repository guardrails (`scripts/pre-commit-check.sh`, incl. dev-secret leak check) and root `./mvnw verify`:
  266 tests, 0 failures, 0 errors, 2 skipped (opt-in `JevLiveSmokeTest` and `JevBaselineLiveBenchmarkTest`).
- New suites: `TriageQuestionSetsTest`, `JevAssessmentMapperTest`, `KeywordRulesAssessorTest`, `BaselineRunnerTest`,
  `JevRecordingTest`, `BenchmarkMetricsTest`, `CostSummaryTest`, `ThresholdExplorerTest`, `ReportWriterTest`,
  `BaselineBenchmarkReproducibilityTest` (offline replay of the committed run, byte-for-byte comparison);
  `GoldsetIsolationTest`, `ArchitectureBoundaryTest` and `StartupHealthTest` green.
- Dataset tool (not covered by the wrapper): `python3 tools/test_goldset.py` OK and
  `python3 tools/goldset.py verify samples/english-goldset/v1` OK; the goldset is unchanged.
- Profile `full`, fingerprint cdd600cdf5b8e4cbb9f9c4c9c6b09376cda9312435b8b931a3e838243756b5e7.
- `python3 scripts/aidd_verification.py --ticket FEATURE-005 check` reports current PASS evidence.

## Live run (opt-in, executed once)
`JEV_BENCHMARK_LIVE=true ... ./mvnw -q -Dtest=JevBaselineLiveBenchmarkTest test` on 2026-11-21: 240/240 calls HTTP
200 on the first attempt, resolved `jev-1.14.0`, 191572 input tokens; output committed under
`reports/evaluation/feature-005-jev-baseline/`.

## Non-blocking warnings
- `gitleaks` and `shellcheck` are not installed in this environment (gitleaks is enforced in CI). No shell scripts
  changed.
