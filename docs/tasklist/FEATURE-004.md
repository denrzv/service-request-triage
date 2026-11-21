# FEATURE-004: Tasklist
Status: TASKLIST_READY

References:
- PRD: `docs/prd/FEATURE-004.prd.md`
- Research: `docs/research/FEATURE-004.md`
- Plan: `docs/plan/FEATURE-004.md`

All Maven commands use `JAVA_HOME=/usr/lib/jvm/temurin-25`.

## Tasks
- [x] TASK-1: Channel enum and test-scope goldset record model with strict loader
  - Goal: Typed goldset record, manifest model and a strict JSONL loader enforcing per-record invariants.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-004.md (Repo map, Constraints discovered)
  - Requirements:
    - PRD: AC2, AC6
    - Plan: `Architecture / Flow`, `Contracts`, `Security & Privacy`
  - Target files:
    - `src/main/java/io/github/denrzv/triage/domain/model/Channel.java`
    - `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldCase.java`, `GoldAlternatives.java`, `Stratum.java`, `GoldSplit.java`, `LabelSource.java`, `GoldsetManifest.java`, `GoldsetLoader.java`, `GoldsetValidationException.java`
  - Reference files: `src/main/java/io/github/denrzv/triage/infrastructure/taxonomy/DefinitionJsonMapper.java`
  - Test files:
    - `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldsetLoaderTest.java`
  - Expected changes:
    - `GoldsetLoader.load(Path, Taxonomy)`: strict parse (unknown property/enum rejected), errors name file, line and field.
    - Record invariants: id/group patterns, ASCII text, length limits, no e-mail or 6+ digit runs, reason code belongs to SR type, terminal route, route/type consistency, `requiresHumanReview == (route != DRAFT_PROPOSAL)`, `CLAIM` → `HUMAN_REVIEW`, alternatives/secondary intents exclude the primary label and duplicates, taxonomy version matches.
  - Acceptance:
    - AC2: every required field is mandatory and typed.
    - AC6: each invalid shape fails with a precise message.
  - Validation:
    - Fast: `./mvnw -q -Dtest='GoldsetLoaderTest,ArchitectureBoundaryTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: none
  - Out of scope: cross-record checks, dataset content
  - Notes: Test scope keeps the reader out of the runtime jar (ADR decision 1).
  - Completion notes: Added `domain.model.Channel` (contracts section 1) and the test-scope `evaluation.goldset` model (`GoldCase`, `GoldAlternatives`, `Stratum`, `GoldSplit`, `LabelSource`, `GoldsetManifest`, `GoldsetValidationException`) plus `GoldsetLoader` (strict Jackson: no scalar coercion, no numeric enums, duplicate keys, missing properties; LF/final-newline check; field path in errors; message text never echoed). Test helper `infrastructure.taxonomy.ShippedDefinitions` exposes the real v1 taxonomy. Checks: `./mvnw -q -Dtest='GoldsetLoaderTest,ArchitectureBoundaryTest' test` on JDK 25 passed (41 tests).

- [x] TASK-2: Cross-record validator and hash-rank splitter
  - Goal: Duplicate/near-duplicate/paraphrase-group rules, balance rules and deterministic split assignment.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-004.md (Risks & unknowns)
  - Requirements:
    - PRD: AC3, AC4, AC6
    - Plan: `Reliability`, `Testing strategy`
  - Target files:
    - `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldsetValidator.java`, `GoldsetSplitter.java`, `TextNormalizer.java`
  - Reference files: Not applicable
  - Test files:
    - `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldsetValidatorTest.java`, `GoldsetSplitterTest.java`
  - Expected changes:
    - `GoldsetSplitter.assign(cases, seed, fraction)`: per-stratum sha256 ranking of units, `floor(f*n+0.5)` target.
    - `GoldsetValidator.validate(dev, test)`: unique IDs, unique normalized messages, Jaccard >= 0.8 only inside a paraphrase group, groups size >= 2 with one stratum, one split and identical labels.
  - Acceptance:
    - AC4: assignment deterministic and group-preserving.
    - AC6: duplicates and leakage detected.
  - Validation:
    - Fast: `./mvnw -q -Dtest='GoldsetValidatorTest,GoldsetSplitterTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-1
  - Out of scope: dataset content
  - Notes: Algorithm must match `tools/goldset.py` exactly.
  - Completion notes: Added `TextNormalizer` (normalize, token set, Jaccard), `GoldsetSplitter` (`sha256-rank-per-stratum-v1`, half-up target, paraphrase group as unit, rejects groups spanning strata and invalid recipe) and `GoldsetValidator` (duplicate IDs across splits, normalized duplicates, near-duplicates >= 0.8 outside groups, group size/stratum/split/label coherence, 200-300 total and >= 12 per stratum). Fixed a null check on immutable lists found by the tests. Checks: `./mvnw -q -Dtest='GoldsetValidatorTest,GoldsetSplitterTest,GoldsetLoaderTest' test` on JDK 25 passed.

