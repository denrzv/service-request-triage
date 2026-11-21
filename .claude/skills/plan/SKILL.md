---
name: plan
description: "Create the implementation plan and ADRs for the active ticket after PRD and research are ready."
argument-hint: "<TICKET>"
arguments:
  - ticket
---
Create or update the technical implementation plan for ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Read PRD and research artifacts.
2. Create/update `docs/plan/<TICKET>.md` from `docs/plan.template.md`.
3. Cover architecture flow, contracts, data changes, error handling, retries, idempotency, security, observability, rollout, rollback, and tests.
4. Create ADRs from `docs/adr.template.md` for decisions with trade-offs. Keep
   `Status: PROPOSED` while a decision is still being evaluated.
5. Link every governing ADR in the plan's `## ADRs` section. Before approving
   the plan, require every ticket-owned and plan-linked ADR to have
   `Status: ACCEPTED`. `ACCEPTED` means the decision is resolved and authorized
   for implementation; it does not mean implementation is complete.
6. If accepting an ADR requires a product, architecture, risk, or maintainer
   choice that is not already resolved by the PRD and repository evidence,
   leave it `PROPOSED`, record the exact choice and options, transition an
   active controller to `HUMAN_REQUIRED`, and stop.
7. Set `Status: PLAN_APPROVED` only when the plan is implementable and its ADR
   decision gate is satisfied.

Do not modify application code.
