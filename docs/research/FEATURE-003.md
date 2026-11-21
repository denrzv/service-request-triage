# FEATURE-003: Research note
Status: RESEARCH_READY

## Repo map
- `pom.xml`: Boot 3.5.16 parent, Spring AI 1.1.2 / SAA 1.1.2.0 BOMs, web (Jackson), actuator, graph-core, ArchUnit 1.5.1. Nothing new is needed: Jackson parses JSON definitions; `ResourceLoader` resolves `classpath:`/`file:` locations.
- `src/main/java/io/github/denrzv/triage/domain/model`: FEATURE-002 Jev value types (`ChoiceAnswer`, `NoulAnswer`, `ScoreAnswer`, `JevQuestionSet`, ...) and package-private `Checks` (text, unit-interval, size, ordered map/list copies).
- `domain/port`: `JevClassificationPort` and `JevException` hierarchy.
- `application/`, `graph/`, `infrastructure/taxonomy/`: empty packages with `package-info.java`.
- `infrastructure/typesafe`: example of `@ConfigurationProperties` record with validation (`JevProperties`) and conditional configuration (`JevConfiguration`).

## Entry points
- REST/API: none (FEATURE-012).
- Message/event consumers: none.
- Schedulers/jobs: none.
- UI or CLI: none. Consumers are Java callers (later graph nodes and benchmark runner).

## Data model touch points
- Entities/tables: none; definitions are classpath JSON (`src/main/resources/taxonomy/`, `src/main/resources/policy/`) as proposed in `docs/architecture.md` section 11.
- Repositories/queries: none.
- Migrations: none.

## Integrations
- Outbound REST/RPC: none. The engine consumes a `SemanticAssessment`; producing one from Jev answers is FEATURE-005/007.
- Messaging topics/queues: none.
- Cache/storage: definitions held in memory after startup load.
- External systems: none.

## Existing tests
- `ArchitectureBoundaryTest`: domain must not depend on application/graph/infrastructure/api; application must not depend on api/graph/infrastructure. The engine in `application` and records in `domain` comply.
- `StartupHealthTest`: full context with keyless defaults; will now also load v1 definitions (must stay green).
- `JevModelValidationTest`: style reference for invariant tests.

## Constraints discovered
- Timeouts: not applicable (no I/O in the engine; one-time startup file read).
- Retries: not applicable.
- Idempotency/deduplication: engine is a pure function; repeated calls are equal.
- Transactions/consistency: definitions immutable after load; versions must be unique across loaded files.
- Auth/security: no secrets; definitions are trusted operator config; customer text never enters the engine.
- Observability: decision carries versions, rule IDs, rationale codes for the future trace.

## Risks & unknowns
- `docs/contracts.md` types use `String` for `ChoiceJudgment.selected` and `rationaleCodes`; typed enums are safer and need an ADR-recorded refinement.
- `docs/contracts.md` section 4 policy order is illustrative; the engine must still keep "model unavailable -> HUMAN_REVIEW", "low fit -> ABSTAIN/HUMAN_REVIEW", "multi-intent -> LLM once, then HUMAN_REVIEW", and "CLAIM approval required" semantics.
- Calibrated values are unknown until FEATURE-004/005; v1 must not pretend otherwise.

## Pointers
- `docs/contracts.md`: sections 1, 2, 4 define the records, ports and policy order.
- `docs/architecture.md`: sections 3, 4, 5 define the separation, taxonomy, and required edge rules.
- `src/main/java/io/github/denrzv/triage/domain/model/Checks.java`: reusable invariant helpers.
- `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevProperties.java`: configuration properties pattern.
- `src/test/java/io/github/denrzv/triage/ArchitectureBoundaryTest.java`: layer rules to keep green.
