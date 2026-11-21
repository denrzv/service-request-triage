---
name: qa
description: "Create a QA report with positive, negative, edge, regression, manual, and automated verification scenarios for the active ticket."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Create or update the QA report for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read the existing QA report, current deterministic evidence, changed-file
   list/diff, PRD acceptance criteria, plan testing section, and review findings.
   Do not read the full tasklist or research artifact by default.
2. Create/update `reports/qa/<TICKET>.md` from `docs/qa.template.md`.
3. Include positive, negative, edge, regression, automated, and manual scenarios.
4. Give every Blocking and Important defect a stable ID (`QA-BLOCK-N` or
   `QA-IMPORTANT-N`) with actionable evidence, acceptance, files, and tests.
5. Set `QA_BLOCKED` while any Blocking or Important defect is unresolved;
   otherwise set `QA_PASSED`.

Do not modify application code.
