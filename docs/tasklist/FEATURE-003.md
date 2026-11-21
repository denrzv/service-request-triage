# FEATURE-003: Tasklist
Status: TASKLIST_READY

References:
- PRD: `docs/prd/FEATURE-003.prd.md`
- Research: `docs/research/FEATURE-003.md`
- Plan: `docs/plan/FEATURE-003.md`

All Maven commands use `JAVA_HOME=/usr/lib/jvm/temurin-25`.

## Tasks
- [x] TASK-1: Taxonomy, decision and policy domain model
  - Goal: Add the synthetic taxonomy enums, typed semantic/decision records and policy model records with invariants.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-003.md (Repo map, Risks & unknowns)
  - Requirements:
    - PRD: AC1, AC2, AC3, AC6, AC7
    - Plan: `Architecture / Flow`, `Contracts`
  - Target files:
    - `src/main/java/io/github/denrzv/triage/domain/model/PrimaryIntent.java`, `ProblemDomain.java`, `RequestedOutcome.java`, `ServiceRequestType.java`, `RouteAction.java`, `RationaleCode.java`, `SignalRequirement.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/ReasonCode.java`, `Taxonomy.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/ChoiceJudgment.java`, `SemanticAssessment.java`, `ClassificationProposal.java`, `RoutingDecision.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/PolicyThresholds.java`, `RuleCondition.java`, `ClassificationRule.java`, `ClassificationPolicy.java`
    - `src/main/java/io/github/denrzv/triage/domain/model/Checks.java`
  - Reference files: `src/main/java/io/github/denrzv/triage/domain/model/ChoiceAnswer.java`
  - Test files:
    - `src/test/java/io/github/denrzv/triage/domain/model/TaxonomyModelTest.java`
    - `src/test/java/io/github/denrzv/triage/domain/model/DecisionContractsTest.java`
    - `src/test/java/io/github/denrzv/triage/domain/model/PolicyModelTest.java`
  - Expected changes:
    - `Taxonomy` requires a definition for every enum constant and the mandatory `UNCLASSIFIED` reason codes; reason codes are keyed by code and bound to one SR type.
    - `ClassificationProposal` requires `taxonomyVersion`; `RoutingDecision` requires `policyVersion`, enforces action/type consistency (`DRAFT_PROPOSAL` never `UNCLASSIFIED`, `HUMAN_REVIEW` sets `humanApprovalRequired`).
    - `ClassificationPolicy.requireCompatibleWith(Taxonomy)` validates version, reason-code references and types.
  - Acceptance:
    - AC1: Invalid records throw `IllegalArgumentException` with messages that name the field.
    - AC2: Domain stays free of Spring/Jackson/infrastructure types (ArchUnit green).
  - Validation:
    - Fast: `./mvnw -q -Dtest='TaxonomyModelTest,DecisionContractsTest,PolicyModelTest,ArchitectureBoundaryTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: none
  - Out of scope: JSON loading, engine logic, Jev mapping
  - Notes: Constants for repeated literals; method complexity <= 15.
  - Completion notes: Added taxonomy enums (`PrimaryIntent`, `ProblemDomain`, `RequestedOutcome`,
    `ServiceRequestType`), `RouteAction`, `RationaleCode`, `SignalRequirement`, `ReasonCode`, `Taxonomy`
    (complete enum coverage, mandatory `UNCLASSIFIED` codes), typed `ChoiceJudgment<E>` with `margin()`,
    `SemanticAssessment`, `ClassificationProposal`, `RoutingDecision` (action/type/approval invariants), and the
    policy model (`PolicyThresholds`, `RuleCondition`, `ClassificationRule`, `ClassificationPolicy` with
    `requireCompatibleWith`). `Checks` gained IAE-based null/range/enum helpers.
    Checks: `./mvnw -q -Dtest='TaxonomyModelTest,DecisionContractsTest,PolicyModelTest,ArchitectureBoundaryTest' test` on JDK 25 passed.

- [x] TASK-2: Pure ClassificationPolicyEngine with table-driven tests
  - Goal: Implement the deterministic engine per ADR decision 4 and the table tests.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-003.md (Risks & unknowns)
  - Requirements:
    - PRD: AC2, AC3, AC4
    - Plan: `Architecture / Flow`, `Testing strategy`; ADR decisions 3-5
  - Target files:
    - `src/main/java/io/github/denrzv/triage/application/policy/ClassificationPolicyEngine.java`
    - `src/main/java/io/github/denrzv/triage/application/policy/package-info.java`
  - Reference files: Not applicable
  - Test files:
    - `src/test/java/io/github/denrzv/triage/application/policy/ClassificationPolicyEngineTest.java`
    - `src/test/java/io/github/denrzv/triage/application/policy/PolicyFixtures.java`
  - Expected changes:
    - `decide(assessment, taxonomy, policy, investigationAttempts)` and `modelUnavailable(taxonomy, policy)`.
    - Alternatives = SR types of other matching rules; supporting signals list matched dimensions/signals.
  - Acceptance:
    - AC1: Table rows cover complaint, technical issue, mixed intent, none-fit, low fit, low confidence, near-tie, investigation budget exhausted, model unavailable.
    - AC2: Equal inputs produce equal decisions; uncalibrated policy adds `THRESHOLDS_UNCALIBRATED`.
  - Validation:
    - Fast: `./mvnw -q -Dtest='ClassificationPolicyEngineTest,ArchitectureBoundaryTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-1
  - Out of scope: loading files (fixtures built in Java here), graph integration
  - Notes: Engine has no Spring annotations, no I/O, no clock.
  - Completion notes: Added `application.policy.ClassificationPolicyEngine` with `decide(...)` and
    `modelUnavailable(...)` following ADR decision 4; ambiguity is checked only on dimensions the decisive rule
    constrains; `ruleIds` lists all matches in table order; uncalibrated policies append `THRESHOLDS_UNCALIBRATED`.
    `ClassificationPolicyEngineTest` has a 12-row `@CsvSource` table (complaint, technical issue, mixed intent,
    none-fit, low fit, low confidence, near-tie, budget exhaustion) plus model-unavailable, no-op, configurable
    low-fit action, zero budget, determinism and invalid-input tests (20 tests).
    Checks: `./mvnw -q -Dtest='ClassificationPolicyEngineTest,ArchitectureBoundaryTest' test` on JDK 25 passed.