- [x] TASK-3: Python dataset tool
  - Goal: Stdlib tool with `validate`, `coverage`, `freeze`, `verify`, mirroring the Java rules.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-004.md (Repo map)
  - Requirements:
    - PRD: AC4, AC6
    - Plan: `Architecture / Flow`
  - Target files:
    - `tools/goldset.py` (moved from `scripts/`, see plan Assumptions)
  - Reference files: `scripts/test-validate-workflow.py`
  - Test files:
    - `tools/test_goldset.py`
  - Expected changes:
    - Canonical serialization (fixed key order, ASCII, LF), manifest with seed/algorithm/sha256/counts, idempotent `freeze`, non-zero exit on any violation.
  - Acceptance:
    - AC6: validation, duplicate detection and coverage report available as a script.
  - Validation:
    - Fast: `python3 tools/test_goldset.py`
    - Broader (only if needed): Not applicable
  - Dependencies: none
  - Out of scope: Java checks
  - Notes: Reads taxonomy JSON directly, no third-party packages.
  - Completion notes: Added `tools/goldset.py` (stdlib; `validate`, `verify`, `coverage`, `freeze`; canonical
    serialization, manifest, idempotent freeze, non-zero exit; message text never printed) and
    `tools/test_goldset.py`. Deviation: `tools/` instead of `scripts/` because the AIDD write hook reserves
    `scripts/` for human-maintained workflow scripts; plan updated. Checks: `python3 tools/test_goldset.py`
    passed (17 tests).

- [x] TASK-4: Author and freeze the v1 dataset
  - Goal: 240 synthetic English cases in 12 strata, frozen into dev/test with manifest.
  - Size: L
  - Risk: standard
  - Evidence: docs/evaluation.md section 1
  - Requirements:
    - PRD: AC1, AC2, AC3, AC4
    - Plan: `Assumptions`
  - Target files:
    - `samples/english-goldset/v1/dev.jsonl`, `test.jsonl`, `manifest.json`
    - `.gitattributes`
  - Reference files: `src/main/resources/taxonomy/v1.json`
  - Test files: covered by TASK-5 and `python3 tools/goldset.py verify`
  - Expected changes:
    - Strata: simple complaint/inquiry/service action/incident, duplicate charge, dissatisfaction with technical symptom, multi-intent, vague, contradictory, out-of-taxonomy, near-tie, no-complaint control; paraphrase groups.
  - Acceptance:
    - AC1/AC3: 240 cases, each stratum >= 12.
    - AC4: manifest seed and hashes.
  - Validation:
    - Fast: `python3 tools/goldset.py verify samples/english-goldset/v1 && python3 tools/goldset.py coverage samples/english-goldset/v1`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-3
  - Out of scope: provider runs
  - Notes: No provider output used for labels.
  - Completion notes: Authored 240 cases (12 strata, 12-24 each; 12 paraphrase groups / 33 cases; 86 with
    alternatives; 21 multi-intent) and froze them with seed `feature-004-english-goldset-v1`: dev 165 / test 75,
    per-stratum test share 29-38 %. Case IDs are assigned in a deterministic shuffled order so they do not reveal
    stratum or labels. `.gitattributes` pins LF. Authoring helper was temporary (`target/`, not committed); the
    frozen files are the source of truth. Checks: `python3 tools/goldset.py verify samples/english-goldset/v1`
    passed; coverage reviewed.

