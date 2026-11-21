# FEATURE-001: Research note
Status: RESEARCH_READY

Research date: 2026-11-21. Sources: Maven Central metadata/POMs/jars (read directly), repository docs. Nothing below was compiled yet; compile/run evidence is produced during implementation.

## Repo map
- Greenfield: no `pom.xml`, no `src/`, no CI. Only docs, AIDD workflow pack (`.claude/`, `scripts/`, `hooks/`, `workflow.md`, `conventions.md`) and `.gitignore` (already ignores `.env`, `.env.*` except `!.env.example`, `target/`, `.idea/`).
- Target layout: `docs/architecture.md` section 11 (`src/main/java/.../triage/{api,domain/model,domain/port,application,graph,infrastructure/*}`, resources `prompts/`, `taxonomy/`, `policy/`).

## Entry points
- REST/API: none yet; only Actuator health/liveness/readiness required (triage REST is FEATURE-012).
- Message/event consumers: none.
- Schedulers/jobs: none.
- UI or CLI: `./mvnw spring-boot:run`, `./mvnw verify`.

## Data model touch points
- Entities/tables: none. Repositories/queries: none. Migrations: none (PostgreSQL checkpointing is FEATURE-011; `PostgresSaver` exists in graph-core 1.1.2.0 but is optional deps there).

## Integrations
- Outbound REST/RPC: none in this ticket (Jev = FEATURE-002, DeepSeek = FEATURE-008).
- Messaging: none. Cache/storage: none.
- External systems: Maven Central; GitHub Actions.

## Version matrix (verified against Maven Central)
| Item | Finding |
|---|---|
| Spring Boot 3.5.x | Latest 3.5 patch on Central: **3.5.16** (3.5.13-3.5.16 present). Boot 4.x lines also exist; must not be used (ADR-005). |
| Spring AI 1.1.x | 1.1.2 exists; latest 1.1.x is 1.1.8. Alibaba BOM 1.1.2.0 targets Spring AI **1.1.2**. |
| Spring AI Alibaba BOM | `com.alibaba.cloud.ai:spring-ai-alibaba-bom` **1.1.2.0** exists (also 1.1.2.1-1.1.2.3, 1.1.2.4-security-fix, 2.0.0-M1.1). |
| Graph core artifact | `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.0` (managed by the BOM). Built with Build-Jdk-Spec 17. |
| Graph core transitive | Depends at compile scope on `spring-ai-commons/rag/retry/client-chat/deepseek` 1.1.2, `spring-ai-autoconfigure-retry`, reactor-core 3.7.13, jackson 2.19.4; DB drivers (postgresql, mysql, oracle, mongo, redis) are `optional`. |
| DeepSeek adapter | `org.springframework.ai:spring-ai-deepseek:1.1.2` arrives transitively via graph-core; the starter `spring-ai-starter-model-deepseek` (1.1.2 in Spring AI BOM) provides auto-configuration and must stay inactive/unconfigured by default. |
| Graph API at 1.1.2.0 (javap) | `StateGraph(KeyStrategyFactory)`, `addNode(String, AsyncNodeAction)`, `compile`, `CompiledGraph.invokeAndGetResponse/stream`, `MemorySaver`, `PostgresSaver`, `OverAllState`, `KeyStrategy`. `addNode` throws checked `GraphStateException`. Exact conditional-edge signatures to confirm when FEATURE-006 builds the graph; FEATURE-001 only needs a compile smoke. |

Open version choice for the plan: use Boot 3.5.16 with Spring AI 1.1.2 + SAA 1.1.2.0 exactly (the BOM-aligned set), not Spring AI 1.1.8; upgrading to SAA 1.1.2.3 is an optional later step. The 1.1.2.4-security-fix suggests a security advisory; the plan should record and decide whether to pin 1.1.2.0 (issue baseline) or the fix build.

## Environment constraints (important)
- Local machine: JDK 21 (Corretto), JDK 17, JDK 8; **no JDK 25 and no `mvn` installed**. Homebrew offers `openjdk@25` and `maven`. Java 25 build evidence therefore needs installing JDK 25 (user's machine change) or relying on CI; if neither is possible, mark local Java 25 tests as not executed (CLAUDE.md rule).
- Spring Boot 3.5.x supports Java 25 per its docs, but Mockito/ByteBuddy, JaCoCo and Maven plugin versions must be pinned to Java 25-compatible releases; verify at build time.
- Maven Wrapper must be generated (`mvn wrapper:wrapper`) or created manually; needs `mvn` or the wrapper plugin once.

## Existing tests
- None. Only AIDD script tests (`scripts/test-*.py`). `scripts/pre-commit-check.sh` and `scripts/security-check.sh` exist; need to read them in planning to see which Maven commands they expect.

## Constraints discovered
- Timeouts/Retries/Idempotency: not applicable for bootstrap; later tickets own them.
- Auth/security: secrets env-only; actuator exposure limited; `spring-ai-autoconfigure-retry` and DeepSeek autoconfig must not require keys at startup (verify in context test with no keys, since Spring AI DeepSeek autoconfig may fail on missing `api-key`).
- Observability: structured logging baseline (Boot 3.4+ has built-in structured logging `logging.structured.format.console=ecs|logstash`, usable without extra deps).
- Conventions: SonarQube-style rules from user CLAUDE.md (no duplicated string literals, cognitive complexity <= 15).
- Governance: ADR-005 is provisional and must become verified/ACCEPTED; any ticket ADR must be `ACCEPTED` before implementation (`.claude/PROJECT_RULES.md`).

## Risks & unknowns
- R1: `spring-ai-deepseek`/autoconfigure may require `spring.ai.deepseek.api-key` and fail startup with no keys. Mitigation: exclude the starter by default or provide an inert placeholder property; test context with empty env.
- R2: Graph-core pulls many optional DB deps; ensure only optional ones stay off the classpath, keep dependency tree small.
- R3: Java 25 not available locally; Mockito inline mock maker and ByteBuddy versions.
- R4: Possible dependency convergence conflicts between Boot 3.5.16 managed versions (jackson 2.19.x vs graph-core 2.19.4, reactor) and Alibaba BOM; resolve through `mvn dependency:tree` and enforcer.
- R5: Security-fix release line (1.1.2.4) may supersede 1.1.2.0.
- Unknown: whether ArchUnit supports Java 25 class files at the version chosen.

## Pointers
- `docs/architecture.md` section 11: package layout to scaffold.
- `docs/adr/README.md` ADR-005, ADR-007: version baseline and checkpointing limitation.
- `docs/implementation-plan.md` row FEATURE-001 and Definition of done: CI needs no cloud credentials.
- `scripts/pre-commit-check.sh`, `scripts/security-check.sh`: expected verification commands (read in plan phase).
- https://repo1.maven.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-bom/1.1.2.0/ : BOM source of truth.