- [x] TASK-3: Versioned JSON definitions, ports and Spring wiring
  - Goal: Add `taxonomy/v1.json`, `policy/v1.json`, ports, JSON mapping with strict validation, in-memory repositories, properties and configuration.
  - Size: M
  - Risk: standard
  - Evidence: docs/research/FEATURE-003.md (Data model touch points)
  - Requirements:
    - PRD: AC1, AC5, AC6
    - Plan: `Architecture / Flow`, `Security & Privacy`, `Rollout plan`; ADR decisions 1, 5
  - Target files:
    - `src/main/java/io/github/denrzv/triage/domain/port/TaxonomyPort.java`, `ClassificationPolicyPort.java`, `DefinitionNotFoundException.java`
    - `src/main/java/io/github/denrzv/triage/infrastructure/taxonomy/DefinitionsProperties.java`, `DefinitionWire.java`, `DefinitionJsonMapper.java`, `InMemoryTaxonomyRepository.java`, `InMemoryPolicyRepository.java`, `DefinitionsConfiguration.java`
    - `src/main/resources/taxonomy/v1.json`, `src/main/resources/policy/v1.json`, `src/main/resources/application.yml`
  - Reference files: `src/main/java/io/github/denrzv/triage/infrastructure/typesafe/JevProperties.java`
  - Test files:
    - `src/test/java/io/github/denrzv/triage/infrastructure/taxonomy/DefinitionJsonMapperTest.java`
    - `src/test/java/io/github/denrzv/triage/infrastructure/taxonomy/DefinitionsConfigurationTest.java`
    - `src/test/java/io/github/denrzv/triage/application/policy/ShippedPolicyTableTest.java`
    - `src/test/resources/policy/test-thresholds.json`, `src/test/resources/policy/alternate-v2.json`, `src/test/resources/policy/invalid-reason-code.json`
  - Expected changes:
    - Unknown JSON properties/enum names rejected; duplicate versions rejected; active policy must exist and be compatible with its taxonomy.
    - Shipped v1 table test drives the engine through the loaded definitions.
  - Acceptance:
    - AC1: Context starts with defaults; ports return v1; unknown version throws `DefinitionNotFoundException`.
    - AC2: Pointing `policy-locations` at another file changes the decision without a source change; invalid file fails startup with the location in the message.
  - Validation:
    - Fast: `./mvnw -q -Dtest='DefinitionJsonMapperTest,DefinitionsConfigurationTest,ShippedPolicyTableTest,StartupHealthTest' test`
    - Broader (only if needed): `./mvnw -B verify`
  - Dependencies: TASK-1, TASK-2
  - Out of scope: hot reload, persistence, REST exposure
  - Notes: Log versions and locations only.
  - Completion notes: Added `TaxonomyPort`, `ClassificationPolicyPort` (`get`, `active`) and
    `DefinitionNotFoundException`; `taxonomy/v1.json` and uncalibrated no-op `policy/v1.json` (10 synthetic rules);
    strict `DefinitionJsonMapper` (unknown/missing/null/duplicate properties, unknown enums, fractional ints,
    `synthetic: true` required), `DefinitionLoader` (location in every failure), in-memory repositories (duplicate
    versions, policy/taxonomy compatibility, active version) and `DefinitionsConfiguration` (also exposes the engine).
    Plan drift: `DefinitionLoader` was added for resource handling; the shipped table test lives in
    `infrastructure.taxonomy.ShippedDefinitionsTableTest` (needs the package-private mapper); threshold branches are
    covered by Java fixtures in TASK-2, so no `test-thresholds.json` was needed.
    Checks: `./mvnw -q -Dtest='DefinitionJsonMapperTest,DefinitionsConfigurationTest,ShippedDefinitionsTableTest,StartupHealthTest' test` on JDK 25 passed (31 tests).

