# FEATURE-002: Implementation plan
Status: PLAN_APPROVED

## Overview
Add a provider-neutral `JevClassificationPort` (domain) and a JDK-HttpClient adapter in
`infrastructure.typesafe` for `POST /v1/systemone`. One call carries several named
Choice/Noul/Score questions; the result returns typed answers plus resolved model, token usage,
question-set version, latency and attempt count. Bounded retry for 429/5xx/transient; terminal
errors for 400/401/403/422 and malformed answers. Offline contract tests with a JDK
`HttpServer`; opt-in live smoke. No taxonomy, routing or task logic.

## Assumptions
- Decisions in `docs/adr/FEATURE-002-jev-port-and-http-client.md`.
- Maven runs use `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (default shell JDK is 21).
- Fixtures are schema-derived synthetic (no live key available); live smoke refreshes confidence in them when run.
- Work is on `feature/FEATURE-002-jev-client` in the main checkout (same constraint as FEATURE-001: write hooks are limited to the project root).

## Architecture / Flow
```
caller -> JevClassificationPort.classify(JevClassificationRequest)
       -> JevHttpClassificationAdapter
            JevRequestMapper  (domain request -> JSON body)
            retry loop (attempt, status classify, backoff via Sleeper)
            JevResponseParser (JSON -> typed answers, strict validation)
       <- JevClassificationResult | JevClientException | JevUnavailableException | JevProtocolException
```
Packages:
- `domain.model`: `JevQuestion` (sealed: `ChoiceQuestion`, `NoulQuestion`, `ScoreQuestion`), `JevQuestionSet(version, questions)`, `JevClassificationRequest(message, channel, questionSet)`, `JevAnswer` (sealed: `ChoiceAnswer`, `NoulAnswer`, `ScoreAnswer`), `JevUsage`, `JevClassificationResult`.
- `domain.port`: `JevClassificationPort`, `JevException` base (+ `JevClientException`, `JevUnavailableException`, `JevProtocolException`).
- `infrastructure.typesafe`: `JevProperties` (`@ConfigurationProperties("triage.jev")`), `JevConfiguration` (conditional bean), `JevRequestMapper`, `JevResponseParser`, `JevHttpClassificationAdapter`, `Sleeper`.
- Config: `application.yml` gains `base-url`, `model`, timeouts, `max-attempts`, backoffs; `.env.example` adds `JEV_LIVE_SMOKE`.

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | TypeSafe already a documented external system | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | No new deployable/store | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): adapter never decides routing; outputs are untrusted inputs for the later Policy Engine. No graph code.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): Jev via port; bounded attempts/timeouts act as cost guard; default CI offline; live smoke opt-in.
- Abstention / human-review paths covered: missing/invalid answers raise typed errors (later mapped to `HUMAN_REVIEW`/`MODEL_UNAVAILABLE`), never default confidence; tested.
- Synthetic-data and goldset-isolation impact: only English synthetic messages; no goldset exists.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: none (Java port only).
- Request/input: `JevClassificationRequest` (non-blank message, optional channel, question set with >=1 questions; Choice 1..255 criteria, Score 1..10 levels).
- Response/output: `JevClassificationResult`.
- Error behavior: `JevClientException(status, detail)`, `JevUnavailableException(attempts, cause/status)`, `JevProtocolException(reason)`; `IllegalArgumentException` for invalid local input before any network call. No customer text or key in messages.

### Outbound contracts
- Integration: `POST {base-url}/v1/systemone`, headers `Authorization: Bearer`, `Content-Type: application/json`; body `{model, state:{customerMessage, channel?}, questions}`. Score criteria sent as array, Choice as object, Noul `instructions` only.
- Timeout: connect and request timeouts configurable (defaults 5s / 30s).
- Retry: 429, 5xx, IOException, timeout; max attempts default 3; backoff initial 200ms doubling to cap 2s; numeric `Retry-After` honoured up to cap.
- Idempotency: inference is read-style so retries are safe; a retried call may be billed again (documented).

## Data changes
- Schema changes: none. Migration/backfill: none. Indexes/partitions: none. Rollback/forward fix: revert PR.

## Reliability
- Timeout strategy: explicit connect/request timeouts; no unbounded waits.
- Retry/backoff strategy: as above; `InterruptedException` restores the interrupt flag and aborts as unavailable.
- Duplicate handling: not applicable (stateless).
- Ordering assumptions: none.
- Partial failure behavior: any missing/malformed answer fails the whole call (no partial result).

## Security & Privacy
- AuthN/AuthZ: Bearer key from `JEV_API_KEY` env only; never logged or put in exceptions.
- Input validation: local validation of request before network; strict response validation (types, ranges [0,1], choice among requested criteria, score legend/probability keys).
- Sensitive data/logging: log only status, attempt, latency, model, token counts; never message text or response body; tests assert exception/log text excludes key and message.
- Audit trail: usage + resolved model returned for upstream trace (FEATURE-012).

## Observability
- Logs: key=value metadata at INFO/WARN per attempt outcome.
- Metrics: none now (deferred to FEATURE-012); result exposes latency/usage.
- Traces/correlation: none yet; MDC keys reserved for later tickets.
- Alerts/runbook: README live-smoke section with billing note.

## Testing strategy
- Unit tests: `JevRequestMapperTest`, `JevResponseParserTest` (valid per type, each malformed case), `JevPropertiesTest`, request validation tests.
- Integration/contract tests: `JevHttpClassificationAdapterTest` against JDK `HttpServer` with fixtures `src/test/resources/jev/*.json` (success multi-type, 400, 401, 422, 429-then-200, 503 exhausted, timeout, malformed/missing answer, Retry-After, no-secret-in-errors, header/path/body assertions); Spring wiring test for conditional bean (disabled default; enabled+blank key fails).
- Regression tests: `ArchitectureBoundaryTest` stays green; no port-bypassing imports.
- Manual checks: opt-in `JevLiveSmokeTest` (`JEV_LIVE_SMOKE=true`, `JEV_API_KEY`); reported as NOT EXECUTED unless a key is supplied. Full `./mvnw -B verify` on JDK 25.

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: required
Docs update: required
Contract review: required
DB review: not-required
Reliability review: required

### Rationale
- Review: Always required.
- QA: Always required; focused on parsing/error/retry matrix and keyless startup.
- Security review: Handles API key, outbound calls to a cloud provider, and logging of text.
- Docs update: README live-smoke/billing note, contracts.md parsing notes, ADR link.
- Contract review: Consumes an external provider schema and defines new domain port types.
- DB review: No database or migrations.
- Reliability review: Timeouts, retries, backoff and failure semantics are introduced.

## Rollout plan
- Deployment order: merge PR; FEATURE-005/007 consume the port.
- Feature flags/config: `triage.jev.enabled=false` default.
- Backward compatibility: additive.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-002-jev-port-and-http-client.md`
- Baseline: ADR-002, ADR-003, ADR-006.

## Open Questions
- None blocking. Provider 401/403/429/5xx behaviour to be confirmed by live smoke.
