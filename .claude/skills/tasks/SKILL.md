---
name: tasks
description: "Decompose the approved plan into small actionable tasks with acceptance criteria and test impact."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Create or update the tasklist for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read PRD, research, plan, and every ADR linked from the plan. Stop if any
   ticket-owned or plan-linked ADR is missing or is not `ACCEPTED`.
2. Create/update `docs/tasklist/<TICKET>.md` from `docs/tasklist.template.md`.
3. Break work into small ordered tasks: `TASK-1`, `TASK-2`, etc., normally
   8-16 tasks for an epic.
4. Combine adjacent changes that share target files, validation, and rollback
   boundary; more than 20 tasks requires a rationale or a split epic.
5. For each task include risk, acceptance criteria, test impact, dependencies,
   and notes.
6. Set `Status: TASKLIST_READY` only when tasks are actionable.

Do not modify application code.
