# AIDD workflow & gates

This file defines the development lifecycle. Stack conventions belong in
`conventions.md`; factual system structure belongs in `docs/architecture.md`.
Tickets are the `FEATURE-00X` GitHub issues from `docs/implementation-plan.md`;
parallel Claude Code / Codex rules are in `docs/collaboration.md`.

## Operating model

The default workflow uses:

- one central maker session for discovery, planning, implementation, repairs,
  documentation, and final validation;
- persistent machine state under `.aidd/runtime/features/<TICKET>/`;
- deterministic verification bound to the exact working-tree fingerprint;
- one independent `reviewer` subagent for code review, QA, and every
  conditional gate required by the plan;
- versioned human-readable artifacts under `docs/` and `reports/`.

The standard lifecycle is:

```text
central preparation
  → accepted architecture decisions
  → central task implementation
  → deterministic verification
  → one independent consolidated verifier
  → central repairs if needed
  → final validation
  → READY_FOR_USER_COMMIT
```

Normal preparation and implementation never fork. This preserves the original
conversation, avoids repeating repository context, and limits a typical feature
to one verifier invocation per converged diff.

## Entry point and controller state

Start or resume with:

```text
/feature-development <TICKET> "Short title/context"
```

The controller records canonical state in:

```text
.aidd/runtime/features/<TICKET>/state.json
.aidd/runtime/features/<TICKET>/events.jsonl
.aidd/runtime/features/<TICKET>/verification/latest.json
```

These runtime files are local and ignored by Git. Human-readable PRD, research,
plan, tasklist, and review reports remain versioned.

Useful controller commands:

```bash
python3 scripts/aidd_state.py --ticket <TICKET> init
python3 scripts/aidd_state.py --ticket <TICKET> show
python3 scripts/aidd_verification.py --ticket <TICKET> check
```

The session checkpoints after three implementation passes. At
`SESSION_CHECKPOINT_READY`, run `/clear` and repeat the same feature command.
The new context resumes from controller state, artifacts, task checkboxes, and
the working tree instead of replaying prior agent narratives.

## Central preparation

### Compact small-change path

For one bounded, low-risk new outcome, run:

```text
/small-change <TICKET> "Concrete change and expected outcome"
```

This user-invoked central skill creates a compact PRD, plan, and one-to-three
task tasklist in one pass, then uses the normal implementation, deterministic
verification, independent review, repair, and final-validation phases. It does
not invoke separate discovery, research, planning, or task-decomposition
skills, and `/review` remains its only subagent.

The path excludes public/event contracts, DB/data, auth/security boundaries,
new dependencies/integrations, ADR triggers, destructive or production
actions, cross-component architecture, and scope larger than three tasks.
Operational scripts are eligible only with input validation, safe or preserved
defaults, no secret output, failure on unknown values, non-deploying tests,
and required reliability review. Operator-visible behavior also requires docs
review. If actual scope crosses these boundaries, transition to
`HUMAN_REQUIRED` and use `/feature-development`.

The following skills run in the current conversation:

```text
/idea <TICKET> "Short title/context" "Optional durable context"
/researcher <TICKET>
/plan <TICKET>
/tasks <TICKET>
```

`/idea` initializes the same controller state used by the autonomous entry
point. Therefore the manual slash-command sequence and
`/feature-development` can be mixed or resumed without a missing
`state.json`.

They produce:

| Artifact | Ready status |
|---|---|
| `docs/prd/<TICKET>.prd.md` | `PRD_READY` |
| `docs/research/<TICKET>.md` | `RESEARCH_READY` |
| `docs/plan/<TICKET>.md` | `PLAN_APPROVED` |
| `docs/tasklist/<TICKET>.md` | `TASKLIST_READY` |