- [x] TASK-4: Documentation
  - Goal: Document taxonomy/policy usage and the contract refinements.
  - Size: S
  - Risk: standard
  - Evidence: docs/research/FEATURE-003.md (Risks & unknowns)
  - Requirements:
    - PRD: AC5, AC6, AC7
    - Plan: `Shared contract changes`, `Observability`
  - Target files:
    - `README.md`
    - `docs/contracts.md`
  - Reference files: Not applicable
  - Test files:
    - Not applicable
  - Expected changes:
    - README "Taxonomy and policy (FEATURE-003)" section: files, properties, engine order, publishing a new version, synthetic disclaimer.
    - contracts.md implemented-notes for sections 1, 2 and 4.
  - Acceptance:
    - AC1: Docs match property names, defaults and engine behaviour in code.
  - Validation:
    - Fast: `python3 scripts/validate-workflow.py --ticket FEATURE-003`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-3
  - Out of scope: architecture diagrams
  - Notes: Concise and factual.
  - Completion notes: README gained "Taxonomy and policy (FEATURE-003)" (files, engine order, uncalibrated v1,
    `triage.definitions.*` table, publishing a new version, startup validation). `docs/contracts.md` sections 1, 2
    and 4 gained "Implemented in FEATURE-003" notes describing the typed refinements, ports and engine order.
    Checks: `python3 scripts/validate-workflow.py --ticket FEATURE-003` passed.

## Diagram tasks
- None. `docs/plan/FEATURE-003.md` has `Render diagrams required: no`.

## Review / QA fixes
- None yet. `/fix-tasks` appends implementation-ready `TASK-FIX-N` entries here
  for unresolved Blocking or Important findings.

## Completion notes
- All four regular tasks complete; full `./mvnw -B verify` is run by `/pre-commit`.
