# $TICKET: Tasklist
Status: DRAFT

References:
- PRD: `docs/prd/$TICKET.prd.md`
- Research: `docs/research/$TICKET.md`
- Plan: `docs/plan/$TICKET.md`

## Tasks
- [ ] TASK-1: Short task title
  - Goal:
  - Size: S
  - Risk: standard
  - Evidence: E-01, E-02
  - Requirements:
    - PRD: AC1
    - Plan: `Relevant section`
  - Target files:
    - `path/to/production-file`
  - Reference files:
    - `path/to/similar-implementation`, only when the pattern must be read; otherwise `Not applicable`
  - Test files:
    - `path/to/test-file`
  - Expected changes:
    - `Symbol`: concrete change
  - Acceptance:
    - AC1:
  - Validation:
    - Fast: `exact narrow command`
    - Broader (only if needed): `exact broader command`, or `Not applicable`
  - Dependencies:
  - Out of scope:
  - Notes:

## Diagram tasks
- None. Add diagram tasks only when `docs/plan/$TICKET.md` has `Render diagrams required: yes`.

## Review / QA fixes
- None yet. `/fix-tasks` appends implementation-ready `TASK-FIX-N` entries here
  for unresolved Blocking or Important findings.

<!-- Appended fix-task shape:
- [ ] TASK-FIX-N: Short fix title
  - Source: BUG-BLOCK-* | BUG-IMPORTANT-* | PRECOMMIT-BLOCK-1 | REV-BLOCK-1 | REV-IMPORTANT-1 | QA-BLOCK-1 | QA-IMPORTANT-1 | SEC-* | CONTRACT-* | DB-* | REL-*
  - Goal:
  - Size: S
  - Risk: standard
  - Requirements:
    - Finding: exact source finding ID
    - PRD/Plan: applicable reference
  - Target files:
  - Reference files:
  - Test files:
  - Expected changes:
  - Acceptance:
  - Validation:
    - Fast:
    - Broader (only if needed):
  - Dependencies:
  - Out of scope:
  - Notes:
-->

## Completion notes
-
