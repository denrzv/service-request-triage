# FEATURE-002: QA
Status: QA_PASSED
Outcome: QA_PASSED

## Evidence
- Deterministic: `aidd_verification.py check` PASS (full). Independent re-run on JDK 25: ArchitectureBoundaryTest 4, JevModelValidationTest 10, JevHttpClassificationAdapterTest 18, JevResponseParserTest 18, JevRequestMapperTest 5, JevPropertiesTest 4, JevConfigurationTest 3, StartupHealthTest 3, GraphDependencySmokeTest 1; 0 failures; `JevLiveSmokeTest` skipped.

## Acceptance criteria
- AC1 base URL/path/Bearer/model alias: PASS (`sendsDocumentedRequestAndParsesTypedResult` asserts method, path, headers, body; defaults in `application.yml`/`JevProperties`).
- AC2 multi-question Choice/Noul/Score typed parsing: PASS (fixture `success-multi-type.json`, parser and mapper tests, Score array vs Choice object).
- AC3 resolved model, usage, question-set version, latency (and attempts): PASS.
- AC4 terminal 400/401/403/422 (plus 404) not retried; 429/5xx/timeout/connect retried with capped backoff and `Retry-After`; exhaustion -> `JevUnavailableException`: PASS.
- AC5 offline contract tests with fixtures and local HTTP server, no key in CI: PASS.
- AC6 opt-in live smoke gated by env flag + key, README with billing note: PASS (live run executed post-merge on 2026-11-21, see below).
- AC7 no routing/taxonomy/task code, ArchUnit green, key/text never logged: PASS (tests assert exceptions exclude key, message and echoed text; logs are metadata only).

## Positive / negative / edge / regression
- Positive: success path, 429-then-200, 503-then-200, read timeout then success.
- Negative: each terminal status, exhausted 503, connection refused, non-JSON body, malformed Noul, missing answer, wrong type, out-of-range values, unknown choice, missing legend, null request.
- Edge: invalid `Retry-After`, `Retry-After` above cap, interrupted backoff restores interrupt flag, blank key/blank message/empty question set/size limits, keyless default startup, enabled+blank key fails with no secret in message.
- Regression: ArchitectureBoundaryTest and StartupHealthTest still green.

## Not executed (at QA time)
- Live Jev smoke test (no key); GitHub Actions run (branch not pushed).

## Post-merge live verification (2026-11-21)
- JDK 25.0.5.1 against `main` at `23094d4`: `JEV_LIVE_SMOKE=true ./mvnw -B verify` passed (67 tests, 0 failures, 0 skipped; `JevLiveSmokeTest` ran instead of being skipped). Live call: HTTP 200 on attempt 1, resolved model `jev-1.14.0` for alias `jev-latest`, 439 input / 78 output tokens, 557-640 ms latency over two runs.
- The key came from a cloud-session network secret that the egress proxy injects for `api.typesafe.ai`; the test was given the placeholder `JEV_API_KEY=proxy-injected`. The key was never in the repository, logs or test output.
- Still unverified against the live API: 401/403/429/5xx bodies and `Retry-After` (only the 200 path was exercised).

