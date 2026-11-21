---
name: pre-commit
description: "Run deterministic whole-diff checks and record a machine-readable pass/block report for automatic repair."
argument-hint: "<TICKET>"
arguments:
  - ticket
allowed-tools: Read, Bash, Write, Edit
---
# Deterministic whole-diff verification

Run the repository's complete local verification for ticket `$0`. If `$0` is
empty, read `docs/.active_ticket`.

This skill may be invoked directly by the user or automatically by
`/feature-development` as its deterministic verification phase. Automatic
invocation does not relax any gate or permission.

Execute exactly once:

```bash
python3 scripts/aidd_verification.py --ticket $0 run
```

The wrapper runs `bash scripts/pre-commit-check.sh`, stores the complete local
log outside Git, and writes diff-bound machine evidence to:

```text
.aidd/runtime/features/<TICKET>/verification/latest.json
```

Write the compact human/fix-task report to
`reports/pre-commit/<TICKET>.md`.

## Passing result

When the command exits successfully:

```text
Status: PRE_COMMIT_PASSED
Outcome: PRE_COMMIT_PASSED
```

Record the command, covered modules/checks, and any non-blocking warnings.
Before reporting pass, require:

```bash
python3 scripts/aidd_verification.py --ticket $0 check
```

## Failing result

When the command fails:

```text
Status: PRE_COMMIT_BLOCKED
Outcome: PRE_COMMIT_BLOCKED
```

Create one stable finding per independently fixable failure:

```text
### PRECOMMIT-BLOCK-N: Short title
- Severity: Blocking
- Status: Unresolved
- Evidence: exact failing check and concise error
- Required change:
- Acceptance:
- Likely target files:
- Validation:
```

Preserve finding IDs when the same failure recurs. Do not fix code in this
skill; the autonomous orchestrator delegates the report to `/fix-tasks`.
Do not include secrets or large raw logs.

Compare failures with the ticket diff and existing evidence. Put demonstrably
pre-existing or unrelated failures under `## External blockers`; do not turn
them into ticket fix findings. Keep `PRE_COMMIT_BLOCKED` and explain the
evidence so the orchestrator stops rather than modifying unrelated code.
