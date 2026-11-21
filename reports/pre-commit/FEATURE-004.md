# FEATURE-004: Pre-commit verification
Status: PRE_COMMIT_PASSED
Outcome: PRE_COMMIT_PASSED

## Command
`python3 scripts/aidd_verification.py --ticket FEATURE-004 run` (JDK 25 at `/usr/lib/jvm/temurin-25`, Maven Wrapper 3.9.11)

## Checks covered
- Repository guardrails (`scripts/pre-commit-check.sh`) and root `./mvnw verify`: 214 tests, 0 failures, 0 errors, 1 skipped (opt-in `JevLiveSmokeTest`).
- New suites: `GoldsetLoaderTest` (strict parse and 29 invalid-record rows), `GoldsetSplitterTest`, `GoldsetValidatorTest`, `GoldsetContractTest` (frozen v1 dataset), `GoldsetIsolationTest` (incl. planted-leak detection and ArchUnit rule); `ArchitectureBoundaryTest` and `StartupHealthTest` green.
- Dataset tool: `python3 tools/test_goldset.py` (17 tests, OK) and `python3 tools/goldset.py verify samples/english-goldset/v1` (`[OK]`, dev 165, test 75), run separately because the wrapper does not cover `tools/`.
- Profile `full`, fingerprint 10cfe2a7c3110e8f8bb7ba0c0bc908ae83ba38c46d50023a555ede2480bb8bb9.
- `python3 scripts/aidd_verification.py --ticket FEATURE-004 check` reports current PASS evidence.

## Non-blocking warnings
- `gitleaks` and `shellcheck` are not installed in this environment (optional locally; gitleaks is enforced in CI). No shell scripts changed.
- The new CI step (`tools/test_goldset.py`, `tools/goldset.py verify`) is verified by its pull-request run, not locally.
