---
name: review
description: "Run one independent consolidated verification pass covering code review, QA, and every conditional gate required by the plan."
argument-hint: "<TICKET>"
arguments:
  - ticket
context: fork
agent: reviewer
---
Run the independent verification pass for ticket `$0`. If `$0` is empty, read
`docs/.active_ticket`.

Steps:
1. Require current deterministic evidence with
   `python3 scripts/aidd_verification.py --ticket $0 check`.
2. Inspect the changed-file list, relevant diff, PRD criteria, plan, existing
   reports, every ticket-owned or plan-linked ADR, and
   `reports/bugfix/$0.md` when present, once.
3. Verify every bugfix finding linked to a completed fix task. Mark it
   `Resolved` only when the converged diff and tests prove its acceptance. Set
   the report to `BUGFIX_RESOLVED` only when every finding resolves.
4. Produce review and QA reports.
5. In the same verifier context, produce every conditional report marked
   `required` by the plan.
6. Treat a missing or non-`ACCEPTED` governing ADR, or implementation that
   materially contradicts an accepted ADR, as Blocking. The reviewer must not
   accept or rewrite the ADR on the maker's behalf.
7. Return `VERIFICATION_PASS` only when all required reports pass, every
   existing bugfix report is `BUGFIX_RESOLVED`, and no Blocking or Important
   bugfix finding remains unresolved.

Do not fix issues in this pass unless the user explicitly asks for fixes.
