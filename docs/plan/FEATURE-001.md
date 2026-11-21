# FEATURE-001: Implementation plan
Status: PLAN_APPROVED

## Overview
Create a Maven (Wrapper) Spring Boot 3.5.16 project on Java 25 with Spring AI 1.1.2 and Spring AI Alibaba Graph 1.1.2.0, the package skeleton from `docs/architecture.md` section 11, Actuator health/readiness, env-only configuration, structured logging, GitHub Actions CI, and README compatibility findings. No triage behavior.

## Assumptions
- Group `io.github.denrzv`, artifact `service-request-triage`, root package `io.github.denrzv.triage` (PRD assumption).
- The local machine has JDK 21 and no `mvn`/JDK 25. Implementation may install them via Homebrew (`openjdk@25`, `maven`) only with user approval; otherwise local Java 25 runs are reported as not executed and CI is the evidence. Generating the wrapper needs `mvn` once.
- Versions follow ADR `FEATURE-001-version-baseline`.
- Work happens on branch `feature/FEATURE-001-project-bootstrap` in the main checkout because the AIDD write hooks only allow paths under the session project root (a sibling worktree was blocked).

## Architecture / Flow
Packages under `io.github.denrzv.triage`: `api`, `domain.model`, `domain.port`, `application`, `graph`, `infrastructure.{typesafe,deepseek,taxonomy,taskmanagement,persistence,observability}`, each with `package-info.java`; `TriageApplication` is the Spring Boot entry point. Dependency direction, enforced by an ArchUnit test: api to application to domain; graph to application and domain; infrastructure to domain (and application for wiring); domain depends on no other project package. A test-only `GraphDependencySmokeTest` builds a trivial `StateGraph` to prove the pinned Graph API resolves and compiles.

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | No new external actors; skeleton only | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | Single Spring Boot app already described in docs | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): skeleton only; packages reserved; no routing logic. The smoke graph is test-only.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): none called; providers disabled by default; live tests are opt-in in later tickets.
- Abstention / human-review paths covered: not applicable (no behavior); owned by FEATURE-003/006/007.
- Synthetic-data and goldset-isolation impact: none; test text is English synthetic; no goldset exists.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`.
- Request/input: none.
- Response/output: Boot Actuator JSON such as `{"status":"UP"}` with no details.
- Error behavior: standard Actuator behavior; no other endpoints. No OpenAPI/AsyncAPI files, so no version bump.

### Outbound contracts
- Integration: none. Timeout/Retry/Idempotency: not applicable.

## Data changes
- Schema changes: none. Migration/backfill: none. Indexes/partitions: none. Rollback/forward fix: revert PR.

## Reliability
- Timeout strategy: not applicable.
- Retry/backoff strategy: not applicable.
- Duplicate handling: not applicable.
- Ordering assumptions: none.
- Partial failure behavior: app starts without any key; provider flags default off.

## Security & Privacy
- AuthN/AuthZ: none (out of scope); actuator exposure minimal.
- Input validation: not applicable.
- Sensitive data/logging: keys only from env; `.env` ignored; logs exclude secrets; a test checks key values never appear in startup output.
- Audit trail: not applicable.

## Observability
- Logs: Boot structured console logging (logstash JSON).
- Metrics: Actuator/Micrometer on classpath; only health and info exposed.
- Traces/correlation: MDC keys `triageId`, `graphRunId` documented for later tickets.
- Alerts/runbook: none; README documents health URLs.

## Testing strategy
- Unit tests: package-structure ArchUnit test; Graph smoke test.
- Integration/contract tests: `@SpringBootTest` context test with empty provider env; health/liveness/readiness endpoint test.
- Regression tests: none yet.
- Manual checks: `./mvnw verify` on Java 25; `./mvnw spring-boot:run` plus curl on health; `./mvnw dependency:tree` captured. Anything not executed is marked as such.

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: required
Docs update: required
Contract review: not-required
DB review: not-required
Reliability review: not-required

### Rationale
- Review: Always required.
- QA: Always required; focused on build, startup, health and keyless behavior.
- Security review: Secrets handling, actuator exposure, logging and the dependency set are introduced.
- Docs update: README, ADR-005 outcome and compatibility findings are acceptance criteria.
- Contract review: Only standard Actuator endpoints; no project API or event schema.
- DB review: No database or migrations.
- Reliability review: No integrations, retries or persistence yet; startup and health are covered by QA.

## Rollout plan
- Deployment order: merge the PR; FEATURE-002/003/004 then branch from it.
- Feature flags/config: provider flags default `false`.
- Backward compatibility: greenfield.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-001-version-baseline.md`
- `docs/adr/FEATURE-001-keyless-startup-and-config.md`
- Baseline: ADR-005 (to be updated from provisional once verified), ADR-006, ADR-007.

## Open Questions
- None blocking. Moving to a later SAA 1.1.2.x patch is deferred to a follow-up.
