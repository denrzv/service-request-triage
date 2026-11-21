# FEATURE-006: Tasklist
Status: TASKLIST_READY

References:
- PRD: `docs/prd/FEATURE-006.prd.md`
- Plan: `docs/plan/FEATURE-006.md`
- ADR: `docs/adr/FEATURE-006-stategraph-foundation.md`

All Maven commands use `JAVA_HOME=/usr/lib/jvm/temurin-25`. Main paths are under
`src/main/java/io/github/denrzv/triage/`, test paths under `src/test/java/io/github/denrzv/triage/`.
Research artifact not required: repository evidence and the verified SAA 1.1.2.0 API facts are recorded in the ADR.

## Tasks
- [x] TASK-1: Domain execution types and typed graph state with strict codec
  - Goal: `IncomingCustomerRequest`, `TriageStatus`, `DecisionTraceEvent`, graph enums, `TriageGraphState` and a
    strict versioned JSON codec, proven to survive the SAA default serializer/`MemorySaver` cloning.
  - Size: M
  - Risk: standard
  - Evidence: ADR FEATURE-006 Context (serializer, `MemorySaver`, `NodeExecutor` clone)
  - Requirements:
    - PRD: AC5 (serialization), AC7, Data considerations
    - Plan: `Architecture / Flow` (components, state fields), `Contracts`
  - Target files:
    - `domain/model/IncomingCustomerRequest.java`, `domain/model/TriageStatus.java`,
      `domain/model/DecisionTraceEvent.java`
    - `graph/TriageNode.java`, `graph/GateOutcome.java`, `graph/FailureCode.java`, `graph/TriageFailure.java`,
      `graph/TriageGraphState.java`, `graph/TriageGraphStateCodec.java`, `graph/package-info.java`
  - Reference files: `domain/model/SemanticAssessment.java`, `domain/model/RoutingDecision.java`
  - Test files:
    - `domain/model/TriageExecutionTypesTest.java`
    - `graph/TriageGraphStateCodecTest.java`, `graph/GraphStateFixtures.java`
  - Expected changes:
    - Records validate structure; `toString` of request and state omit the message.
    - Codec: explicit wire records, string enums, `schemaVersion` `triage-graph-state-v1`; rejects unknown schema,
      unknown fields and unknown enum constants with `IllegalArgumentException`.
    - Test: a one-node SAA graph with the default compile config carries the encoded state unchanged.
  - Acceptance:
    - AC5: full, minimal and failed states round-trip equal; enum-keyed choice probabilities preserved.
  - Validation:
    - Fast: `./mvnw -q -Dtest='TriageExecutionTypesTest,TriageGraphStateCodecTest,ArchitectureBoundaryTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: none
  - Out of scope: nodes, routing
  - Notes: `providerError` keeps the exception simple class name only.
  - Completion notes: Added `IncomingCustomerRequest` (unvalidated carrier, message hidden in `toString`), `TriageStatus`,
    `DecisionTraceEvent` in `domain.model`; `TriageNode`, `GateOutcome`, `FailureCode`, `TriageFailure`, immutable
    `TriageGraphState` (transition helpers, consecutive trace check; `triageId()` derived from input instead of a
    duplicate component) and strict `TriageGraphStateCodec` (`triage-graph-state-v1`, wire records, enum-order maps,
    unknown/missing fields and enums rejected, error messages never echo JSON). A one-node SAA graph with the default
    compile config (MemorySaver/cloning) carries the encoded state unchanged.
    Checks: `./mvnw -q -Dtest='TriageExecutionTypesTest,TriageGraphStateCodecTest,ArchitectureBoundaryTest' test`
    passed on JDK 25 (12 + 3 + 4 tests).

- [x] TASK-2: Input validation, definition loading and Jev classification nodes
  - Goal: `ValidateInput`, `LoadTaxonomy`, `JevClassify` node actions plus the exception guard.
  - Size: M
  - Risk: standard
  - Evidence: `domain/port/JevClassificationPort.java`, `application/assessment/JevAssessmentMapper.java`
  - Requirements:
    - PRD: Main scenario 4, Edge scenarios 1-3
    - Plan: `Security & Privacy` (input validation), `Reliability`
  - Target files:
    - `graph/InputValidator.java`, `graph/GuardedNode.java`, `graph/TriageRoutes.java`,
      `graph/ValidateInputNode.java`, `graph/LoadTaxonomyNode.java`, `graph/JevClassifyNode.java`
  - Reference files: `domain/port/TaxonomyPort.java`, `domain/port/ClassificationPolicyPort.java`
  - Test files: `graph/InputValidatorTest.java`, `graph/IntakeNodesTest.java`
  - Expected changes:
    - Validator: trimmed length 1..max (default 4000), no control chars except tab/CR/LF, identifier pattern for
      `triageId` and optional `syntheticCustomerId`.
    - `LoadTaxonomy`: unknown/incompatible version -> `failed`, `DEFINITIONS_UNAVAILABLE`.
    - `JevClassify`: one call with `TriageQuestionSets.v1()`; `JevException` (incl. mapper protocol failure) ->
      `error` with class name; success -> assessment, resolved model, question-set version.
    - Guard: unexpected `RuntimeException` -> `NODE_FAILED` (node + class name), route `failed`.
  - Acceptance:
    - Each node selects its route label and appends exactly one trace event.
  - Validation:
    - Fast: `./mvnw -q -Dtest='InputValidatorTest,IntakeNodesTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-1
  - Out of scope: gate/policy nodes
  - Notes: failure detail never includes exception messages.
  - Completion notes: Added `TriageRoutes` (single label->target table shared by trace and edges), `InputValidator`
    (identifier pattern, trimmed 1..4000 code points, control-character check, violation codes), `TriageStep`
    (node body + `route` helper), `GuardedNode` (decode/encode the single `triage` key; unexpected exception ->
    `NODE_FAILED` with class name, routed `failed`), `DefinitionSource` (resolve/reload policy+taxonomy by version,
    compatibility check) and the `ValidateInput`, `LoadTaxonomy`, `JevClassify` nodes. Test helpers `FakeJevPort`
    and `GraphTestDefinitions` (shipped v1 + strict test policy) were created here because the node tests need them.
    Checks: `./mvnw -q -Dtest='InputValidatorTest,IntakeNodesTest' test` passed on JDK 25 (15 + 11 tests).

