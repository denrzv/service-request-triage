# FEATURE-006: Pre-commit verification
Status: PRE_COMMIT_PASSED
Outcome: PRE_COMMIT_PASSED

## Command
`python3 scripts/aidd_verification.py --ticket FEATURE-006 run` (JDK 25 at `/usr/lib/jvm/temurin-25`, Maven Wrapper 3.9.11)

## Checks covered
- Repository guardrails (`scripts/pre-commit-check.sh`, incl. dev-secret leak check) and root `./mvnw verify`:
  338 tests, 0 failures, 0 errors, 2 skipped (opt-in `JevLiveSmokeTest` and `JevBaselineLiveBenchmarkTest`).
- New suites: `TriageExecutionTypesTest`, `TriageGraphStateCodecTest` (incl. state through the default SAA runtime),
  `InputValidatorTest`, `IntakeNodesTest`, `DecisionNodesTest`, `TriageGraphTest` (offline end-to-end runs of the
  real SAA graph with a fake Jev port); `ArchitectureBoundaryTest` (new graph port allow-list),
  `GraphDependencySmokeTest`, `GoldsetIsolationTest`, `BaselineBenchmarkReproducibilityTest` and
  `StartupHealthTest` green.
- Profile `full`, fingerprint f66e2e69ada542fd174dd31f2676f68c40a379ba1125547cd1ce3e7fab66a9a8.
- `python3 scripts/aidd_verification.py --ticket FEATURE-006 check` reports current PASS evidence.

## Live run
None. The graph adds no live provider path; the Jev adapter was live-smoked in FEATURE-002/005.

## Non-blocking warnings
- `gitleaks` and `shellcheck` are not installed in this environment (gitleaks is enforced in CI). No shell scripts
  changed.
