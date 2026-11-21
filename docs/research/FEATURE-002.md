# FEATURE-002: Research note
Status: RESEARCH_READY

## Repo map
- `pom.xml`: Boot 3.5.16 parent, Spring AI 1.1.2 + SAA 1.1.2.0 BOMs, `spring-boot-starter-web` (brings Jackson, `RestClient`, JDK HTTP client support), actuator, graph-core, ArchUnit 1.5.1; Enforcer requires JDK >= 25.
- `src/main/java/io/github/denrzv/triage/`: only `TriageApplication` and `package-info.java` skeletons for `api`, `application`, `graph`, `domain.model`, `domain.port`, `infrastructure.{typesafe,deepseek,taxonomy,persistence,taskmanagement,observability}`. No domain code yet.
- `src/main/resources/application.yml`: `triage.jev.enabled=false`, `triage.jev.api-key=${JEV_API_KEY:}`; structured logstash logging; actuator exposes health/info.
- `.env.example` has `JEV_API_KEY=` placeholder.
- Tests: `ArchitectureBoundaryTest` (domain/application must not depend on outer layers; infrastructure free to depend on domain), `StartupHealthTest`, `GraphDependencySmokeTest`.
- Docs: ADR-002 (Jev primary, `JevClassificationPort`, RestClient/HttpClient), ADR-006 (separate ports per protocol), `docs/contracts.md` sections 2-3 (ports, request/response shapes), `conventions.md` (constructor injection, validated `@ConfigurationProperties`, no `@Value`, opt-in live smoke, never log customer text).

## Entry points
- REST/API: none for this ticket.
- Message/event consumers: none.
- Schedulers/jobs: none.
- UI or CLI: none. Consumers will be FEATURE-005 benchmark runner and FEATURE-007 graph node via the domain port.

## Data model touch points
- Entities/tables: none.
- Repositories/queries: none.
- Migrations: none.
- New domain value types (records) in `domain.model` and a port in `domain.port`; no persistence.

## Integrations
- Outbound REST/RPC: `POST {baseUrl}/v1/systemone`, `Authorization: Bearer <key>`, JSON. Official OpenAPI 0.2.0 (fetched 2026-11-21 from docs.typesafe.ai/api/system-one):
  - Request: `model` (required), `state` (required; string/object/array), `questions` (required map, >=1; each `type` in `noul|choice|score|box`), optional `assets`.
  - Question shapes: `noul` {instructions?, criteria?{true,false}}; `choice` {instructions?, criteria: map name->description, 1..255}; `score` {instructions?, criteria: ARRAY of 1..10 levels}.
  - Response 200: `model`, `answers` (keyed by question name, `type` matches), `usage{input_tokens,output_tokens}`.
  - Answers: `noul` -> {type, noul}; `choice` -> {type, choice, confidence, probabilities}; `score` -> {type, score (fractional, probability-weighted index), confidence, legend{"0".."n"}, probabilities{"0".."n"}}.
  - Errors documented: 400 `{detail: string|object}`, 422 `{detail:[{loc,msg,type,input?,ctx?}]}`. NOT documented: 401/403/429/5xx bodies, `Retry-After`, rate limits, request-id header.
  - Optional headers `TypeSafe-Danger-Zone-*` only for images/long context: out of scope.
- Messaging topics/queues: none.
- Cache/storage: none.
- External systems: TypeSafe cloud (billed per input token; output tokens documented as free). Data sent is English synthetic text only.

## Existing tests
- Architecture tests will constrain new code: domain types must not import infrastructure/Spring HTTP; infrastructure may depend on domain. No existing test infra for HTTP.
- Mock HTTP options: JDK built-in `com.sun.net.httpserver.HttpServer` (zero dependency, works on JDK 25) vs OkHttp `mockwebserver` 4.12.0 (present in local `~/.m2` only through Boot dependency management, not on the project classpath) vs WireMock (not available). Recommendation: JDK `HttpServer`, to avoid new dependencies/supply-chain surface.
- Retry: `spring-retry` 2.0.13 is transitive only; a small hand-written bounded loop with an injectable sleeper and clock is simpler to test deterministically and avoids relying on a transitive dependency.

## Constraints discovered
- Timeouts: none configured yet; add connect/read timeouts (JDK `HttpClient` connect timeout + request timeout, or `JdkClientHttpRequestFactory`).
- Retries: retry only 429, 5xx, `IOException`/timeout; never 400/401/403/422. Honour numeric `Retry-After` capped by max backoff; since undocumented, treat as optional.
- Idempotency/deduplication: the System One call is a pure read-style inference; retries are safe (but a retried call may be billed again; document).
- Transactions/consistency: none.
- Auth/security: `JEV_API_KEY` env only; adapter must be inactive when `triage.jev.enabled=false` or key blank so the app still starts (FEATURE-001 AC4/edge 1). Do not log key, `state`, or response bodies containing text; exception messages must exclude both.
- Observability: log key=value (status, attempt, latency, model, tokens); no Micrometer requirement yet.
- Build: default shell JDK is 21; JDK 25 is at `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (README documents it). All Maven runs must set `JAVA_HOME` to it.
- Style: SonarQube rules from user config (no duplicated literals, cognitive complexity <= 15, no gratuitous boolean expressions).
- Contract gap: `contracts.md` `ClassificationModelPort.classify(request, taxonomy)` needs `Taxonomy`/`SemanticAssessment` from FEATURE-003/007, which do not exist. This ticket therefore ships the lower-level `JevClassificationPort` (ADR-002) over generic typed questions/answers; the semantic mapping is a later layer.

## Risks & unknowns
- Undocumented 401/403/429/5xx behaviour: handle by status class; verify with opt-in live smoke (not run in CI; report as not executed unless a key is provided).
- Jackson polymorphic parsing of `answers` by `type` must reject missing/mistyped fields rather than default to 0 (use explicit validation after tree parse; `FAIL_ON_NULL_FOR_PRIMITIVES`-style strictness).
- Javadoc/record naming must avoid leaking provider terms into domain beyond Choice/Noul/Score concepts.
- Fixtures are hand-built from the documented schema (no captured live response available without a key): label them "schema-derived synthetic" and refresh from the live smoke when run.
- Alias drift (`jev-latest`): mitigated by recording resolved `model`.

## Pointers
- `docs/contracts.md` section 3: request/response illustration and parsing requirements.
- `docs/adr/README.md` ADR-002, ADR-006, ADR-003 (confidence is not truth): scope boundaries.
- `src/test/java/io/github/denrzv/triage/ArchitectureBoundaryTest.java`: layer rules to keep green.
- `src/main/resources/application.yml`, `.env.example`: config/secret placeholders to extend.
- `README.md` lines 56 and 75-92: JDK 25 setup and CI description to extend with smoke instructions.