Together, the ready PRD, approved plan, and ready tasklist are the controlled
feature contract. Every ADR owned by the ticket or linked from the plan is part
of that contract and must have `Status: ACCEPTED` before implementation. A
`PROPOSED` ADR is an unresolved decision, not an implementation-ready artifact.
If acceptance needs a user or maintainer choice, the controller stops at
`HUMAN_REQUIRED` instead of selecting on the user's behalf. A second generated
`contract.yaml` is deliberately not added:
it would duplicate the same acceptance criteria and create another drift
surface. Machine state may change statuses and evidence pointers, but it may
not rewrite that versioned contract to make a failed check pass.

Application code and runtime configuration cannot be edited before PRD, plan,
ADR decision, and tasklist gates pass. The gate includes both
`docs/adr/<TICKET>-*.md` and ADR paths linked from the plan's `## ADRs` section.
Those ADR files are included in the workflow-gate cache signature, so changing
an accepted decision invalidates the cached write permission. Research can be
made mandatory with `AIDD_REQUIRE_RESEARCH=true`.

## Central implementation

Implement one task at a time:

```text
/implement <TICKET> TASK-N
```

Use the compact task reader rather than loading a long tasklist:

```bash
python3 scripts/aidd-task-context.py --ticket <TICKET> --task <TASK-N>
python3 scripts/aidd-task-context.py --ticket <TICKET> --pending
```

`/implement-deep` is a central high-effort fallback, not another subagent. Use
it only for an explicitly high-risk task or after two evidence-backed attempts
leave the same non-environmental failure.

## Deterministic evidence

Before independent verification, run:

```text
/pre-commit <TICKET>
```

This executes `scripts/pre-commit-check.sh` and writes structured evidence.
It may be invoked automatically by `/feature-development`; direct user
invocation remains available for the manual workflow.
Evidence is valid only while its working-tree fingerprint matches the current
diff:

```bash
python3 scripts/aidd_verification.py --ticket <TICKET> check
```

A product, test, build, migration, contract, or runtime-config edit invalidates
the evidence. Documentation, report, and diagram-only paths are excluded from
the build fingerprint and may reuse it; rerun verification whenever a
verification-relevant path changes.

Missing Maven, Gradle, package-manager, or TypeScript tooling is a failed gate,
not a successful skip. Optional tools such as `gitleaks` and `shellcheck` remain
warnings unless CI makes them mandatory.

## Independent consolidated verifier

Run:

```text
/review <TICKET>
```

This is the only normal forked skill. The `reviewer`:

- reads the approved requirements, current deterministic evidence, and diff;
- writes review and QA reports;
- executes the contract, DB, reliability, security, and docs checklists only
  where the plan marks them `required`;
- reuses one evidence-gathering pass across those checks;
- reports `VERIFICATION_PASS` or `VERIFICATION_BLOCKED`;
- never changes product code, workflow configuration, or maintained docs.

Always-generated reports:

```text
reports/review/<TICKET>.md
reports/qa/<TICKET>.md
```

Conditional reports are written only for required gates. Finding IDs remain
stable across reruns.

## Repair loop

### Direct confirmed-bug intake

For a bug discovered manually before an approved ticket is committed, run:

```text
/bugfix <TICKET> "Symptom, reproduction, or confirmed diagnosis"
```

This skips discovery, research, planning, and regular task decomposition. It
records stable `BUG-BLOCK-N` / `BUG-IMPORTANT-N` findings under
`reports/bugfix/<TICKET>.md`, delegates task creation to
`/fix-tasks <TICKET> bugfix`, implements the resulting fix tasks centrally,
and then runs deterministic verification, one independent review, and final
validation. Intake stays `BUGFIX_OPEN` until the independent reviewer verifies
every finding and sets `BUGFIX_RESOLVED`. The normal repair budget and
escalation rules still apply.

`/bugfix` is valid only when the defect maps to the ticket's approved contract
and the ticket is not committed or released. New behavior, missing contracts,
contradictory requirements, destructive decisions, and fixes to released work
require a new ticket through `/feature-development`.

