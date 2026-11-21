# FEATURE-002: Jev API Integration
Status: PRD_READY

Issue: https://github.com/denrzv/service-request-triage/issues/2 (depends on FEATURE-001, merged).
Branch: `feature/FEATURE-002-jev-client`.

## Context / Problem
The project needs a typed adapter to the TypeSafe Jev System One decision API
(`POST https://api.typesafe.ai/v1/systemone`, ADR-002/ADR-006) so that FEATURE-005
(Jev baseline benchmark) and FEATURE-007 (confidence routing) can obtain typed
Choice / Noul / Score judgments about English synthetic messages. The
FEATURE-001 skeleton has only an empty `infrastructure/typesafe` package and
`JEV_API_KEY` / `jev` config placeholders. Jev is a typed decision API, not a
generative model: it returns no natural-language reasoning and makes no routing
decision.

## Goals
- Application-owned, provider-neutral domain port and typed value objects for a
  multi-question Jev request and its typed answers (Choice, Noul, Score).
- HTTP adapter in `infrastructure.typesafe` implementing that port with the
  Spring `RestClient`/JDK HTTP stack already on the classpath.
- Capture resolved model name, input/output token usage, caller-supplied
  question-set version and request latency on every successful call.
- Explicit error taxonomy: terminal (400/401/422 and malformed/absent answers) vs
  retryable (429/5xx/transient IO/timeouts) with bounded exponential backoff.
- Offline contract tests with captured synthetic fixtures; opt-in live smoke test.

## Non-goals
- Taxonomy, policy thresholds, `SemanticAssessment` mapping, `ClassificationPolicyEngine`
  (FEATURE-003/007). The adapter returns raw typed answers; it never decides routing.
- Task creation / Task Management (FEATURE-010), graph nodes (FEATURE-006), DeepSeek.
- Box questions, image assets, `TypeSafe-Danger-Zone-*` headers, multipart requests.
- The versioned production question set content (FEATURE-003/005); only a minimal
  synthetic example set is used for tests and the smoke test.
- Calibration of confidence; Choice `confidence` and class `probabilities` are carried through unchanged.

## Users & Roles
- Downstream feature developers (FEATURE-005/007) consuming the port.
- CI (offline) and developers/operators running the opt-in live smoke test.

## User Stories
- As the benchmark runner, I want one call returning several typed judgments so that cost and latency stay low.
- As the policy engine, I want absent/malformed answers to be explicit errors so that they route to human review, never to a default confidence.
- As a maintainer, I want the resolved model and usage recorded so that alias drift and cost are observable.

## Main Scenarios
1. Request with Choice + Noul + Score questions; response parsed into typed answers; model, usage, latency, question-set version returned.
2. Model alias (default `jev-latest`) configurable; response `model` stored as resolved model.
3. Bearer key read from `JEV_API_KEY`; adapter is disabled/unavailable without a key and the app still starts.
4. 429/5xx/transient failure retried up to a bounded attempt count with capped backoff (honouring `Retry-After` if present); success on a later attempt returns normally.

## Edge Scenarios
1. HTTP 400, 401, 403, 422: terminal error, no retry; 422 `detail` list and 400 `detail` surfaced without echoing the customer message or key.
2. Retries exhausted on 429/5xx/timeout: terminal "unavailable" error carrying attempt count.
3. 200 with an answer missing for a requested question, wrong `type`, missing required field (e.g. Choice without `probabilities`), probability/confidence outside [0,1], Choice `choice` not among requested criteria, or Score without a `legend`: provider-protocol error; never a default value.
4. Noul answers have no `confidence`; the typed model must not invent one.
5. Unknown extra response fields are tolerated; non-JSON or empty body is a protocol error.
6. Blank API key / blank message / empty question set rejected before any network call.
7. Request-time validation limits: Choice 1-255 criteria, Score 1-10 levels (array), at least one question.

## Acceptance Criteria
(Mirrors issue #2.)
- AC1: Adapter POSTs to the configurable base URL (default `https://api.typesafe.ai`) path `/v1/systemone` with `Authorization: Bearer ${JEV_API_KEY}`; model alias configurable via properties (default `jev-latest`).
- AC2: One request supports several named Choice/Noul/Score questions; the response is parsed per-type (`choice`+`confidence`+`probabilities`; `noul` only; `score`+`confidence`+`legend`+`probabilities`) into typed domain answers.
- AC3: Result records resolved model name, input/output token usage, question-set version and latency.
- AC4: HTTP 400/401/403/422 map to distinct terminal exceptions (no retry); 429, 5xx and transient I/O/timeouts are retried with bounded attempts and bounded backoff; exhaustion yields a typed error.
- AC5: Offline contract tests use captured synthetic fixtures (success, each error status, malformed answers) via a local mock HTTP server; no key needed in default CI.
- AC6: Opt-in live smoke test with one English synthetic message, enabled only by an explicit env flag plus key, documented in README with a provider billing note.
- AC7: Adapter contains no routing, taxonomy or task-creation logic; ArchUnit rules still pass (domain independent of infrastructure) and the key/message text are never logged.

## Non-functional requirements
- Security: key only from env, never logged or in exception messages; request body (customer text) not logged; TLS default.
- Privacy/data protection: only English synthetic text sent; README notes the cloud provider receives message text.
- Reliability: connect/read timeouts configurable; bounded retries (max attempts and max backoff configurable, small defaults); no unbounded loops.
- Performance/scale: single call for all questions; latency measured with a monotonic clock.
- Observability: log key=value metadata only (status, attempt, latency, model, token counts); Micrometer metrics optional/deferred to FEATURE-012.
- Compatibility: Java 25, Boot 3.5.x; Jackson records; no new heavy dependencies unless the plan justifies (mock HTTP server for tests).

## Data considerations
- Identifiers: question names chosen by caller; no triageId yet.
- Sensitive data: API key only.
- Retention/lifecycle: stateless adapter; fixtures synthetic.

## Constraints & Assumptions
- The Jev docs (OpenAPI 0.2.0) document only 200/400/422; 401/403/429/5xx bodies and `Retry-After` are undocumented, so handling is by status code and tolerant of any body. Verify with live smoke.
- Domain port name follows ADR-002 (`JevClassificationPort`); the contracts-doc `ClassificationModelPort` (taxonomy-aware, returns `SemanticAssessment`) is deferred to FEATURE-003/007 and will be layered over this port. Recorded as an ADR in the plan.
- Output tokens are documented as free; usage is still recorded.
- Response schema forbids assuming a request id; none is recorded.
- Version of question set is supplied by the caller and merely echoed.

## Risks
- Undocumented error/rate-limit behaviour (mitigate: status-code-based policy, configurable bounds).
- Alias drift (`jev-latest`): mitigated by recording resolved model.
- Accidental logging of customer text/key (mitigate: tests asserting exception/log content).
- Scope creep into taxonomy mapping (mitigate: non-goals).

## Open Questions
- None blocking. Assumptions above to be confirmed in the plan/ADR.
