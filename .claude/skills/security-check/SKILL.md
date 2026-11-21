---
name: security-check
description: "Run local automated security checks such as OSV scanner, npm/pnpm audit, and optional project-specific secret checks."
disable-model-invocation: true
allowed-tools: Bash
---
Run:

```bash
bash scripts/security-check.sh
```

If it fails, summarize findings. Do not run automatic dependency fix commands unless the user explicitly approves.
