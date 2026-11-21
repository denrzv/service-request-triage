# FEATURE-001: Docs review
Status: DOCS_COMPLETE

## Findings

### DOC-001
- Severity: Important
- Status: Resolved
- Evidence: PRD AC5 requires a structured logging baseline "with correlation placeholders"; the plan (Observability) requires MDC keys `triageId`, `graphRunId` to be documented. Originally absent from README and ADRs.
- Required change: Document the reserved MDC keys and where they appear in JSON logs.
- Acceptance: README states the reserved MDC keys and where they appear in the JSON log output. Met: README.md line 67 states logstash JSON emits MDC entries as top-level fields and that `triageId` and `graphRunId` are reserved correlation keys populated by later tickets; docs/adr/FEATURE-001-keyless-startup-and-config.md line 14 repeats it.
- Likely target files: README.md, docs/adr/FEATURE-001-keyless-startup-and-config.md
- Validation: grep for `triageId` in README.md and the ADR (done); `python3 scripts/aidd_verification.py --ticket FEATURE-001 check` PASS, fingerprint e7b49a4f485b9d6b4832c54645b228ae857dca05b3bd2fd3fd828346d91a06d6 (current after the fix).

## Verified accurate
README build/run commands, health URLs, env vars, JDK 25 requirement, compatibility table (matches pom and dependency tree), ADR-005 status update in docs/adr/README.md, MDC correlation keys.
Not executed: GitHub Actions workflow (README states this).
