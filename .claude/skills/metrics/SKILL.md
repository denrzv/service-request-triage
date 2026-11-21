---
name: metrics
description: "Show local AIDD phase timing, tool activity, failures, permissions, and workflow gate cache statistics."
argument-hint: "<TICKET>"
disable-model-invocation: true
allowed-tools: Bash
---
Show metrics for ticket `$0`. When `$0` is empty, use the active ticket.

Run:

```bash
python3 scripts/aidd-metrics-report.py --ticket "$0"
```

Report the command output without modifying application or workflow files.
