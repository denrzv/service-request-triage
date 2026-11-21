---
name: small-change
description: "Implement a bounded low-risk new change through compact AIDD preparation and the normal verification gates. Use for one small outcome that needs no research, ADR, API, database, auth, security-boundary, or architecture work."
argument-hint: "<TICKET> \"Bounded change and expected outcome\""
arguments:
  - ticket
  - change
disable-model-invocation: true
effort: medium
---
# Compact small-change lifecycle

Bring ticket `$0` for bounded change `$1` to `READY_FOR_USER_COMMIT` in the
central session. Require both arguments. This is a compact contract path, not
permission to code without preparation or skip final gates.

Never stage, commit, push, merge, rebase, tag, create a PR, deploy, waive a
gate, or modify unrelated changes.

## 1. Qualify the change

Inspect the target and its nearest tests/readme before writing artifacts. Use
this path only when all of the following are true:

- one independently releasable outcome;
- one module or operational boundary;
- one to three implementation tasks and normally no more than six relevant
  product/test/docs files;
- implementation follows an existing repository pattern;
- requirements and acceptance are concrete without external research or an
  architecture decision;
- rollback is a simple code/config revert.

Reject this path and recommend `/feature-development` when the change includes:

- OpenAPI/AsyncAPI, public/event/message contract, or compatibility changes;
- database schema, migration, backfill, retention, or persistent-data changes;
- authentication, authorization, cryptography, secrets, PII, or a new trust
  boundary;
- a new dependency, module, external integration, background workflow, or
  cross-component architecture change;
- an ADR trigger, destructive operation, production execution, unresolved
  product decision, or more than three tasks;
- work already represented by an approved existing ticket (resume that ticket
  instead).

Bounded operational scripts and runtime configuration are eligible. For them,
require explicit input validation, a non-production-safe default or preserved
backward-compatible behavior, no secret output, failure on unknown values,
dry-run/static checks where available, and `Reliability review: required`.
Require `Docs update: required` when operator-visible usage changes.

If scope crosses an eligibility boundary later, stop at `HUMAN_REQUIRED`; do
not silently grow the compact contract.

## 2. Start durable state

1. Refuse to replace a different active non-terminal ticket unless the user
   explicitly asks to switch.
2. Set `docs/.active_ticket` to `$0`.
3. Run `python3 scripts/aidd_state.py --ticket $0 init` and inspect the state.
4. On `main`, `master`, or detached HEAD, stop before application edits and ask
   the user to select a feature branch or isolated worktree.
5. Transition to `PREPARING` with phase `small-change-preparation`.

Resume only artifacts containing `Workflow path: SMALL_CHANGE`. Never replace
full feature artifacts with compact ones.

## 3. Create the compact contract

Create the following artifacts directly in the central session using the
normal templates, but keep each section concise and fully resolved:

### PRD

Write `docs/prd/$0.prd.md` with `Workflow path: SMALL_CHANGE` and:

- `Status: PRD_READY`;
- one problem statement and one goal;
- explicit non-goals;
- two to five testable acceptance criteria;
- compatibility and rollback expectations;
- `Open Questions` set to `None`.

### Plan

Write `docs/plan/$0.md` with `Workflow path: SMALL_CHANGE` and:

- `Status: PLAN_APPROVED`;
- exact target symbols/files and the existing pattern to follow;
- error behavior, observability, tests, rollout, and rollback;
- `Render diagrams required: no` and all diagram impacts `not changed`;
- `Gate policy version: 1` with Review and QA required;
- every conditional gate explicitly `required` or `not-required` with a
  rationale. Contract and DB review must be `not-required` because changes
  requiring them are ineligible. Upgrade Reliability and Docs as described
  above.

### Tasklist

Write `docs/tasklist/$0.md` with `Workflow path: SMALL_CHANGE` and:

- `Status: TASKLIST_READY`;
- one to three `TASK-N` entries;
- concrete target/reference/test files, acceptance mapping, fast validation,
  broader validation, dependencies, and out-of-scope boundaries;
- no research dependency; record it as not required for this compact path.

Do not invoke `/idea`, `/researcher`, `/plan`, or `/tasks`. Run
`python3 scripts/validate-workflow.py --ticket $0`; continue only when every
preparation gate passes. Then transition to `READY_FOR_IMPLEMENTATION`.

## 4. Implement centrally

For each unchecked task in dependency order:

1. Transition to `IMPLEMENTING` with the task ID and `--count-attempt`.
2. Run `/implement $0 TASK-N`.
3. Require focused tests or a concrete rationale when behavior cannot be
   executed safely.
4. Run the narrow checks and update the task checkbox plus compact completion
   notes.

For shell/operational scripts, normally run syntax validation, ShellCheck, and
table-driven argument/dry-run checks. Never execute a real deployment as a
test. Use the normal session checkpoint after three implementation passes.

## 5. Verify and converge

1. Transition to `VERIFYING` and run `/pre-commit $0`.
2. Require
   `python3 scripts/aidd_verification.py --ticket $0 check` to pass for the
   current fingerprint.
3. Run `/review $0` exactly once per converged diff. This remains the only
   normal subagent invocation.
4. Route Blocking or Important findings through
   `/fix-tasks $0 verification`, the normal repair budget, and central
   `/implement` passes.
5. If review proves the actual scope was not eligible for a small change,
   transition to `HUMAN_REQUIRED` and recommend a normal feature ticket.

## 6. Finish

After `VERIFICATION_PASS`:

1. Run `/validate $0` and require strict final validation.
2. Inspect `git status --short` and `git diff --stat`.
3. Stop at `READY_FOR_USER_COMMIT`.
4. Report the compact contract, completed tasks, checks, gate results, changed
   files, and that nothing was staged or committed.
