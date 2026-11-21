# FEATURE-005: Research note
Status: RESEARCH_READY

## Repo map
- `domain.model`: `SemanticAssessment` (three enum-typed `ChoiceJudgment`s, four Noul doubles, `modelUsed`,
  `questionSetVersion`; every field mandatory), `ChoiceJudgment.margin()`, Jev request/answer records
  (`JevQuestionSet`, `ChoiceQuestion`, `NoulQuestion`, `ScoreQuestion`, `ChoiceAnswer`, `NoulAnswer`,
  `ScoreAnswer`, `JevClassificationResult` with `resolvedModel`, `usage`, `latency`, `attempts`).
- `domain.port`: `JevClassificationPort` and `JevException` subtypes (`JevClientException`,
  `JevUnavailableException`, `JevProtocolException`).
- `application.policy.ClassificationPolicyEngine`: pure `decide(assessment, taxonomy, policy, investigationAttempts)`
  and `modelUnavailable(taxonomy, policy)`. With `investigationAttempts >= maxInvestigations` every concern becomes
  `HUMAN_REVIEW` + `INVESTIGATION_BUDGET_EXHAUSTED`, so passing `policy.maxInvestigations()` models "no LLM fallback"
  without touching the engine.
- `infrastructure.typesafe.JevHttpClassificationAdapter` (package-private) with `JevProperties`; state sent is
  `{customerMessage, channel}`.
- Test scope `evaluation.goldset`: `GoldsetLoader` (strict JSONL, `sha256(Path)`), `GoldCase`, `GoldAlternatives`,
  `Stratum`, `GoldSplit`, `GoldsetManifest`; `GoldsetContractTest.DATASET` path constant.
- `infrastructure.taxonomy.ShippedDefinitions` (test helper) exposes taxonomy `v1`; the shipped policy is
  `src/main/resources/policy/v1.json` (uncalibrated, no-op thresholds, `maxInvestigations: 1`, `CLAIM` requires
  approval).
- Missing: no triage question set, no Jev-answer to `SemanticAssessment` mapping, no benchmark code.

## Entry points
- REST/API: none (FEATURE-012).
- Message/event consumers: none.
- Schedulers/jobs: none.
- UI or CLI: JUnit tests are the harness entry point (`./mvnw test -Dtest=...`); the existing opt-in pattern is
  `@EnabledIfEnvironmentVariable` (see `JevLiveSmokeTest`).

## Data model touch points
- Entities/tables: none.
- Repositories/queries: goldset files `samples/english-goldset/v1/{dev,test}.jsonl` + `manifest.json` read via
  `GoldsetLoader` (test scope only).
- Migrations: none.

## Integrations
- Outbound REST/RPC: TypeSafe `POST /v1/systemone` through the existing adapter (opt-in live run only). Verified
  today: a minimal request through this environment's proxy returned HTTP 200 with `model: jev-1.14.0`.
- Messaging topics/queues: none.
- Cache/storage: files under `reports/evaluation/<runId>/`.
- External systems: TypeSafe docs (retrieved 2026-11-21): Models page lists `jev-1.14.0` at USD 0.042 per Mtok,
  billed per input token only (output tokens free); `jev-latest` currently points to `jev-1.14.0` and moves with
  releases. Companion Noul pattern: pair the Choice with a Noul asking whether any option fits; read the Noul first
  and ignore the Choice on a clear no.

## Existing tests
- `JevResponseParserTest`, `JevHttpClassificationAdapterTest` (FakeJevServer), `JevLiveSmokeTest` (opt-in).
- `ClassificationPolicyEngineTest`, `PolicyFixtures`, `DomainFixtures`.
- `GoldsetContractTest`, `GoldsetIsolationTest` (scans `src/main`, compiled main output and `src/test/resources`
  for goldset path, case IDs, label fields and messages; ArchUnit: runtime never depends on `..evaluation..`).
- `ArchitectureBoundaryTest`: application must not depend on infrastructure/graph/api.

## Constraints discovered
- Timeouts: adapter uses `connect-timeout` 5 s and `request-timeout` 30 s by default.
- Retries: adapter retries 429/5xx/IO with bounded exponential backoff (`max-attempts` ≤ 10); no extra retry layer.
- Idempotency/deduplication: Jev calls are read-only judgments; replay keys on message SHA-256 (the port receives
  only the request, not the case ID).
- Transactions/consistency: not applicable.
- Auth/security: key from `JEV_API_KEY`; adapter never logs key, text or bodies. Goldset isolation tests forbid case
  IDs and messages in `src/test/resources` and `src/main`, so recordings and reports must live outside them
  (`reports/evaluation/`).
- Observability: adapter logs model, attempts, latency and tokens per call.

## Risks & unknowns
- Policy `v1` thresholds are no-ops; the `JEV_ONLY` v1 result is essentially "argmax + categorical rules". The
  threshold sweep is what shows the value of confidence/Noul signals.
- `ScoreAnswer` is not needed by `SemanticAssessment`; adding a Score question would cost tokens without use.
- Small sample: 75 held-out cases; per-class F1 for rare classes is noisy.
- Determinism of the committed report requires stable ordering and `Locale.ROOT` number formatting.

## Pointers
- `src/main/java/io/github/denrzv/triage/application/policy/ClassificationPolicyEngine.java`: decision order and
  investigation budget.
- `src/main/java/io/github/denrzv/triage/domain/model/SemanticAssessment.java`: mapper target.
- `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevHttpClassificationAdapter.java`: live port.
- `src/test/java/io/github/denrzv/triage/infrastructure/typesafe/JevLiveSmokeTest.java`: opt-in pattern.
- `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldsetIsolationTest.java`: isolation constraints.
- `docs/evaluation.md`: metric definitions and output artifacts.
