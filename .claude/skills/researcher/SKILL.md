---
name: researcher
description: "Research the active ticket before planning by inspecting relevant repository code, interfaces, data model, tests, constraints, and risks."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Research ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read the PRD for the ticket.
2. Inspect relevant source, tests, configuration, migrations, OpenAPI/event schemas, and existing docs.
3. Identify modules, entry points, outbound integrations, data model touch points, constraints, risks, and unknowns.
4. Save the result to `docs/research/<TICKET>.md` using `docs/research.template.md`.
5. Set `Status: RESEARCH_READY` when the findings are sufficient for planning; otherwise keep `Status: DRAFT`.

Do not modify application code.
