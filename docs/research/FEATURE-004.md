# FEATURE-004: Research note
Status: RESEARCH_READY

## Repo map
- `pom.xml`: Boot 3.5.x parent; Jackson (via web starter), JUnit 5, AssertJ and ArchUnit already available in test scope. No new dependency is needed to parse JSONL or compute SHA-256 (`java.security.MessageDigest`).
- `src/main/resources/taxonomy/v1.json`: label vocabulary (intents, domains, outcomes, SR types, reason codes bound to SR types). The goldset must reference exactly this version.
- `src/main/java/io/github/denrzv/triage/domain/model`: enums `PrimaryIntent`, `ProblemDomain`, `RequestedOutcome`, `ServiceRequestType`, `RouteAction`; `Taxonomy` record with `reasonCode(...)` lookup. No `Channel` enum exists although `docs/contracts.md` section 1 defines one.
- `src/main/java/io/github/denrzv/triage/infrastructure/taxonomy/DefinitionJsonMapper`: maps taxonomy JSON into `Taxonomy`; reusable by tests to load the real `v1` taxonomy for cross-checks.
- `scripts/`: Python 3 stdlib tooling for the AIDD workflow; `scripts/test-validate-workflow.py` shows the unittest style.
- `.github/workflows/ci.yml`: `./mvnw -B verify`, gitleaks, AIDD validation (Python). A Python dataset check can be added as a step.
- `docs/architecture.md` section 11 already reserves `samples/english-goldset/` outside `src/`.

## Entry points
- REST/API: none.
- Message/event consumers: none.
- Schedulers/jobs: none.
- UI or CLI: dataset tool (`python3 tools/goldset.py ...`); Java loader used by tests now and by the FEATURE-005 benchmark later.

## Data model touch points
- Entities/tables: none. New versioned files under `samples/english-goldset/v1/` (JSONL + manifest).
- Repositories/queries: none.
- Migrations: none.

## Integrations
- Outbound REST/RPC: none.
- Messaging topics/queues: none.
- Cache/storage: none.
- External systems: none. Providers are never called to create labels.

## Existing tests
- `ArchitectureBoundaryTest`: layer rules; analyses main classes only.
- `ShippedDefinitionsTableTest` / `DefinitionsConfigurationTest`: load the shipped `v1` taxonomy; pattern for reading the real definitions in tests.
- `StartupHealthTest`: full context; must remain unaffected (goldset is not loaded at runtime).

## Constraints discovered
- Timeouts: not applicable.
- Retries: not applicable.
- Idempotency/deduplication: split freezing must be idempotent (re-freezing frozen files yields identical bytes).
- Transactions/consistency: hashes must be stable across platforms; Git may rewrite line endings unless `.gitattributes` pins LF.
- Auth/security: anything under `src/main/resources` is packaged into the runtime jar and is reachable by future investigator tools; the goldset must stay outside it. Maven `user.dir` is the project base directory during Surefire runs, so tests can read `samples/` from disk.
- Observability: coverage report is the operator-facing summary.

## Risks & unknowns
- Paraphrase groups split across dev and test would leak near-identical text into the held-out set; the split unit must be the paraphrase group.
- Random-number-based splits are hard to reproduce identically in Python and Java; a hash-rank assignment (`sha256(seed:unitId)`) is language-neutral.
- Only one labeler is available; label quality limits must be documented rather than hidden.
- The policy `v1` routes `CLAIM` to `HUMAN_REVIEW`; the gold route guideline intentionally agrees (claims always need approval under the synthetic taxonomy) but is defined independently of policy code.

## Pointers
- `docs/evaluation.md` section 1: required fields and strata.
- `docs/adr/README.md`: ADR-009 (benchmark first), ADR-010 (English synthetic data), ADR-011 (ground truth immutable).
- `docs/contracts.md` sections 1 and 6: `Channel` enum; tools must exclude the goldset.
- `src/main/resources/taxonomy/v1.json`: label vocabulary.
- `scripts/test-validate-workflow.py`: Python unittest style.
