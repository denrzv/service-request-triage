---
name: docs-update
description: "Update project documentation, runbooks, diagrams, examples, and ADR links after a ticket implementation."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Update documentation for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read the existing docs report, changed-file list/diff, plan docs impact,
   directly relevant PRD criteria, and only the blocking verification findings
   that require documentation. Do not read the full tasklist or research
   artifact by default.
2. Update relevant docs, runbooks, README sections, API/message examples, and diagrams.
3. Keep changes concise and factual.
4. Do not invent behavior; use code and artifacts as sources.
5. Write `reports/docs/<TICKET>.md` with `Status: DOCS_COMPLETE`, changed paths,
   and validation. Use `DOCS_BLOCKED` with a stable `DOCS-BLOCK-N` finding when
   a code defect prevents truthful documentation.

Output a summary of updated documentation.