- [x] TASK-3: ConfidenceGate, FinalPolicyGate and BuildTrace nodes
  - Goal: Path labelling, the single Policy Engine decision point and status derivation.
  - Size: M
  - Risk: high
  - Evidence: `application/policy/ClassificationPolicyEngine.java`
  - Requirements:
    - PRD: AC2, Main scenarios 1-4, Edge scenario 5
    - Plan: `Project invariants` (graph/policy boundary)
  - Target files:
    - `graph/ConfidenceGateNode.java`, `graph/FinalPolicyGateNode.java`, `graph/BuildTraceNode.java`
  - Reference files: `domain/model/RoutingDecision.java`
  - Test files: `graph/DecisionNodesTest.java`
  - Expected changes:
    - Gate: no assessment -> `error`; engine `LLM_INVESTIGATION` -> `ambiguous`; `UNCLASSIFIED` -> `abstain`;
      otherwise `clear`; never writes `routingDecision`.
    - Final gate: `error` -> `modelUnavailable`; otherwise `decide(attempts = maxInvestigations)`;
      `LLM_INVESTIGATION` -> `INVARIANT_VIOLATED`.
    - BuildTrace: failure -> `FAILED`; `HUMAN_REVIEW` -> `AWAITING_HUMAN`; `ABSTAIN` -> `COMPLETED`;
      `DRAFT_PROPOSAL` -> `CLASSIFIED`; appends terminal event to `END`.
  - Acceptance:
    - AC2: each label produced from deterministic state; ABSTAIN and HUMAN_REVIEW paths tested.
  - Validation:
    - Fast: `./mvnw -q -Dtest='DecisionNodesTest' test`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-2
  - Out of scope: LLM investigation (FEATURE-009)
  - Notes: high risk because it is the decision-authority boundary.
  - Completion notes: Added `ConfidenceGateNode` (labels from a preliminary pure-engine decision: no assessment ->
    error, UNCLASSIFIED -> abstain, MULTI_INTENT/LOW_CONFIDENCE/NEAR_TIE rationale -> ambiguous, else clear; never
    writes the decision), `FinalPolicyGateNode` (only writer of `routingDecision`; `modelUnavailable` on error, else
    `decide` with attempts = `maxInvestigations`; `LLM_INVESTIGATION` -> `INVARIANT_VIOLATED`; package-private
    `Decider` seam makes that guard testable) and `BuildTraceNode` (failure -> FAILED, HUMAN_REVIEW ->
    AWAITING_HUMAN, ABSTAIN -> COMPLETED, DRAFT_PROPOSAL -> CLASSIFIED; terminal event to END).
    Checks: `./mvnw -q -Dtest='DecisionNodesTest' test` passed on JDK 25 (16 tests).

