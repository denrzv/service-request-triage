# FEATURE-006: StateGraph Foundation
Status: PRD_READY

Issue: https://github.com/denrzv/service-request-triage/issues/6 (depends on FEATURE-003 and FEATURE-005, both
merged to `main`).
Branch: `claude/feature-005-development-vrzpb2` (session-assigned branch, restarted from `main` after PR #30 merged;
one ticket per branch).

## Context / Problem
Milestone M2 introduces the deterministic decision graph. FEATURE-002..005 delivered a typed Jev port, the
`triage-jev-v1` question set with a strict `JevAssessmentMapper`, the pure `ClassificationPolicyEngine`, versioned
taxonomy/policy ports and a measured baseline. Nothing yet orchestrates them per request: there is no
`IncomingCustomerRequest`, no `TriageStatus`, no typed graph state, no node/edge trace, and the pinned Spring AI
Alibaba Graph (`spring-ai-alibaba-graph-core` 1.1.2.0) is used only by a smoke test. FEATURE-007 (confidence
routing) and FEATURE-009 (bounded LLM fallback) need a graph whose conditional edges they can extend without
rewriting orchestration.

## Goals
- A conditional `StateGraph` built with the released SAA API (`StateGraph`, `addConditionalEdges`,
  `KeyStrategy`, `CompileConfig.recursionLimit`), with nodes `ValidateInput`, `LoadTaxonomy`, `JevClassify`,
  `ConfidenceGate`, `FinalPolicyGate`, `BuildTrace`.
- A typed, immutable `TriageGraphState` (execution state only) with an explicit, tested serialized form.
- Deterministic conditional edges: input `valid|invalid`, definitions `loaded|failed`, gate
  `clear|ambiguous|abstain|error`.
- Formal type and route decided only by the existing pure Policy Engine; Jev failures map to the engine's
  `modelUnavailable` decision (`HUMAN_REVIEW` / `MODEL_UNAVAILABLE`).
- Explicit execution statuses including `PARTIAL` (graph stopped before `BuildTrace`, e.g. iteration limit) and
  `FAILED` (rejected input, missing definitions, unexpected node failure).
- A trace of node entries and edge transitions (with chosen edge label) in every result.
- Runs end-to-end offline with a fake `JevClassificationPort` and the shipped taxonomy/policy `v1`.
- Documentation that the v1 graph is in-memory and not durable.

## Non-goals
- LLM investigation node, DeepSeek, re-evaluation loop (FEATURE-008/009). The `ambiguous` edge is the seam.
- Calibrated thresholds, Companion-Noul policy changes, policy `v2` (FEATURE-007).
- Mock Task Management, draft creation, idempotent side effects (FEATURE-010).
- Human review pause/resume, PostgreSQL checkpoints (FEATURE-011).
- REST API, Spring bean wiring of the graph for request handling, Micrometer metrics, `TriageResult` DTO
  (FEATURE-012).
- Changing the Policy Engine, question set, goldset or Jev adapter behavior.

## Users & Roles
- Developers of FEATURE-007/009/011/012 who extend or expose the graph.
- Reviewers/analysts who need a per-run decision trace to understand why a route was chosen.

## User Stories
- As a developer, I want a typed graph state and named conditional edges so that later features add nodes by
  remapping an edge label instead of rewriting orchestration.
- As a reviewer, I want every run to report node/edge transitions, status and the policy decision so that the
  chosen path is explainable without a model-generated explanation.
- As an operator, I want a runaway or broken graph to end as `PARTIAL`/`FAILED` with a reason rather than hang,
  loop, or silently return a confident route.

## Main Scenarios
1. Clear: valid input → taxonomy/policy `v1` loaded → one Jev call → assessment mapped → gate `clear` →
   Policy Engine decision (e.g. `DRAFT_PROPOSAL`, or `HUMAN_REVIEW` for approval-required types) → trace built.
   Status `CLASSIFIED` for `DRAFT_PROPOSAL`, `AWAITING_HUMAN` for `HUMAN_REVIEW`.
2. Ambiguous: engine reports concerns (multi-intent, low confidence, near-tie) → gate `ambiguous` → no investigator
   is wired in v1, so `FinalPolicyGate` evaluates with the investigation budget treated as exhausted →
   `HUMAN_REVIEW` with `INVESTIGATION_BUDGET_EXHAUSTED` → `AWAITING_HUMAN`.
3. Abstain: low taxonomy fit or no rule match → gate `abstain` → engine's configured unclassified action
   (`ABSTAIN` → `COMPLETED`, or `HUMAN_REVIEW` → `AWAITING_HUMAN`).
