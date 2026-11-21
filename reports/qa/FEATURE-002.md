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
- AC6 opt-in live smoke gated by env flag + key, README with billing note: PASS by inspection; NOT EXECUTED (no key supplied).
- AC7 no routing/taxonomy/task code, ArchUnit green, key/text never logged: PASS (tests assert exceptions exclude key, message and echoed text; logs are metadata only).

## Positive / negative / edge / regression
- Positive: success path, 429-then-200, 503-then-200, read timeout then success.
- Negative: each terminal status, exhausted 503, connection refused, non-JSON body, malformed Noul, missing answer, wrong type, out-of-range values, unknown choice, missing legend, null request.
- Edge: invalid `Retry-After`, `Retry-After` above cap, interrupted backoff restores interrupt flag, blank key/blank message/empty question set/size limits, keyless default startup, enabled+blank key fails with no secret in message.
- Regression: ArchitectureBoundaryTest and StartupHealthTest still green.

## Not executed
- Live Jev smoke test (no key); GitHub Actions run (branch not pushed).
