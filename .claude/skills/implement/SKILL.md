---
name: implement
description: "Implement exactly one regular or quality-fix task from the active tasklist after preparation gates pass."
argument-hint: "<TICKET> <TASK-N|TASK-FIX-N>"
arguments:
  - ticket
  - task_id
---
Implement task `$1` for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Rules:
1. Read only the requested task with
   `python3 scripts/aidd-task-context.py --ticket $0 --task $1`.
2. Validate gates with
   `python3 scripts/validate-workflow.py --ticket $0 --quiet`.
3. Implement exactly one requested `TASK-N` or `TASK-FIX-N`. Do not take adjacent tasks unless the tasklist explicitly says they are inseparable.
4. Keep the diff small and aligned with existing style.
5. Add/update tests.
6. Check the task and add only 6-10 lines of implementation notes.
7. Run the smallest relevant checks. If checks cannot run, document why.

Stop and report if the task is ambiguous, blocked by open questions, or contradicts PRD/plan.
