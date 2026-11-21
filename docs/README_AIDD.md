# AIDD workflow pack for Claude Code

This repository uses a spec-driven AI-assisted development workflow. One
central Claude Code session owns the work, one independent verifier checks it,
and deterministic hooks plus versioned artifacts enforce the gates.

Current workflow protocol: `4.5.0` (`central-session-adr-gate`).

## Documentation responsibility map

| File | Responsibility |
|---|---|
| `CLAUDE.md` | Short entrypoint and routing instructions for Claude Code |
| `.claude/PROJECT_RULES.md` | Hard restrictions and non-negotiable rules |
| `workflow.md` | Lifecycle, gates, statuses, slash-skill sequence, diagram policy |
| `conventions.md` | Engineering standards: Java 25 / Spring Boot, providers, tests, observability |
| `docs/architecture.md`, `docs/contracts.md`, `docs/adr/README.md` | Current architecture, domain contracts, baseline ADRs (project-owned) |
| `docs/*.template.md` | Ticket artifact templates |
| `.claude/skills/*/SKILL.md` | Workflow commands/skills |
| `.claude/agents/reviewer.md` | The single independent verifier |

Keep these files separated. Avoid copying full conventions into `CLAUDE.md`, and avoid putting current component inventory into `conventions.md`.

## Install / refresh

```bash
chmod +x scripts/*.sh scripts/*.py hooks/*.py
python3 scripts/check-aidd-pack.py
```

If `.claude/agents/` or `.claude/skills/` did not exist when Claude Code started, restart Claude Code so the directories are loaded.

## Main flow

```text
/feature-development FEATURE-002 "Short title/context"
```

This runs the complete lifecycle in the current conversation: preparation,
every implementation task, deterministic whole-diff verification, one
independent consolidated verification pass, repair loops, and strict
validation. Pre-commit is rerun after code-affecting fixes but not after
docs/report-only changes. The lifecycle stops at
`READY_FOR_USER_COMMIT` without staging or committing files.

To keep subscription usage predictable, the coordinator checkpoints after
three implementation passes in one conversation. When it returns
`SESSION_CHECKPOINT_READY`, run `/clear` and repeat the same feature command.
It resumes from `.aidd/runtime/features/<TICKET>/state.json`, repository
artifacts, and the working tree; clearing the conversation does not reset
account usage limits.

Manual preparation sequence:

```text
/idea FEATURE-002 "Short title/context"
/researcher FEATURE-002
/plan FEATURE-002
# Resolve every ticket-owned/plan-linked ADR to Status: ACCEPTED
/tasks FEATURE-002
python3 scripts/validate-workflow.py --ticket FEATURE-002
```

`/idea` also initializes `.aidd/runtime/features/FEATURE-002/state.json`, so the
manual workflow does not require a separate controller command.

Bounded low-risk new change:

```text
/small-change FEATURE-0XX "One concrete change and expected outcome"
```

This creates a compact PRD, plan, and one-to-three task tasklist in one central
pass, then runs the same implementation, pre-commit, consolidated review, and
final validation gates. It is not available for API/event contracts, DB/data,
auth/security boundaries, new dependencies/integrations, ADR triggers,
destructive work, or cross-component changes.

Implementation loop:

```text
/implement FEATURE-002 TASK-1
/implement FEATURE-002 TASK-2
```

Confirmed bug inside an existing approved, uncommitted ticket:

```text
/bugfix FEATURE-002 "Symptom, reproduction, or confirmed diagnosis"
```

This records a durable bug finding and enters the existing fix-task loop
without rerunning `/idea`, `/researcher`, `/plan`, or `/tasks`. It still runs
pre-commit, one consolidated review, and final validation. Use a new ticket and
`/feature-development` when the change is new scope or the original work was
already committed/released.

Use `/implement-deep FEATURE-002 TASK-N` only for a task explicitly marked
`Risk: high` or for a documented failed Sonnet escalation. The project default
is Sonnet/medium; deep skills raise effort without automatically switching to a
more expensive model.

Quality convergence, when run manually:

```text
/pre-commit FEATURE-002
# if blocked: /fix-tasks FEATURE-002 pre-commit, implement fixes, rerun pre-commit
/review FEATURE-002
/fix-tasks FEATURE-002 verification
# implement appended TASK-FIX-N, rerun pre-commit, then restart at review
# /review includes QA and every conditional gate marked required by the plan
/validate FEATURE-002
```

