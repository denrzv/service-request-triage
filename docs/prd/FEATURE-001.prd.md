# FEATURE-001: Project Bootstrap
Status: PRD_READY

Issue: https://github.com/denrzv/service-request-triage/issues/1 (root issue, no dependencies).
Branch/worktree: `feature/FEATURE-001-project-bootstrap` in `../service-request-triage-feature-001`.

## Context / Problem
The repository contains only documentation and the AIDD workflow pack. All later
tickets (FEATURE-002/003/004 can then run in parallel) need a compilable Java 25 /
Spring Boot 3.5.x Maven project with a verified Spring AI 1.1.2 + Spring AI Alibaba
Graph 1.1.2.0 dependency set, the package boundaries from `docs/architecture.md`
section 11, and CI. ADR-005 is provisional until a Maven spike proves the version
matrix, so this ticket is also the compatibility spike. It is a clean architecture
skeleton, not a triage implementation.

## Goals
- Pin a real released Spring Boot 3.5.x patch and the Spring AI Alibaba BOM; capture `mvn dependency:tree`.
- Identify and validate the correct Graph core artifact and the Spring AI DeepSeek adapter version (compatibility findings documented, not guessed).
- Maven Wrapper build on Java 25 that passes on a clean checkout; unit tests run in CI.
- Package skeleton for api / domain / application / graph / infrastructure per the architecture doc.
- App starts with health/readiness endpoints and requires no cloud API keys.
- Env-only secrets, `.env.example`, `.gitignore`, structured logging baseline.
- README with exact local build/run commands and compatibility findings; ADR-005 updated with the verified result.

## Non-goals
- Any triage logic: Jev client (FEATURE-002), taxonomy/policy (003), goldset (004), StateGraph nodes (006), DeepSeek investigator (008), mock task management (010), REST triage API (012).
- PostgreSQL, checkpointing, Temporal.
- Live calls to Jev or DeepSeek; no cloud credentials in CI.
- Real bank or customer data.

## Users & Roles
- Developers and Codex/Claude agents working on later FEATURE issues (consumers of the skeleton).
- CI runner (consumer of the build).
- Operators/reviewers hitting health endpoints locally.

## User Stories
- As a developer, I want a building, tested skeleton so that parallel feature branches start from a shared baseline.
- As a maintainer, I want verified, pinned versions so that Graph/Spring AI compatibility risk is retired early.
- As a security reviewer, I want secrets externalized to environment variables so that nothing sensitive is committed.

## Main Scenarios
1. Clean clone, `./mvnw verify` on JDK 25 passes with no network credentials.
2. `./mvnw spring-boot:run` starts the app with no API keys; `/actuator/health`, liveness and readiness probes return `UP`.
3. CI on pull request runs build and unit tests on Java 25.
4. Developer copies `.env.example` to a local untracked `.env`/exports vars to enable opt-in live providers later.

## Edge Scenarios
1. A provider key variable is unset or empty: the app still starts and is healthy; provider adapters (later tickets) will remain disabled.
2. A Graph or DeepSeek starter artifact does not compile with Boot 3.5.x: record the finding, choose the verified artifact set or document the blocker; never claim compile-ready without a run.
3. Java 25 incompatible with a plugin (e.g. Mockito/ByteBuddy, JaCoCo, Maven plugins): pin the working plugin version and document it.
4. Logs must not contain secrets or request bodies.

## Acceptance Criteria
- AC1: `pom.xml` pins a released Spring Boot 3.5.x patch, Spring AI 1.1.2 and the Spring AI Alibaba BOM (1.1.2.0); `mvn dependency:tree` runs and its result (or a summary) is recorded in the README/docs.
- AC2: Maven Wrapper (`mvnw`) committed; `./mvnw verify` passes on Java 25 from a clean checkout; the exact command and result are documented. A GitHub Actions workflow runs unit tests on Java 25 without cloud credentials.
- AC3: Package boundaries `api`, `domain` (model/port), `application`, `graph`, `infrastructure` exist under the root package per `docs/architecture.md` section 11, with `package-info.java` and an automated architecture test (e.g. dependency-direction check) enforcing that domain does not depend on infrastructure/graph/api.
- AC4: Spring Boot starts with Actuator health plus liveness/readiness probes; a test (context load + health endpoint) passes with no API keys set.
- AC5: Configuration uses environment-only secrets (`JEV_API_KEY`, `DEEPSEEK_API_KEY` or equivalent names); `.env.example` committed with placeholders only; `.gitignore` excludes `.env`, build output and IDE files; structured logging baseline (JSON or key=value pattern with correlation placeholders) configured and no secret logged.
- AC6: README documents local build/run commands, JDK 25 requirement, health URLs, env vars, and a compatibility findings section (verified versions, Graph core artifact, DeepSeek adapter version, issues hit). ADR-005 updated from provisional to the verified outcome (or a new ADR if deviating).
- AC7: Graph core dependency is verified to resolve and a minimal compile-time/unit smoke test shows `StateGraph` API is usable at the pinned version (no business logic); DeepSeek adapter dependency resolves but is not activated by default.
- AC8: No live model calls in default tests; all test data English and synthetic.

## Non-functional requirements
- Security: no secrets in repo; keys only via environment; actuator exposure limited to health (plus info) by default; no real data.
- Privacy/data protection: logging baseline excludes request text and credentials.
- Reliability: reproducible build via wrapper and pinned versions; CI does not need credentials.
- Performance/scale: not applicable beyond reasonable startup time.
- Observability: structured logs, health/readiness; Micrometer on classpath via Actuator only (decision-trace metrics deferred to FEATURE-012).
- Compatibility: Java 25, Spring Boot 3.5.x, Spring AI 1.1.2, SAA 1.1.2.0; do not mix Spring AI 2.x / Boot 4.x.

## Data considerations
- Identifiers: none yet (triageId/graphRunId are introduced in later tickets).
- Sensitive data: only provider API keys, supplied through environment variables.
- Retention/lifecycle: no persistence; graph state is in-memory only (ADR-007).

## Constraints & Assumptions
- Assumed Maven group `io.github.denrzv`, artifact `service-request-triage`, root package `io.github.denrzv.triage` (matches `.../triage/` in architecture section 11). Not stated in the issue; adjust in the plan if the user objects.
- Assumed Spring Boot 3.5 patch to be resolved from Maven Central during research; versions must be verified, not guessed.
- Assumed Maven (not Gradle) per issue. Tests: JUnit 5, AssertJ, Spring Boot Test.
- Assumed the Graph core artifact name is to be verified (candidate `spring-ai-alibaba-graph-core`); cannot be assumed.
- Cognitive complexity and duplicated-literal limits from user conventions apply to any code added.

## Risks
- Graph/Spring AI/Boot version incompatibility or missing Java 25 support in transitive libs (mitigate: spike first, document).
- Java 25 tooling gaps (bytecode agents, plugins).
- BOM drift if unpinned versions are used.
- Scope creep into adapters or graph nodes (mitigate: skeleton only).

## Open Questions
- None blocking. Assumptions above (group/package naming, env var names) are recorded for the plan to confirm.
