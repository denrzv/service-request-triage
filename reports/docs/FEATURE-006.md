# FEATURE-006: Documentation update
Status: DOCS_COMPLETE
Outcome: DOCS_COMPLETE

Verifier pass over maintained documentation (no edits made).

## Checked
- `README.md`: status line and "Triage graph (FEATURE-006)" section (route table, statuses, input bounds, state format, not-durable
  and Error limitation, test command) match `TriageRoutes`, `BuildTraceNode`, `InputValidator` and `TriageGraph`.
- `docs/architecture.md`: implemented-node paragraph and section 8 current-state note match the code; no durability or resume
  guarantee claimed; unimplemented nodes listed.
- `docs/contracts.md`: section 1 and 5 implemented notes match `TriageGraphState` fields, `DecisionTraceEvent` and the codec schema.
- `docs/assumptions-and-risks.md` A-09 updated. ADR `ACCEPTED`; PRD (`PRD_READY`), plan (`PLAN_APPROVED`) and tasklist present.
- Diagrams: plan declares no impact (no new actor/container); none required.

## Findings
No Blocking or Important findings.