`/feature-development` invokes `/pre-commit` automatically. The explicit
command above is needed only when running the lifecycle manually.

In the manual flow, `/validate` reconciles the local controller state to
`VERIFYING` only after fresh deterministic PASS evidence, runs strict final
validation, and transitions a clean result to `READY_FOR_USER_COMMIT`.
The Stop hook does not block the passive `NEW` state between manual commands;
it activates after the autonomous controller moves into `PREPARING` or another
active execution state.

## Gates

Application code/config writes are blocked until:

- PRD has `Status: PRD_READY`
- Plan has `Status: PLAN_APPROVED`
- Every ticket-owned and plan-linked ADR has `Status: ACCEPTED`
- Tasklist has `Status: TASKLIST_READY`

Run:

```bash
python3 scripts/validate-workflow.py --ticket FEATURE-002
python3 scripts/validate-workflow.py --ticket FEATURE-002 --final
```

## Central context and verifier

`/idea`, `/researcher`, `/plan`, `/tasks`, `/implement`, `/small-change`,
`/bugfix`, `/fix-tasks`, `/docs-update`, and `/validate` run centrally and
therefore retain the user's conversation context. `/review` is the sole normal
forked skill. Do not launch parallel or duplicate agents for an ordinary
feature.

Implementers read a single block through
`python3 scripts/aidd-task-context.py --ticket FEATURE-002 --task TASK-1`; they do
not load an ever-growing tasklist. New tasklists should normally contain 8-16
regular tasks, and implementation completion notes stay within 6-10 lines.

Automatic fix capacity is 12 for up to 20 regular tasks, then grows by one for
each four additional tasks, capped at 18 by default. A user/maintainer may
record a higher ticket-specific `Repair budget override`; agents cannot add or
change it. Check current usage with
`python3 scripts/aidd-task-context.py --ticket FEATURE-002 --repair-status`.
`/fix-tasks` refuses the whole new finding batch before writing if it would
exceed that limit.

The independent `/review` uses Sonnet/high, reads the current deterministic
evidence and changed-file diff without the full tasklist, and writes review,
QA, and plan-required specialist reports from one context pass.
`/review-deep` is a central, explicitly requested diagnostic aid; ordinary
Blocking/Important findings use fix tasks directly.

## Artifact locations

- PRD: `docs/prd/`
- Research: `docs/research/`
- Plan: `docs/plan/`
- Tasklist: `docs/tasklist/`
- ADR: `docs/adr/`
- Review: `reports/review/`
- QA: `reports/qa/`
- Security review: `reports/security/`
- Confirmed bug intake: `reports/bugfix/`
- Local controller state: `.aidd/runtime/features/<TICKET>/`
- Evolution proposals: `reports/evolution/`

## Diagram policy

The plan controls diagram work. Render diagrams only when `docs/plan/<TICKET>.md` says `Render diagrams required: yes` or when diagram source files changed.

Diagram impact rules:

- System Context C4: external actors/systems, system boundary, cross-system integrations.
- Container C4: deployable components, databases, brokers, storage, relationships.
- ERD: tables/entities, relationships, indexes, partitions, retention, migrations.

For pure business logic, validation, mapping, controller-only, test-only, or documentation-only changes, leave diagram impact as `not changed` and rendering will be skipped.

## Local checks

```bash
bash scripts/pre-commit-check.sh
bash scripts/security-check.sh
python3 scripts/diagram-impact-check.py --ticket FEATURE-002
bash scripts/render-diagrams.sh FEATURE-002
```

## Security notes

- Sensitive files are denied through `.claude/settings.json` `permissions.deny`;
  `hooks/read_guard.py` adds defense in depth for Read/Grep/Glob.
- Script writes use a fail-closed allowlist: `AGENT_EDITABLE_SCRIPT_PATHS` in
  `scripts/aidd_common.py` is empty, so every `scripts/*` path is
  human-maintained.
- Plans link ticket-owned ADR files (`docs/adr/<TICKET>-<slug>.md`). The
  baseline index `docs/adr/README.md` is referenced by ADR ID and excluded
  from the ADR status gate.
- Only synthetic English data; no real customer/bank data, credentials or
  goldset labels in prompts, tools, fixtures, or reports.
