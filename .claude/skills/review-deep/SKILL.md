---
name: review-deep
description: "Escalation-only high-effort central review for one unresolved ambiguity or uncovered high-risk boundary."
argument-hint: "<TICKET> <REASON>"
arguments:
  - ticket
  - reason
disable-model-invocation: true
effort: high
---
Deep-review ticket `$0` in the central session only for this escalation reason:
`$1`. This is an explicit manual diagnostic, not part of the normal automatic
cycle.

Before investigating:
1. Read `reports/review/$0.md`.
2. Confirm the user requested this bounded deep diagnostic explicitly.
3. Read the changed-file list/stat and the minimum relevant diff.
4. Do not read the full tasklist or repeat settled standard-review evidence.

Update the existing review report with the resolved evidence and a matching
`REVIEW_APPROVED` or `REVIEW_BLOCKED` outcome. Do not fix code in this pass.
