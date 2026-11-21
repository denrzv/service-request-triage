# ADR: FEATURE-002 — Jev port shape, JDK HTTP client, bounded retry and offline test server
Status: ACCEPTED
Date: 2026-11-21

## Context
FEATURE-002 must expose typed Choice/Noul/Score judgments from `POST /v1/systemone`
through an application-owned port. `docs/contracts.md` shows a taxonomy-aware
`ClassificationModelPort.classify(request, Taxonomy) -> SemanticAssessment`, but
`Taxonomy`, `SemanticAssessment` mapping and policy thresholds belong to FEATURE-003/007.
ADR-002 already names `JevClassificationPort` and allows RestClient or Java HTTP
client. Jev's 401/403/429/5xx behaviour and `Retry-After` are undocumented. The
project forbids new dependencies without need and requires cloud-free CI.

## Decision
1. **Port:** add `domain.port.JevClassificationPort` operating on generic, provider-concept
   value types in `domain.model` (question set, typed answers, result metadata). It
   carries no taxonomy or routing semantics. The taxonomy-aware `ClassificationModelPort`
   is layered on top by FEATURE-003/007 and is out of scope.
2. **Errors:** unchecked exceptions in `domain.port` separate terminal client errors
   (400/401/403/422), retryable-exhausted unavailability (429/5xx/IO/timeouts), and
   protocol errors (missing/malformed answer). Absent data is never defaulted.
3. **HTTP client:** the adapter uses the JDK `java.net.http.HttpClient` with explicit
   connect and request timeouts and Jackson (`ObjectMapper` from Boot) for JSON. No new
   Maven dependency.
4. **Retry:** hand-written bounded loop: retry only 429, 5xx, `IOException` and timeouts;
   exponential backoff with cap, honouring numeric `Retry-After` up to the cap;
   `maxAttempts` default 3. Sleeper and clock are injectable for deterministic tests.
5. **Enablement:** bean is created only when `triage.jev.enabled=true`; with enabled and a
   blank key startup fails with a clear message (no silent mis-config). Default is disabled,
   so keyless startup (ADR FEATURE-001-keyless-startup-and-config) is unchanged.
6. **Offline tests:** JDK built-in `com.sun.net.httpserver.HttpServer` serves schema-derived
   synthetic fixtures. The live smoke test is gated by `JEV_LIVE_SMOKE=true` plus `JEV_API_KEY`.

## Alternatives considered
### Option A: Spring `RestClient` + spring-retry
- Pros: idiomatic Spring.
- Cons: retry library is only transitive; harder deterministic backoff tests; extra abstraction for one endpoint.

### Option B: implement `ClassificationModelPort` returning `SemanticAssessment` now
- Pros: matches contracts doc literally.
- Cons: forces inventing Taxonomy/question-set semantics owned by FEATURE-003/007; blocks parallel work.

### Option C: WireMock / OkHttp MockWebServer for tests
- Pros: richer matching.
- Cons: new test dependency and supply-chain surface; JDK `HttpServer` is sufficient.

## Consequences
- Positive: no new dependencies, provider-neutral contract usable by FEATURE-005/007, deterministic failure tests.
- Negative: a later mapping layer is required; retry logic is owned in-repo.
- Risks: undocumented provider error behaviour; a retried call may be billed twice.
- Mitigations: status-class based handling, small default attempts, live smoke documented, usage recorded.

## Rollout / rollback
- Rollout: merged disabled by default (`triage.jev.enabled=false`).
- Rollback: revert the PR; nothing else depends on the port yet.

## Links
- PRD: docs/prd/FEATURE-002.prd.md
- Plan: docs/plan/FEATURE-002.md
- Tasklist: docs/tasklist/FEATURE-002.md