- [x] TASK-5: Shipped-dataset contract test and isolation guards
  - Goal: Build-enforced validation of the frozen dataset and of label isolation from runtime/agent-accessible resources.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-004.md (Constraints discovered)
  - Requirements:
    - PRD: AC1, AC3, AC4, AC5
    - Plan: `Testing strategy`, `Security & Privacy`
  - Target files:
    - `src/test/java/io/github/denrzv/triage/evaluation/goldset/GoldsetContractTest.java`, `GoldsetIsolationTest.java`
  - Reference files: `src/test/java/io/github/denrzv/triage/infrastructure/taxonomy/ShippedDefinitionsTableTest.java`
  - Test files: same
  - Expected changes:
    - Contract: counts, strata balance, per-stratum test fraction, hashes, recomputed split, validator clean.
    - Isolation: no goldset path/IDs/label fields/messages in `src/main`, compiled main output, test resources; `pom.xml` adds no `samples` resource.
  - Acceptance:
    - AC5: leakage would fail the build.
  - Validation:
    - Fast: `./mvnw -q -Dtest='GoldsetContractTest,GoldsetIsolationTest' test`
    - Broader (only if needed): `./mvnw -q verify`
  - Dependencies: TASK-1, TASK-2, TASK-4
  - Out of scope: future tool implementations
  - Notes: Guards scan sources, so they cover FEATURE-008 tools automatically.
  - Completion notes: `GoldsetContractTest` (manifest metadata, hashes/counts, recomputed split, validator clean,
    size/strata balance with 20-40 % held-out share, every formal type and terminal route plus multi-intent and
    ambiguous cases in both splits, canonical order, non-informative case IDs, directory content) and
    `GoldsetIsolationTest` (src/main markers/IDs/texts, compiled main output, classpath, test resources, pom,
    ArchUnit rule that runtime classes never depend on `..evaluation..`, plus a planted-leak test proving the scanner
    detects leaks). Narrowed the marker list after a false positive on the word "goldset" in the policy description.
    Shared test helper `GoldCases`. Checks: `./mvnw -q -Dtest='Goldset*Test' test` on JDK 25 passed (README check
    passed once TASK-6 added it).

- [x] TASK-6: Documentation and CI
  - Goal: Dataset README with label guideline and limitations, root README, contracts/evaluation notes, CI step.
  - Size: S
  - Risk: standard
  - Evidence: docs/evaluation.md
  - Requirements:
    - PRD: AC7
    - Plan: `Shared contract changes`, `Rollout plan`
  - Target files:
    - `samples/english-goldset/README.md`, `README.md`, `docs/contracts.md`, `docs/evaluation.md`, `.github/workflows/ci.yml`
  - Reference files: Not applicable
  - Test files: Not applicable
  - Expected changes:
    - README covers schema, strata, route guideline, split/hashes, change procedure, limitations (synthetic, single author, ambiguity, multi-label).
  - Acceptance:
    - AC7: limitations and ambiguous/multi-label handling explained.
  - Validation:
    - Fast: `python3 tools/goldset.py verify samples/english-goldset/v1`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-4
  - Out of scope: evaluation results
  - Notes: English only.
  - Completion notes: Added `samples/english-goldset/README.md` (files, isolation rules, schema, labeling
    guideline, strata, split, change procedure, limitations incl. single-author labels, ambiguity via
    `alternatives`, multi-label approximation), README goldset section and CI table row, `docs/evaluation.md`
    implemented note, `docs/contracts.md` section 5a, and a CI step running `tools/test_goldset.py` and
    `tools/goldset.py verify`. CI workflow is verified by its PR run, not locally.

## Diagram tasks
- None. Add diagram tasks only when `docs/plan/FEATURE-004.md` has `Render diagrams required: yes`.

## Review / QA fixes
- None yet. `/fix-tasks` appends implementation-ready `TASK-FIX-N` entries here
  for unresolved Blocking or Important findings.
