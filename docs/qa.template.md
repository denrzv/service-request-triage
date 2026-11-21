# $TICKET: QA report
Status: DRAFT

## Scope
Describe what is covered by this QA report.

## Positive scenarios
-

## Negative scenarios
-

## Edge cases
- Timeout:
- Duplicate/retry:
- Invalid input:
- Permission/auth:
- Stale/concurrent data:

## Regression areas
-

## Automated checks
- Unit tests:
- Integration tests:
- Contract tests:
- Commands to run:

## Manual checks
-

## Test data
-

## Risks / open issues
Use `None` when there are no actionable defects. Blocking and Important defects
use this shape:

### QA-BLOCK-N or QA-IMPORTANT-N: Short title
- Severity: Blocking | Important
- Status: Unresolved
- Evidence:
- Required change:
- Acceptance:
- Likely target files:
- Validation:

## Recommendation
Set `QA_BLOCKED` while any Blocking or Important defect is unresolved. Set
`QA_PASSED` when none remain; Nice-to-have observations do not block.