- [x] TASK-4: Compiled TriageGraph runner with bounded iterations and offline end-to-end tests
  - Goal: Build/compile the SAA `StateGraph`, run it, derive `PARTIAL`/`FAILED`, and test all paths offline.
  - Size: M
  - Risk: high
  - Evidence: ADR FEATURE-006 Context (recursion limit completes silently)
  - Requirements:
    - PRD: AC1, AC2, AC3, AC4, AC5 (trace), AC7
    - Plan: `Architecture / Flow`, `Testing strategy`
  - Target files: `graph/TriageGraph.java`
  - Reference files: `src/test/java/io/github/denrzv/triage/GraphDependencySmokeTest.java`
  - Test files:
    - `graph/TriageGraphTest.java`, `graph/FakeJevPort.java`
    - `ArchitectureBoundaryTest.java` (graph port allow-list, no SR lifecycle types)
  - Expected changes:
    - `StateGraph` with one `REPLACE` key, the six nodes, conditional edges from `TriageRoutes`,
      `CompileConfig.recursionLimit(limit)`; compiled once, reusable.
    - `run` returns final state; missing BuildTrace -> `PARTIAL`/`ITERATION_LIMIT_REACHED`; exception or empty ->
      `FAILED`/`GRAPH_RUNTIME_ERROR`.
  - Acceptance:
    - AC3: all scenarios pass with fake Jev and shipped `v1` definitions, no credentials.
    - AC4: small limit -> `PARTIAL` with partial trace; failures -> `FAILED`.
    - AC5: trace node sequence equals the SAA-streamed node sequence.
    - AC7: ArchUnit allow-list passes.
  - Validation:
    - Fast: `./mvnw -q -Dtest='TriageGraphTest,ArchitectureBoundaryTest,GraphDependencySmokeTest' test`
    - Broader (only if needed): `./mvnw -q verify`
  - Dependencies: TASK-3
  - Out of scope: Spring bean wiring, REST
  - Notes: none
  - Completion notes: Added `TriageGraph`: SAA `StateGraph` with one `REPLACE` key, six guarded nodes, conditional
    edges from `TriageRoutes` (edge action only returns the node-selected label), `CompileConfig.recursionLimit`,
    an owned `MemorySaver` with `releaseThread(true)` and a unique checkpoint thread per run (verified empty after
    clear and PARTIAL runs). Runner maps missing BuildTrace -> `PARTIAL`/`ITERATION_LIMIT_REACHED`, runtime exception
    or empty state -> `FAILED`/`GRAPH_RUNTIME_ERROR`. Finding: an `Error` thrown in a node blocks `invoke` in SAA
    1.1.2.0; documented as a known limitation instead of catching `Error`. ArchUnit allow-list for graph ports added.
    Checks: `./mvnw -q -Dtest='TriageGraphTest,ArchitectureBoundaryTest,GraphDependencySmokeTest' test` (14+5+1) and
    `./mvnw -q verify` passed on JDK 25 (338 tests, 0 failures, 2 skipped opt-in live tests).

- [x] TASK-5: Documentation and durability limitation
  - Goal: Document the graph, statuses, trace, state format and the in-memory restart limitation.
  - Size: S
  - Risk: standard
  - Evidence: plan `Contracts`
  - Requirements:
    - PRD: AC6
    - Plan: `Quality gate impact` (docs)
  - Target files: `README.md`, `docs/architecture.md`, `docs/contracts.md`, `docs/assumptions-and-risks.md`
  - Reference files: `docs/adr/FEATURE-006-stategraph-foundation.md`
  - Test files: none
  - Expected changes:
    - README "Triage graph" section with node/route table, statuses, failure codes, test command.
    - Architecture/contracts "Implemented in FEATURE-006" notes; state not durable, restart loses runs.
  - Acceptance:
    - AC6: no durability claims; limitation stated explicitly.
  - Validation:
    - Fast: `python3 scripts/validate-workflow.py --ticket FEATURE-006`
    - Broader (only if needed): Not applicable
  - Dependencies: TASK-4
  - Out of scope: diagrams
  - Notes: none
  - Completion notes: README "Triage graph (FEATURE-006)" section (route table, decision authority, statuses and
    failure codes, input bounds, state format, not-durable statement, `Error` limitation, offline test command) and
    status banner; "Implemented in FEATURE-006" notes in `docs/architecture.md` (sections 5 and 8) and
    `docs/contracts.md` (sections 1 and 5); A-09 in `docs/assumptions-and-risks.md`. Plan field list and ADR
    decision 5 aligned with the implementation (derived `triageId`, owned `MemorySaver` released per run).
    Checks: `python3 scripts/validate-workflow.py --ticket FEATURE-006` passed.
