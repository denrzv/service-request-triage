# FEATURE-002: Tasklist
Status: TASKLIST_READY

References:
- PRD: `docs/prd/FEATURE-002.prd.md`
- Research: `docs/research/FEATURE-002.md`
- Plan: `docs/plan/FEATURE-002.md`

All Maven commands use `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.

## Tasks
- [x] TASK-1: Domain model and port
  - Goal: Add provider-neutral typed question/answer/result records, the `JevClassificationPort` and the exception hierarchy.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-002.md (Integrations, Constraints)
  - Requirements:
    - PRD: AC2, AC3, AC4, AC7
    - Plan: `Architecture / Flow`, `Contracts`
  - Target files:
    - `src/main/java/io/github/denrzv/triage/domain/model/JevQuestion.java` (+ `ChoiceQuestion`, `NoulQuestion`, `ScoreQuestion`)
    - `src/main/java/io/github/denrzv/triage/domain/model/JevQuestionSet.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/JevClassificationRequest.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/JevAnswer.java` (+ `ChoiceAnswer`, `NoulAnswer`, `ScoreAnswer`)
    - `src/main/java/io/github/denrzv/triage/domain/model/JevUsage.java`, `JevClassificationResult.java`
    - `src/main/java/io/github/denrzv/triage/domain/port/JevClassificationPort.java`, `JevException.java`, `JevClientException.java`, `JevUnavailableException.java`, `JevProtocolException.java`
  - Reference files: Not applicable
  - Test files:
    - `src/test/java/io/github/denrzv/triage/domain/model/JevModelValidationTest.java`
  - Expected changes:
    - Records validate invariants in compact constructors (non-blank message, >=1 question, Choice 1..255 criteria, Score 1..10 levels, probabilities and confidence within [0,1], answers immutable copies).
    - `NoulAnswer` has no confidence field.
  - Acceptance:
    - AC1: Invalid local input throws `IllegalArgumentException` with messages that exclude message text.
    - AC2: Domain package imports no Spring/Jackson/HTTP types (ArchUnit green).
  - Validation:
    - Fast: `./mvnw -q -Dtest=JevModelValidationTest,ArchitectureBoundaryTest test`
    - Broader (only if needed): Not applicable
  - Dependencies: none
  - Out of scope: HTTP, JSON, taxonomy mapping, routing
  - Notes: No string literal duplication; keep each method complexity <= 15.
  - Completion notes: Added sealed `JevQuestion`/`JevAnswer` hierarchies, `JevQuestionSet`, request/result/usage
    records and package-private `Checks` in `domain.model`; `JevClassificationPort` and the
    `JevException` hierarchy in `domain.port`. Score answers keep per-level probabilities as a list (legend is a
    request echo and validated in the parser). `JevClassificationRequest.toString` omits the message.
    Checks: `./mvnw -q -Dtest=JevModelValidationTest,ArchitectureBoundaryTest test` on JDK 25 passed.

- [x] TASK-2: Configuration properties and conditional wiring
  - Goal: Add validated `JevProperties`, extend `application.yml`/`.env.example`, and a conditional configuration that builds the adapter only when enabled.
  - Size: S
  - Risk: standard
  - Evidence: docs/research/FEATURE-002.md (Auth/security)
  - Requirements:
    - PRD: AC1, AC7
    - Plan: `Outbound contracts`, `Rollout plan`, ADR decision 5
  - Target files:
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevProperties.java`
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevConfiguration.java`
    - `src/main/resources/application.yml`
    - `.env.example`
  - Reference files: `src/main/resources/application.yml`
  - Test files:
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevPropertiesTest.java`
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevConfigurationTest.java`
  - Expected changes:
    - `JevProperties`: enabled, apiKey, baseUrl (default `https://api.typesafe.ai`), model (`jev-latest`), connectTimeout, requestTimeout, maxAttempts, initialBackoff, maxBackoff; validation of positive/ordered values; `toString` omits the key.
    - `JevConfiguration`: `@ConditionalOnProperty(triage.jev.enabled=true)`; blank key fails startup; default disabled leaves the app keyless-healthy.
  - Acceptance:
    - AC1: Default context has no `JevClassificationPort` bean and `StartupHealthTest` stays green.
    - AC2: enabled + blank key fails with a message that has no secret; enabled + key creates the bean.
  - Validation:
    - Fast: `./mvnw -q -Dtest=JevPropertiesTest,JevConfigurationTest,StartupHealthTest test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-3 (adapter class needed by the configuration; implement the adapter constructor first or stub then complete)
  - Out of scope: Micrometer metrics
  - Notes: Constructor injection only; no `@Value`.
  - Completion notes: `JevProperties` (created in TASK-4) is bound via `@EnableConfigurationProperties` in
    `JevConfiguration`; the `JevClassificationPort` bean exists only with `triage.jev.enabled=true` and fails startup with a
    key-free message when `JEV_API_KEY` is blank. `application.yml` documents all defaults; `.env.example` adds `JEV_LIVE_SMOKE`.
    Checks: `./mvnw -q -Dtest=JevPropertiesTest,JevConfigurationTest,StartupHealthTest test` on JDK 25 passed (surefire reports 4/3/3 tests, 0 failures).

- [x] TASK-3: Request mapper and strict response parser
  - Goal: Map the domain request to the System One JSON body and parse/validate the response into typed answers.
  - Size: M
  - Risk: high (provider protocol correctness; must never default missing data)
  - Evidence: docs/research/FEATURE-002.md (Integrations, schema)
  - Requirements:
    - PRD: AC2, AC3, AC7
    - Plan: `Outbound contracts`, `Security & Privacy` (strict validation)
  - Target files:
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevRequestMapper.java`
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevResponseParser.java`
  - Reference files: Not applicable
  - Test files:
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevRequestMapperTest.java`
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevResponseParserTest.java`
    - `src/test/resources/jev/success-multi-type.json`, `malformed-*.json`
  - Expected changes:
    - Mapper emits `model`, `state{customerMessage, channel?}`, `questions` (choice criteria object, score criteria array, noul instructions only).
    - Parser validates: required top-level `model`/`answers`/`usage`, answer present for every question, `type` matches, Choice has `choice` in requested criteria + `confidence` + `probabilities`, Noul has `noul` only, Score has `score` + `confidence` + `legend` + `probabilities` with matching keys, all probabilities within [0,1]; unknown extra fields tolerated; violations throw `JevProtocolException` without body text.
  - Acceptance:
    - AC1: Golden success fixture parses to the expected typed answers, model, usage.
    - AC2: Each malformed fixture (missing answer, wrong type, missing probabilities, out-of-range, unknown choice, score without legend, empty body, non-JSON) raises `JevProtocolException`.
  - Validation:
    - Fast: `./mvnw -q -Dtest=JevRequestMapperTest,JevResponseParserTest test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-1
  - Out of scope: HTTP, retries
  - Notes: Fixtures are schema-derived synthetic; label them so in a fixture README comment or test Javadoc. Split helper methods to keep complexity <= 15.
  - Completion notes: Added `JevWire` (wire constants), `JevRequestMapper` (Choice object / Score array / Noul
    instructions only) and strict `JevResponseParser` (returns `Parsed(answers, model, usage)`; every failure is a
    `JevProtocolException` without response text; extra fields and unrequested answers tolerated). Tests use
    `JevTestData` plus the schema-derived `success-multi-type.json` and 16 parameterized malformed mutations
    (malformed cases mutate the golden fixture instead of separate files).
    Checks: `./mvnw -q -Dtest=JevRequestMapperTest,JevResponseParserTest test` on JDK 25 passed.

