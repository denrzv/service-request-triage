---
name: idea
description: "Create or update the PRD and active ticket for a new feature or change. Use at the beginning of the AIDD workflow."
argument-hint: "<TICKET> \"Short title\" \"Context summary\""
arguments:
  - ticket
  - title
  - context
---
Create or update the PRD for ticket `$0` with title/context `$1`.

Additional context from the requester (may be empty):

> $2

This skill runs in the central session and therefore retains the user's
conversation context. Treat `$2` as an optional durable summary for future
resumption. If the conversation, repository, and `$1` are insufficient, keep
`Status: DRAFT` and list the missing information as open questions instead
of inventing scope.

Steps:
1. If `$0` is provided, write it to `docs/.active_ticket`; otherwise read `docs/.active_ticket`.
2. Run `python3 scripts/aidd_state.py --ticket <TICKET> init`. This is
   idempotent and is required for both `/feature-development` and the manual
   slash-command workflow.
3. Ensure `docs/prd/<TICKET>.prd.md` exists; use `docs/prd.template.md` if missing.
4. Fill or refine the PRD using `$1`, `$2`, and repository context.
5. Keep `Status: DRAFT` if open questions remain.
6. Set `Status: PRD_READY` only when scope and acceptance criteria are concrete enough for planning.

Output:
- Updated `docs/.active_ticket`.
- Updated `docs/prd/<TICKET>.prd.md`.
- Short summary of unresolved questions or confirmation that the PRD is ready.