For Blocking or Important findings:

```text
/fix-tasks <TICKET> verification
```

The central session adds one deduplicated batch of `TASK-FIX-N` items, implements
them, reruns deterministic verification when the diff changes, and invokes the
reviewer again.

Automatic repair capacity is 12 through 20 regular tasks, then increases by one
per four tasks, capped at 18. Only a human may set
`Repair budget override: N`. The write hooks enforce this for both central
edits and subagents, and shell writes cannot bypass the check.

Stop at `HUMAN_REQUIRED` for contradictory requirements, missing authority,
destructive decisions, an exhausted repair budget, three blocked passes, or a
repeated identical failure after completed repairs.

## Conditional gate policy

Every new plan includes `Gate policy version: 1` and marks each gate
`required` or `not-required` with a rationale:

- contract compatibility;
- database migration safety;
- reliability and operability;
- security and privacy;
- maintained documentation.

Review and QA are always required and are part of the consolidated verifier.
The verifier blocks if the actual diff reveals that the plan incorrectly
skipped a conditional gate. `/gate-policy` updates that declaration centrally
before verification is rerun.

## Final gate

Run:

```text
/validate <TICKET>
```

For a manual lifecycle, `/validate` reconciles an initialized active state to
`VERIFYING` only after current deterministic PASS evidence exists. A successful
strict final validation then transitions it to `READY_FOR_USER_COMMIT`.
Terminal states that require human action are never auto-resumed.

Final validation requires:

- all regular and fix tasks complete;
- every ticket-owned and plan-linked ADR still `ACCEPTED`;
- current deterministic `PASS` evidence;
- review and QA pass;
- all plan-required conditional reports pass;
- controller state consistent with final verification.

The workflow then transitions to `READY_FOR_USER_COMMIT`. It never stages,
commits, pushes, merges, rebases, tags, or opens a PR. The user owns the final
inspection and commit.

## Safety and permissions

- Runtime `.env` files, credentials, secret directories, certificates, and
  private keys are denied to built-in read tools via `.claude/settings.json`
  `permissions.deny`; `hooks/read_guard.py` is a defense-in-depth layer.
  Jev/DeepSeek API keys come from environment variables only.
- Destructive shell operations and shell-based gate bypasses are blocked by
  `scripts/bash-command-guard.py`.
- Application code/config writes are gated by `scripts/gate-code-write.py`.
- The independent reviewer can write only verification reports.
- Workflow control files and hooks are human-maintained. Script writes are
  fail-closed: only exact paths in `AGENT_EDITABLE_SCRIPT_PATHS`
  (`scripts/aidd_common.py`, empty by default) may be changed by an agent
  after workflow gates pass.
- The Stop hook ignores passive `NEW` state used between manual slash commands.
  After an autonomous controller takes ownership by transitioning to
  `PREPARING` (or a later active state), the hook prevents it from silently
  declaring completion; after three repeated blocks it escalates to
  `HUMAN_REQUIRED`.
- The lifecycle never stages, commits, pushes, or opens a PR. After
  `READY_FOR_USER_COMMIT` the user commits and opens the FEATURE PR per
  `docs/collaboration.md`.

## Evolution

Controller transitions append structured events to `events.jsonl`. Run:

```text
/evolution-review
```

The skill aggregates feature histories, repeated failure fingerprints, attempts,
verification results, and escalation rates. It produces shadow recommendations
only. Workflow changes require human review, and promotion needs evidence from
at least six completed features; no proposal edits the live workflow
automatically.

## Validation commands

```bash
python3 scripts/check-aidd-pack.py
python3 scripts/validate-workflow.py --ticket <TICKET>
python3 scripts/validate-workflow.py --ticket <TICKET> --final
python3 scripts/aidd_verification.py --ticket <TICKET> check
python3 scripts/diagram-impact-check.py --ticket <TICKET>
bash scripts/pre-commit-check.sh
```
