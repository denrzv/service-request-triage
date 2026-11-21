# ADR: FEATURE-006 — Triage StateGraph with single-key codec state, node-selected routes and runner-derived PARTIAL
Status: ACCEPTED
Date: 2026-11-21

## Context
ADR-001 and ADR-005 mandate a conditional Spring AI Alibaba `StateGraph` (pinned `spring-ai-alibaba-graph-core`
1.1.2.0), not a supervisor or a homegrown engine. ADR-003/ADR-004 make the pure `ClassificationPolicyEngine` the
only owner of formal type and route, with `ABSTAIN`/`HUMAN_REVIEW` first-class. ADR-007 keeps v1 in memory.

Verified facts of the pinned API (`javap` on the 1.1.2.0 jar):
- `StateGraph(KeyStrategyFactory)` uses the Jackson-based `SpringAIJacksonStateSerializer`; the default
  `CompileConfig` registers a `MemorySaver`, and `NodeExecutor` clones the state map through the graph serializer
  between steps. State values therefore must survive that serializer.
- Domain records such as `ChoiceJudgment<E extends Enum<E>>` (enum-keyed `EnumMap`) cannot be rebuilt by a generic
  Jackson map serializer without type information.
- Edge actions (`EdgeAction.apply(OverAllState) -> String`) cannot update state, so they cannot write a trace.
- `CompileConfig.recursionLimit(int)` bounds iterations; when it is reached `MainGraphExecutor` completes the run
  without an exception (`isMaxIterationsReached` → `handleCompletion`).

## Decision
1. **Single state key holding a versioned JSON document.** `OverAllState` holds one key `triage` (`KeyStrategy.REPLACE`)
   whose value is the JSON string produced by `TriageGraphStateCodec` from the immutable `TriageGraphState` record
   (`schemaVersion: triage-graph-state-v1`). Nodes decode, compute, and return the re-encoded next state. The codec
   uses explicit wire records with string enums and is strict (unknown fields, unknown enum constants, wrong schema
   version are rejected). Strings survive any SAA serializer and the future PostgreSQL saver (FEATURE-011).
2. **Nodes select routes; edges only read them.** Every node except `BuildTrace` stores the chosen route label in
   state (`route`) and appends one `DecisionTraceEvent(sequence, node, routeLabel, nextNode)`. Conditional edge
   actions return `state.route` and the label→target maps live in one route table (`TriageRoutes`). Labels:
   `ValidateInput: valid|invalid`, `LoadTaxonomy: loaded|failed`, `JevClassify: classified|error|failed`,
   `ConfidenceGate: clear|ambiguous|abstain|error|failed`, `FinalPolicyGate: decided|failed`. `invalid`/`failed`
   go to `BuildTrace`; all `ConfidenceGate` routing labels go to `FinalPolicyGate` in v1, and FEATURE-009 remaps
   `ambiguous` to the investigation node.
3. **Policy Engine stays the authority.** `ConfidenceGate` classifies the path from the engine's pure decision on the
   assessment (`LLM_INVESTIGATION` → `ambiguous`; `UNCLASSIFIED` → `abstain`; otherwise `clear`; no assessment →
   `error`) and never writes `routingDecision`. `FinalPolicyGate` is the only node writing `routingDecision`: it calls
   `modelUnavailable` on `error`, otherwise `decide` with `investigationAttempts = policy.maxInvestigations()`
   because no investigator is wired in v1. A returned `LLM_INVESTIGATION` is an invariant violation → `FAILED`.
4. **Statuses.** `BuildTrace` derives the execution status: any failure → `FAILED`; `HUMAN_REVIEW` →
   `AWAITING_HUMAN`; `ABSTAIN` → `COMPLETED`; `DRAFT_PROPOSAL` → `CLASSIFIED` (no draft is created before
   FEATURE-010). The runner (`TriageGraph.run`) returns `PARTIAL` with `ITERATION_LIMIT_REACHED` when the graph ended
   without `BuildTrace` (no interrupts are configured, so the iteration limit is the only such cause in v1), and
   `FAILED`/`GRAPH_RUNTIME_ERROR` if the framework throws or returns no state. Node bodies run inside a guard that
   turns any unexpected `RuntimeException` into `NODE_FAILED` (node name and exception class only).
5. **Bounded execution.** One Jev call per run (adapter keeps its bounded retries); `recursionLimit` defaults to 12
   (6-node happy path plus margin) and is a constructor argument validated `>= 1`. The graph registers its own `MemorySaver`
   with `releaseThread(true)` and a fresh checkpoint thread id per run, so in-memory checkpoints are released when
   a run ends instead of accumulating.
6. **Execution state only.** `TriageGraphState` carries no SR lifecycle; the graph depends only on
   `JevClassificationPort`, `TaxonomyPort` and `ClassificationPolicyPort` (ArchUnit allow-list). New shared domain
   types `IncomingCustomerRequest`, `TriageStatus` and `DecisionTraceEvent` follow `docs/contracts.md` §1.
7. **No Spring wiring yet.** The graph is a plain object built from ports; exposing it as a bean with request
   handling is FEATURE-012, so keyless start-up is unchanged.

## Alternatives considered
### Option A — One `OverAllState` key per field with framework Jackson serialization
- Pros: idiomatic per-key `KeyStrategy` (e.g. `APPEND` for trace); less custom code.
- Cons: generic records with enum-keyed maps do not round-trip through the default serializer; hidden type
  metadata; partial updates across keys make invariants harder to enforce.

### Option B — Custom `StateSerializer` subclass for typed records
- Pros: typed values in `OverAllState`.
- Cons: couples to serializer internals (`JacksonStateSerializer`, `TypeMapper`) that may change; still needs the
  same wire mapping.

### Option C — Edges compute labels from state directly (no `route` field)
- Pros: one less state field.
- Cons: the trace could not record the chosen edge; routing logic would be split between nodes and edges.

## Consequences
- Positive: deterministic, explicit state contract; trace contains every transition; later features remap labels.
- Negative: encode/decode per node (negligible at PoC scale); label convergence on `FinalPolicyGate` in v1.
- Risks: SAA behavior around cloning/max iterations changes in a future release.
- Mitigations: tests pin the observed behavior (state clone with default `MemorySaver`, PARTIAL on limit).

## Rollout / rollback
- Rollout: additive classes; no runtime wiring or configuration change.
- Rollback: revert the PR.

## Links
- PRD: `docs/prd/FEATURE-006.prd.md`
- Plan: `docs/plan/FEATURE-006.md`
- Tasklist: `docs/tasklist/FEATURE-006.md`
- Baseline: ADR-001, ADR-003, ADR-004, ADR-005, ADR-007.
