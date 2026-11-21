---
name: reliability-review
description: "Review required reliability and operability changes and produce deterministic pass/block evidence."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Review reliability changes for ticket `$0` and write
`reports/review/$0-reliability.md`.

Read the existing report, changed-file list/stat, relevant runtime-risk diff,
plan reliability section, and directly relevant PRD criteria. Do not read the
full tasklist or research artifact. On reruns, verify existing finding IDs first.

Use `RELIABILITY_BLOCKED` while any Blocking or Important finding is unresolved;
otherwise use `RELIABILITY_PASS`. Keep the report and final response compact.
Do not modify application code.
