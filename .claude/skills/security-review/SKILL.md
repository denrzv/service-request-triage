---
name: security-review
description: "Produce a lightweight security and privacy review for changes affecting auth, sessions, tokens, PII, public APIs, logging, secrets, dependencies, or threat boundaries."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Create or update the security review for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read the existing report, changed-file list/stat, relevant security diff,
   plan security section, and directly relevant PRD criteria.
2. Do not read the full tasklist or research artifact. On reruns, verify
   existing finding IDs first.
3. Create/update `reports/security/<TICKET>.md` from `docs/security-review.template.md`.
4. Cover threat model, auth/session/token handling, input validation,
   logging/privacy, secrets, dependencies, audit, and mitigation.
5. Give Blocking and Important findings stable IDs (`SEC-BLOCK-N` or
   `SEC-IMPORTANT-N`) and actionable evidence, acceptance, files, and tests.
6. Set `SECURITY_BLOCKED` while any Blocking or Important finding remains;
   otherwise set `SECURITY_PASS` or `SECURITY_PASS_WITH_NOTES`.
7. Keep the report and final response compact; do not paste code, diff, logs,
   secrets, or task narratives.

Do not modify application code.
