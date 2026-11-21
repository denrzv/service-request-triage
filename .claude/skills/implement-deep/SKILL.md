---
name: implement-deep
description: "Escalation-only high-effort central implementation for one high-risk or repeatedly failing task."
argument-hint: "<TICKET> <TASK-N|TASK-FIX-N>"
arguments:
  - ticket
  - task_id
disable-model-invocation: true
effort: high
---
Implement task `$1` for ticket `$0` in the central session with a bounded
high-effort pass.

Before editing:
1. Run `python3 scripts/aidd-task-context.py --ticket $0 --task $1`.
2. Confirm the task declares `Risk: high`, or that two evidence-backed central
   implementation attempts left the same non-environmental failure.
3. Run `python3 scripts/validate-workflow.py --ticket $0 --quiet`.

Follow the exact task block. Do not read the full tasklist, take adjacent tasks,
or use this command as a generic retry for environmental failures.
