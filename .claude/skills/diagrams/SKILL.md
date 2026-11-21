---
name: diagrams
description: "Render PlantUML diagrams only when the active plan declares diagram impact or diagram source files changed. Use for C4 System Context, C4 Container, and ERD diagram validation."
argument-hint: "<TICKET> [--force]"
arguments:
  - ticket
  - mode
disable-model-invocation: true
allowed-tools: Bash(bash scripts/render-diagrams.sh *) Bash(python3 scripts/diagram-impact-check.py *)
---
Run diagram rendering for ticket `$0` with optional mode `$1`:

```bash
bash scripts/render-diagrams.sh "$0" "$1"
```

Policy:

- Render C4 System Context only when external actors/systems, system boundary, or cross-system integrations changed.
- Render C4 Container only when deployable components, services, databases, brokers, or component relationships changed.
- Render ERD only when persistent tables/entities, relationships, indexes, partitions, retention, or migration-relevant data model changed.
- For pure business logic, validation, mapping, controller-only, or test-only changes, skip rendering unless diagram source files changed.
- Use `--force` only when intentionally regenerating all diagram outputs.

Generated files under `diagrams/out/` are local artifacts and should not be committed unless the project explicitly requires generated diagrams in source control.