- [x] TASK-4: HTTP adapter with bounded retry
  - Goal: Implement `JevHttpClassificationAdapter` using the JDK `HttpClient` with timeouts, status classification, bounded backoff and latency measurement.
  - Size: M
  - Risk: high (retry/timeout semantics; secrets in errors)
  - Evidence: docs/research/FEATURE-002.md (Constraints)
  - Requirements:
    - PRD: AC1, AC3, AC4, AC7
    - Plan: `Reliability`, `Outbound contracts`, ADR decisions 2-4
  - Target files:
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevHttpClassificationAdapter.java`
    - `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/Sleeper.java`
  - Reference files: Not applicable
  - Test files: covered in TASK-5
  - Expected changes:
    - POST to `{baseUrl}/v1/systemone` with Bearer and JSON headers.
    - 200 -> parser; 400/401/403/422 -> `JevClientException(status, safeDetail)`; 429/5xx/IOException/timeout -> retry up to `maxAttempts` with capped exponential backoff and numeric `Retry-After`; exhaustion -> `JevUnavailableException(attempts)`; other statuses -> `JevClientException`.
    - Interrupt restores flag and aborts. Latency measured with `System.nanoTime` across all attempts; attempt count in result. Logs metadata only.
  - Acceptance:
    - AC1: Exceptions and logs never contain the API key or customer message.
    - AC2: Methods stay within complexity 15 (extract helpers).
  - Validation:
    - Fast: `./mvnw -q -DskipTests compile`
    - Broader (only if needed): see TASK-5
  - Dependencies: TASK-1, TASK-3
  - Out of scope: Micrometer, circuit breaker
  - Notes: `detail` from 400/422 bodies is truncated and sanitised (it may echo input previews); prefer reporting only `loc`/`type` for 422.
  - Completion notes: Added `JevHttpClassificationAdapter` (JDK `HttpClient`, bounded retry loop, injectable `Sleeper` and
    nano clock), `Sleeper` and `JevProperties` (pulled forward from TASK-2 because the adapter needs it; TASK-2 still owns
    YAML, wiring and its tests). 2xx parsed strictly; 429/5xx/IOException/timeout retried with capped exponential backoff or
    numeric `Retry-After`; all other statuses terminal. 400 detail is withheld, 401/403 generic, 422 reports only `loc`/`type`.
    Checks: `./mvnw -q -DskipTests compile` on JDK 25 passed; behaviour is covered by TASK-5 tests.

- [x] TASK-5: Offline contract tests with JDK HttpServer
  - Goal: Prove the adapter against a local fake server and fixtures for every success/error/retry path.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-002.md (Existing tests)
  - Requirements:
    - PRD: AC1, AC2, AC3, AC4, AC5, AC7
    - Plan: `Testing strategy`
  - Target files:
    - Not applicable (tests only)
  - Reference files: Not applicable
  - Test files:
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/FakeJevServer.java`
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevHttpClassificationAdapterTest.java`
    - `src/test/resources/jev/error-400.json`, `error-422.json`
  - Expected changes:
    - Cases: multi-type success (asserts path, method, Bearer header, body shape, model/usage/latency/attempts/version); 400, 401, 403, 422 terminal with single request; 429 then 200; 503 exhausted after `maxAttempts` with recorded sleeps bounded by the cap; `Retry-After`; read timeout then success; malformed answer is not retried; no secret/message in exception text.
  - Acceptance:
    - AC1: All cases pass offline with no key.
  - Validation:
    - Fast: `./mvnw -q -Dtest=JevHttpClassificationAdapterTest test`
    - Broader (only if needed): `./mvnw -B verify`
  - Dependencies: TASK-4
  - Out of scope: live calls
  - Notes: Injected `Sleeper` records delays; no real sleeping.
  - Completion notes: Added `FakeJevServer` (JDK `HttpServer`, scripted replies, recorded requests) and
    `JevHttpClassificationAdapterTest` (18 cases): request path/method/Bearer/body shape, typed result with model/usage/version/
    attempts/latency, terminal 400/401/403/404/422 (single request, no key/message/echoed text), 429 then 200, 503 exhaustion with
    capped backoff, Retry-After (numeric, capped, invalid), read timeout retry, connection refused, protocol errors not retried,
    interrupt handling, backoff cap, null request. Error fixtures: `error-400.json`, `error-422.json`.
    Checks: `./mvnw -q -Dtest=JevHttpClassificationAdapterTest test` on JDK 25 passed (18 tests, 0 failures).

- [x] TASK-6: Opt-in live smoke test
  - Goal: Add a skipped-by-default live test sending one English synthetic message.
  - Size: S
  - Risk: standard (incurs provider billing when run)
  - Evidence: PRD AC6
  - Requirements:
    - PRD: AC6
    - Plan: `Testing strategy` manual checks
  - Target files:
    - `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevLiveSmokeTest.java`
  - Reference files: Not applicable
  - Test files: same
  - Expected changes:
    - `@EnabledIfEnvironmentVariable(named = "JEV_LIVE_SMOKE", matches = "true")` plus non-blank `JEV_API_KEY` assumption; one message, one Choice + one Noul + one Score question; asserts typed shape only, not accuracy; prints model/usage/latency, never the key.
  - Acceptance:
    - AC1: Default `verify` reports it skipped.
  - Validation:
    - Fast: `./mvnw -q -Dtest=JevLiveSmokeTest test` (skipped)
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-4
  - Out of scope: executing it without a user-provided key
  - Notes: Report as NOT EXECUTED unless a key is supplied.
  - Completion notes: Added `JevLiveSmokeTest` gated by `@EnabledIfEnvironmentVariable(JEV_LIVE_SMOKE=true)` plus a
    non-blank `JEV_API_KEY` assumption; optional `JEV_MODEL` override. One synthetic English message, one Choice, Noul and Score
    question; asserts shape only and prints model/usage/latency (never the key).
    Checks: `./mvnw -q -Dtest=JevLiveSmokeTest test` on JDK 25: 1 test, skipped (default). Live run NOT EXECUTED: no key supplied.

- [x] TASK-7: Documentation
  - Goal: Document configuration, error semantics, live smoke and billing note.
  - Size: S
  - Risk: standard
  - Evidence: PRD AC6
  - Requirements:
    - PRD: AC1, AC4, AC6
    - Plan: `Quality gate impact` (Docs update)
  - Target files:
    - `README.md`
    - `docs/contracts.md`
  - Reference files: `README.md`
  - Test files: Not applicable
  - Expected changes:
    - README: Jev adapter section (properties/env, retry/error table, exact smoke command, billing/data-sharing note, test status).
    - contracts.md section 3: note on `JevClassificationPort` as lower-level port beneath `ClassificationModelPort`, verified response schema, undocumented statuses.
  - Acceptance:
    - AC1: Commands documented match those executed; unexecuted items are marked as such.
  - Validation:
    - Fast: `python3 scripts/validate-workflow.py --ticket FEATURE-002`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-5, TASK-6
  - Out of scope: new diagrams
  - Notes: contracts.md is not an OpenAPI file, so no file rename/version bump.
  - Completion notes: README gained a "Jev adapter (FEATURE-002)" section (properties, error/retry table, undocumented-status
    caveat, opt-in smoke command with billing and data-sharing note); `docs/contracts.md` section 3 records the implemented port,
    verified schema details and port layering. Tests executed: `./mvnw -B verify` on JDK 25, 67 tests, 0 failures, 1 skipped
    (`JevLiveSmokeTest`); the live smoke run and the GitHub Actions run are NOT EXECUTED.

## Diagram tasks
- None.

## Review / QA fixes
- None yet. `/fix-tasks` appends implementation-ready `TASK-FIX-N` entries here
  for unresolved Blocking or Important findings.

## Completion notes
-
