---
name: gate-policy
description: "Reconcile the plan's quality-gate decisions with the implemented diff and review evidence without changing implementation design."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
# Implemented-diff gate policy reconciliation

For ticket `$0`, read the current plan, current diff, task completion notes, and
latest review report. Update only `## Quality gate impact` and its rationales.

Rules:

1. `Review` and `QA` remain `required`.
2. Upgrade a conditional gate to `required` when the actual diff or reviewer
   evidence crosses its trigger.
3. Never downgrade a required gate during the autonomous lifecycle merely to
   avoid running it.
4. Do not change the implementation design, PRD, task scope, or application
   code.
5. Preserve `Gate policy version: 1` and a concrete rationale for every gate.
6. Run `python3 scripts/validate-workflow.py --ticket $0` after the edit.

Report the exact policy decisions that changed.
