---
name: fix-tasks
description: "Append implementation-ready TASK-FIX entries for unresolved Blocking and Important findings from deterministic checks, consolidated verification, or confirmed bug intake."
argument-hint: "<TICKET> <bugfix|pre-commit|verification|review|qa|security|contract|db|reliability>"
arguments:
  - ticket
  - source
---
# Quality finding task decomposition

Append fix tasks for unresolved findings from `$1` for ticket `$0`.

## Source report

- `bugfix`: `reports/bugfix/$0.md`
- `verification`: all existing blocked reports below, processed as one
  deduplicated batch in the listed order
- `review`: `reports/review/$0.md`
- `qa`: `reports/qa/$0.md`
- `pre-commit`: `reports/pre-commit/$0.md`
- `security`: `reports/security/$0.md`
- `contract`: `reports/review/$0-contracts.md`
- `db`: `reports/review/$0-db.md`
- `reliability`: `reports/review/$0-reliability.md`

## Rules

1. Run
   `python3 scripts/aidd-task-context.py --ticket $0 --repair-status`.
   Count eligible unresolved findings before writing. If the complete new batch
   would exceed the reported repair budget, append nothing and return
   `BLOCKED_NEEDS_USER_INPUT` with the current usage, requested slots, and
   source finding IDs.
   Do not add or change `Repair budget override`; only an explicit
   user/maintainer edit may authorize additional capacity.
2. Read the selected source report(s), current tasklist, PRD, plan, current
   diff, and only the files needed to make each finding actionable. For
   `verification`, inspect review, QA, contract, DB, reliability, security, and
   docs reports that exist and are currently blocked. For `bugfix`, require a
   durable `BUG-BLOCK-N` or `BUG-IMPORTANT-N` finding mapped to the existing
   approved contract; refuse free-form findings that bypass `/bugfix` intake.
3. Create tasks only for unresolved `Blocking` and `Important` findings.
   Ignore `Nice-to-have` findings unless they are explicitly required by an
   acceptance criterion.
4. Append `TASK-FIX-N` entries using the next unused integer. Never renumber,
   rewrite, reopen, or delete an existing task.
5. Put the report finding ID in `Source`. If that ID already appears in any
   task or another selected report references the same primary finding, do not
   create a duplicate.
6. Give every fix task the same actionable handoff fields as a regular task:
   goal, size, requirements, target/reference/test files, expected changes,
   acceptance, validation, dependencies, and out of scope.
7. Keep `Status: TASKLIST_READY` only when all appended tasks are independently
   implementable. If a finding requires a user decision, return
   `BLOCKED_NEEDS_USER_INPUT` without inventing a solution.
8. Do not modify application code or quality reports.

Output the IDs of newly appended tasks. If no eligible unresolved findings
exist, report `NO_FIX_TASKS_REQUIRED`.
