---
name: contract-review
description: "Review required API, schema, message, and compatibility changes and produce deterministic pass/block evidence."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Review contract changes for ticket `$0` and write
`reports/review/$0-contracts.md`.

Read the existing report, changed-file list/stat, relevant contract diff, plan
contract section, and directly relevant PRD criteria. Do not read the full
tasklist or research artifact. On reruns, verify existing finding IDs first.

Use `CONTRACT_BLOCKED` while any Blocking or Important finding is unresolved;
otherwise use `CONTRACT_PASS`. Keep the report and final response compact. Do
not modify application code.