4. Error: Jev unavailable/client/protocol failure or mapper rejection → gate `error` →
   `modelUnavailable` → `HUMAN_REVIEW` / `MODEL_UNAVAILABLE` → `AWAITING_HUMAN`. Never a defaulted confidence.

## Edge Scenarios
1. Blank, oversized or otherwise invalid input → edge `invalid` → `BuildTrace`; status `FAILED`, failure code
   `INPUT_REJECTED`; no Jev call, no routing decision.
2. Requested taxonomy/policy version not loaded, or incompatible → edge `failed` → `FAILED`,
   `DEFINITIONS_UNAVAILABLE`; no Jev call.
3. Unexpected runtime exception inside a node → recorded as `NODE_FAILED` (node name, exception class only; no
   message text that could contain customer input) → `FAILED`.
4. Iteration limit reached before `BuildTrace` → result `PARTIAL` with the trace collected so far and failure code
   `ITERATION_LIMIT_REACHED`.
5. Engine unexpectedly returns `LLM_INVESTIGATION` at the final gate (impossible with budget exhausted) →
   `FAILED`, `INVARIANT_VIOLATED`; never surfaced as a route.
6. Framework-level failure outside nodes → `FAILED`, `GRAPH_RUNTIME_ERROR`.

## Acceptance Criteria
(Mirrors issue #6.)
- AC1: Graph nodes include `ValidateInput`, `LoadTaxonomy`, `JevClassify`, `ConfidenceGate`, `FinalPolicyGate`,
  `BuildTrace`.
- AC2: Conditional edges select clear / ambiguous / abstain / error paths using deterministic state.
- AC3: Graph runs end-to-end with fake Jev and the shipped policies without cloud credentials.
- AC4: Graph execution has a bounded iteration count and explicit `PARTIAL`/`FAILED` statuses.
- AC5: Graph state serialization tests, and a trace with node/edge transitions.
- AC6: In-memory v1 restart limitation documented; no misleading durability claims.
- AC7: No direct domain SR status mutation by the graph runtime.

## Non-functional requirements
- Security: customer text is untrusted; it is sent only to `JevClassificationPort`, never logged, never put in the
  trace or failure details. No credentials needed for the default build.
- Privacy/data protection: synthetic English only; no goldset labels reachable from graph code (test fixtures use
  hand-written synthetic messages, not goldset labels).
- Reliability: at most one Jev call per run (adapter keeps its own bounded retries); bounded graph iterations;
  every terminal state carries a status and, when not successful, a failure code.
- Performance/scale: offline run in milliseconds; graph compiled once and reusable across runs (thread-safe:
  per-run state only).
- Observability: trace events (sequence, node, edge label, target) plus taxonomy/policy/question-set versions and
  resolved model in state; metrics are FEATURE-012.
- Compatibility: Java 25, Boot 3.5.16, SAA 1.1.2.0, no new dependencies; Spring context start-up unchanged
  (keyless).

## Data considerations
- Identifiers: `triageId` (caller-provided, validated) is the run identifier and future idempotency key;
  `graphRunId` is not introduced until checkpoints exist (FEATURE-011).
- Sensitive data: the request message lives in graph state only for the duration of a run; it is excluded from
  `toString()` and the trace.
- Retention/lifecycle: none; state is in memory and discarded after the run (not durable).

## Constraints & Assumptions
- The SAA default `CompileConfig` registers an in-memory `MemorySaver`, and node execution clones state through the
  graph's `StateSerializer`; graph state values must therefore survive that serializer. Verified by tests in the
  first task before the rest of the design depends on it.
- When the iteration limit is reached SAA ends the run without an exception (observed in `MainGraphExecutor`
  bytecode: `isMaxIterationsReached` → completion); `PARTIAL` is therefore detected by the graph runner from state,
  to be confirmed by a test.
- `TriageStatus` values follow `docs/contracts.md` §1; this ticket uses `CLASSIFIED`, `AWAITING_HUMAN`,
  `COMPLETED`, `PARTIAL`, `FAILED` (`RECEIVED`/`INVESTIGATING` reserved).
- Maximum message length: 4,000 characters (assumption for the PoC input bound; documented and configurable via the
  graph factory argument, not a business rule).

## Risks
- SAA internal behavior (cloning, max-iteration handling) may change across releases; pinned version and tests
  guard it.
- Status naming (`CLASSIFIED` for an un-drafted `DRAFT_PROPOSAL`) may need refinement when FEATURE-010 creates
  drafts; documented.
- Converging the gate labels on `FinalPolicyGate` in v1 could look redundant; it keeps the Policy Engine the single
  decision authority, and the labels are asserted in tests and recorded in the trace.

## Open Questions
- None blocking.
