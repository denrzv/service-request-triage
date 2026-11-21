---
name: validate
description: "Run final ticket validation: preparation gates, completed regular/fix tasks, and pass evidence for every required quality gate."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Validate the workflow state for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Run `python3 scripts/aidd_state.py --ticket $0 init`, then read the state
   with `python3 scripts/aidd_state.py --ticket $0 show`.
2. If the state is `HUMAN_REQUIRED`, `BLOCKED`, `CANCELLED`, or
   `SESSION_CHECKPOINT_READY`, stop without changing it. These states require
   the explicit lifecycle action they name.
3. Run `python3 scripts/aidd_verification.py --ticket $0 check`.
4. Only when deterministic evidence is a current PASS, transition an
   initialized or active manual-flow state to `VERIFYING`:

   ```text
   python3 scripts/aidd_state.py --ticket $0 transition \
     --status VERIFYING \
     --phase final-validation \
     --evidence .aidd/runtime/features/$0/verification/latest.json \
     --next-action "Run strict final validation"
   ```

   This reconciles controller bookkeeping with existing immutable artifacts;
   it does not waive or synthesize a gate.
5. Run `python3 scripts/validate-workflow.py --ticket $0 --final`. This must
   reject any ticket-owned or plan-linked ADR whose status is not `ACCEPTED`.
6. Only when strict final validation exits successfully, transition to:

   ```text
   python3 scripts/aidd_state.py --ticket $0 transition \
     --status READY_FOR_USER_COMMIT \
     --phase complete \
     --evidence .aidd/runtime/features/$0/verification/latest.json \
     --next-action "User inspects and commits the verified working tree"
   ```

7. Read the controller state, plan's `Quality gate impact` policy, structured
   deterministic evidence, and relevant quality reports.
8. Summarize required, passed, deliberately skipped, and missing gates;
   incomplete tasks; pre-commit status; and whether the working tree is ready
   for the user to inspect and commit.

Do not modify versioned files or verification reports. The only permitted
writes are controller state/events under `.aidd/runtime/features/$0/`, and
only through `scripts/aidd_state.py` as described above.
Do not stage, commit, push, merge, or create a PR.
