# PROJECT_RULES.md — hard rules for Claude Code

This file contains non-negotiable rules. Put detailed engineering standards in `conventions.md`, the lifecycle in `workflow.md`, and factual architecture in `docs/architecture.md`.

## Mandatory
- Follow the artifact lifecycle in `workflow.md`.
- Do not modify application code before implementation gates pass.
- Keep discovery, planning, implementation, fixes, and documentation in the
  central session so product context is reused instead of copied.
- Use the independent `reviewer` subagent only after deterministic verification.
- Keep changes small: one task per implementation pass.
- Never invent domain facts; record assumptions and open questions.
- Add or update tests for behavior changes.
- Document timeout, retry, idempotency, observability, and rollback for risky changes.
- Keep the approved plan and tasklist aligned with implementation reality.
- Respect the binding project architecture rules in `CLAUDE.md`: conditional
  StateGraph orchestration, Jev as a typed decision API, DeepSeek as a bounded
  read-only investigator, and the pure Java Policy Engine as the only authority
  for formal SR classification and routing.
- `ABSTAIN` and `HUMAN_REVIEW` are first-class outcomes and must be tested.
- Use only synthetic English data; never place goldset ground-truth labels in
  any prompt, tool, or agent-accessible corpus.

## Forbidden
- Reading secrets, environment files, credentials, private keys, tokens, or generated secret dumps.
- Logging secrets, tokens, credentials, private keys, or sensitive personal data.
- Mixing unrelated refactoring into feature tasks.
- Changing contracts without compatibility analysis, docs, and tests.
- Introducing unbounded retries or silent failure handling.
- Treating generated research/planning text as fact without code references or explicit assumptions.
- Updating C4/ERD diagram sources unless the approved plan declares diagram impact.
- Running automatic dependency-fix commands such as `npm audit fix` without explicit user approval.
- Sending real customer/bank data, credentials, internal taxonomy, or source
  code to Jev or DeepSeek.
- Live Jev/DeepSeek calls in default test runs; live smoke tests are opt-in.

## Context and verifier discipline
- `/idea`, `/researcher`, `/plan`, `/tasks`, `/implement`, `/small-change`,
  `/bugfix`, `/fix-tasks`, and `/docs-update` run in the central session.
- `/review` is the only normal forked phase. Its `reviewer` is read-only for
  product/workflow files and may write only verification reports, including
  status/evidence updates in `reports/bugfix/`.
- The reviewer combines code review, QA, and all conditional gates declared
  `required` by the approved plan in one diff-first pass.
- Do not launch parallel or duplicate agents for a normal feature.
- After three implementation passes in one session, checkpoint durable state
  and resume the same feature command in a fresh context.

## ADR triggers
Create or update an ADR for:
- auth/session/security changes
- API/event/message contract changes
- DB migration/retention/data ownership decisions
- integration retry/idempotency/order guarantees
- major component boundary changes
- risky rollout or rollback strategy

Every ticket-owned and plan-linked ADR must be `ACCEPTED` before implementation.
Keep unresolved decisions `PROPOSED` and stop at `HUMAN_REQUIRED` when accepting
one requires a user or maintainer choice. A reviewer verifies ADR conformance
but cannot accept or rewrite an ADR for the maker.

## Commit & PR
- The AIDD lifecycle stops at `READY_FOR_USER_COMMIT`; the user commits and
  opens the PR.
- Run `/pre-commit` before committing a task.
- Run `/validate` before opening a PR.
- One FEATURE issue per branch/worktree and per PR, following
  `docs/collaboration.md` (PR checklist, test evidence, acceptance criteria).
- Prefer squash merge into one ticket-level commit on the main branch.
