# $TICKET: Security and privacy review
Status: DRAFT

## Scope
Describe the changed surfaces and trust boundaries.

## Assets and data
- Sensitive data involved:
- Secrets involved:
- Data retention/lifecycle:

## Threat model
- Actors:
- Abuse cases:
- Trust boundaries:

## Authentication and authorization
-

## Input validation and output safety
-

## Logging, privacy, and audit
-

## Dependency / supply-chain risks
-

## Findings
### Blocking
Use stable headings `#### SEC-BLOCK-N: Short title` with Severity, Status,
Evidence, Required change, Acceptance, Likely target files, and Validation; or
write `None`.

### Important
Use stable headings `#### SEC-IMPORTANT-N: Short title` with the same fields; or
write `None`.

### Notes
-

## Recommendation
Use one of:
- `Status: SECURITY_PASS`
- `Status: SECURITY_PASS_WITH_NOTES`
- `Status: SECURITY_BLOCKED`

Any unresolved Blocking or Important finding requires `SECURITY_BLOCKED`.
