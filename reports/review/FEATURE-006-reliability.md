# FEATURE-006: Reliability review
Status: RELIABILITY_PASS
Outcome: RELIABILITY_PASS

## Checklist
- Bounds: `recursionLimit` default 12 (validated >= 1) over a 6-node acyclic path; exactly one Jev call per run, with timeouts and
  bounded retries inherited from the adapter; the graph adds no retries.
- Failure semantics: node exceptions -> `NODE_FAILED` -> `BuildTrace` -> `FAILED`; early stop -> `PARTIAL`/`ITERATION_LIMIT_REACHED`
  (confirmed by test against the real SAA runtime); framework exception or empty state -> `FAILED`/`GRAPH_RUNTIME_ERROR`. Provider
  failure leads to `MODEL_UNAVAILABLE` human review, never a defaulted route.
- Ordering/concurrency: sequential graph; compiled graph is stateless and reused; per-run state and unique checkpoint thread id;
  `compiledGraphIsReusableForConcurrentRuns` passes.
- Recovery/durability: not durable by design (ADR-007); checkpoints are released per run (test asserts no accumulation); README,
  architecture and risks A-09 state the restart limitation without a durability claim.
- Observability: trace (sequence, node, label, target), versions and resolved model in the result; metrics deferred to FEATURE-012.
- Known limitation (accepted, documented): a JVM `Error` inside a node can block `run` in SAA 1.1.2.0; callers needing a deadline
  bound the call in FEATURE-012.

## Findings
No Blocking or Important findings.
