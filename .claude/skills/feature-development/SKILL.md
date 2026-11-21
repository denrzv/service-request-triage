---
name: feature-development
description: "Run the AIDD ticket lifecycle in one central session with durable state and deterministic verification."
argument-hint: '<TICKET> "Short title/context"'
arguments:
  - ticket
  - title
disable-model-invocation: true
effort: medium
---
# Feature development

Complete ticket `$0` with context `$1` using the repository lifecycle in
`workflow.md`. Reuse durable state and repository artifacts instead of replaying
completed phases from conversation history.

## Startup

1. Check the current Git branch.
2. If already on a non-default working branch, keep it.
3. If on `main`, `master`, or detached HEAD, create or switch to
   `feature/<TICKET>-<short-slug>` (for example
   `feature/FEATURE-002-jev-client`) before application edits. Prefer a
   dedicated Git worktree per `docs/collaboration.md`; never share a worktree
   or branch with Codex.
4. Set or confirm `docs/.active_ticket` is `$0`.
5. Read the ticket's scope, dependencies, and acceptance criteria from its
   GitHub issue (`gh issue list --search "$0"` / `gh issue view <N>`) and the
   matching section of `docs/implementation-plan.md`. Stop at
   `HUMAN_REQUIRED` if a dependency FEATURE is not merged yet.
6. Initialize and read controller state:

```bash
python3 scripts/aidd_state.py --ticket $0 init
python3 scripts/aidd_state.py --ticket $0 show
```

Normal lifecycle operations include Git branch/status/switch/diff commands,
Python workflow scripts, Maven checks, `gh issue view`, and the repository verification
commands required by the active ticket.

## Preparation

Resume from controller state. Run only missing phases:

1. `/idea $0 "$1" "<durable context summary>"`
2. `/researcher $0` when research is required
3. `/plan $0`
4. `/tasks $0`
5. `python3 scripts/validate-workflow.py --ticket $0`

Do not implement until the PRD, plan, ADR decision gate, and tasklist are ready.
If a real product decision is unresolved, transition to `HUMAN_REQUIRED` with a
clear `nextAction`.

## Implementation

Read pending tasks compactly:

```bash
python3 scripts/aidd-task-context.py --ticket $0 --pending
```

Implement one task at a time with `/implement $0 TASK-N`, update its completion
notes, and run the focused task checks. Use `/implement-deep` only for an
explicitly high-risk task or after two evidence-backed attempts leave the same
non-environmental failure.

After three implementation passes in the current conversation, checkpoint the
controller state and return `SESSION_CHECKPOINT_READY`; a fresh session resumes
with the same `/feature-development` command.

## Verification and repair

1. Run `/pre-commit $0`.
2. Require current verification evidence:

```bash
python3 scripts/aidd_verification.py --ticket $0 check
```

3. Run `/review $0` as the single independent verifier.
4. For Blocking or Important findings, use `/fix-tasks $0 verification`,
   implement the generated fix tasks, rerun deterministic verification when the
   relevant diff changes, then rerun `/review $0`.
5. Respect the repository repair budget and stop at `HUMAN_REQUIRED` when it is
   exhausted or a genuine user decision is needed.

## Final gate

After verification passes:

1. Recheck `python3 scripts/aidd_verification.py --ticket $0 check`.
2. Run `/validate $0`.
3. Inspect `git status --short` and `git diff --stat`.
4. Confirm controller state is `READY_FOR_USER_COMMIT`.
5. Report completed tasks, checks, gate outcomes, and changed files.

The standard lifecycle stops at `READY_FOR_USER_COMMIT` as defined in
`workflow.md`. Never stage, commit, push, merge into `main` or `master`, or
open a PR automatically; the user owns commit and PR creation. `/review $0`
runs exactly once per converged diff.
