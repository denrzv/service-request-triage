---
name: db-review
description: "Review required database, migration, index, retention, and query-plan changes and produce deterministic pass/block evidence."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Review database and migration changes for ticket `$0` and write
`reports/review/$0-db.md`.

Read the existing report, changed-file list/stat, relevant persistence diff,
plan data/migration section, and directly relevant PRD criteria. Do not read the
full tasklist or research artifact. On reruns, verify existing finding IDs first.

Use `DB_BLOCKED` while any Blocking or Important finding is unresolved;
otherwise use `DB_PASS`. Keep the report and final response compact. Do not
modify application code.
