---
name: bugfix
description: "Capture and repair a confirmed bug within an existing approved, uncommitted ticket without rerunning discovery, research, planning, or regular task decomposition."
argument-hint: "<TICKET> \"Confirmed symptom, evidence, or diagnosis\""
arguments:
  - ticket
  - finding
---
# Confirmed bug repair

Repair a confirmed defect that violates the approved contract for ticket `$0`.
If `$0` is empty, read `docs/.active_ticket`. Require a concrete finding in
`$1`; do not infer a bug from an empty description.

This skill runs in the central session. It is a short intake path into the
existing repair loop, not a lower-quality alternative to the normal gates.

## 1. Validate eligibility

1. Run `python3 scripts/aidd_state.py --ticket $0 init`, inspect the state, and
   run `python3 scripts/validate-workflow.py --ticket $0 --quiet`.
2. Require an existing `PRD_READY`, `PLAN_APPROVED`, `TASKLIST_READY`, and
   `ACCEPTED` status for every ticket-owned and plan-linked ADR.
3. Map the finding to a specific acceptance criterion, approved requirement,
   or regression introduced by the current ticket.
4. Use this route only before the ticket's changes are committed or released.
5. Stop and recommend `/feature-development` with a new ticket when the request
   adds behavior outside the approved contract, lacks preparation artifacts,
   contradicts a requirement, requires a new product/architecture decision, or
   repairs already-committed/released work.
6. If the fix crosses a conditional-gate trigger not required by the current
   plan, run `/gate-policy $0` before implementation.

Do not bypass `BLOCKED`, `CANCELLED`, `SESSION_CHECKPOINT_READY`, or an
unresolved `HUMAN_REQUIRED` reason. Resume `HUMAN_REQUIRED` only when the
current user invocation explicitly resolves or overrides the exact recorded
reason and the action is otherwise permitted. Never bypass repair-budget or
destructive-action escalation.

## 2. Record a durable finding

Create or update `reports/bugfix/$0.md`. Preserve existing findings and assign
the next stable ID:

- `BUG-BLOCK-N` when the defect breaks an acceptance criterion, causes data
  loss/corruption, security exposure, or a production-critical runtime path;
- `BUG-IMPORTANT-N` for a significant correctness or operability defect that
  does not meet Blocking severity.

Treat the user's diagnosis as a hypothesis until source, logs, or a focused
reproduction confirms it. Deduplicate against the bugfix report, current
review reports, and tasklist before appending.

Every finding must include:

```text
Severity
Status: Open
User evidence or reproduction
Confirmed root cause
PRD/plan acceptance mapping
Required change
Acceptance
Likely target and test files
Fast and broader validation
Out of scope
```

Use `Status: BUGFIX_OPEN` at report level while any finding is unresolved. Do
not edit product code while capturing an ambiguous finding; stop for the
missing decision instead.

## 3. Create controlled fix tasks

1. Run
   `python3 scripts/aidd-task-context.py --ticket $0 --repair-status`.
2. Run `/fix-tasks $0 bugfix` to append one deduplicated batch of
   `TASK-FIX-N` entries. The normal adaptive repair budget applies.
3. If the finding is already linked from an existing task, reuse that task;
   never duplicate or renumber it.
4. Transition to `REPAIRING` with the sorted source finding IDs as the failure
   fingerprint and use `--count-attempt` exactly once for the batch.

Only `/fix-tasks` may translate the intake report into tasklist entries. Do not
append ad hoc regular tasks or patch code without a task.

## 4. Implement centrally

For every newly appended or reused unchecked fix task, in dependency order:

1. Run `/implement $0 TASK-FIX-N`.
2. Add a regression test that exercises the real failing boundary. Prefer a
   red-before-green reproduction when it can run safely.
3. Run the task's narrow checks and update its checkbox and compact completion
   notes.
4. Apply the normal three-implementation-pass session checkpoint.

Do not rerun `/idea`, `/researcher`, `/plan`, or `/tasks`.

## 5. Converge and finish

After the fix diff converges:

1. Run `/pre-commit $0` and require current deterministic PASS evidence.
2. Run `/review $0` exactly once against the converged diff. The reviewer must
   verify every bugfix finding, mark it `Resolved` only with evidence, and set
   the report to `Status: BUGFIX_RESOLVED` only when all findings resolve.
3. For new Blocking or Important findings, use the normal
   `/fix-tasks $0 verification` repair loop.
4. Run `/validate $0` only after `VERIFICATION_PASS`.
5. Stop at `READY_FOR_USER_COMMIT` and report the fix tasks, regression tests,
   gates, and changed files.

Never stage, commit, push, merge, or create a PR.
