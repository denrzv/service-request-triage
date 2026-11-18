# Parallel Implementation Workflow — Claude Code + Codex

**Goal:** two coding assistants can contribute in parallel **without sharing a mutable checkout, branch or half-complete API**.

## Hard rules

1. Each assistant has **one assigned FEATURE issue** per branch, e.g. `feature/FEATURE-002-jev-client` versus `feature/FEATURE-003-taxonomy-policy`.
2. Run assistants in **separate Git worktrees** / separate local directory clones. Never both edit `main` or the same worktree.
3. Dependency ordering is mandatory. Read linked issues and merge shared contracts before dependent branches begin. Avoid starting FEATURE-009 before 006–008 are merged.
4. Both tools read `README.md`, `docs/architecture.md`, `docs/adr/README.md`, `docs/contracts.md`, `docs/implementation-plan.md`, and their own instructions (`CLAUDE.md` or `AGENTS.md`).
5. Every change is a PR referencing its `FEATURE-00X` issue, with test evidence, assumptions, and architecture impact.
6. A change to public domain records, graph state schema, taxonomy, policy, dependency BOM or API must be called out and merged **before parallel downstream work rebases**.
7. Never ask an assistant to re-implement somebody else's still-open issue. Stop and clarify ownership at the GitHub issue level.

## Suggested parallel batches

| Batch | Claude Code | Codex | Merge gate |
|---|---|---|---|
| 0 | FEATURE-001 Project Bootstrap | Review docs, explore dependencies (no code PR) | Bootstrap merged |
| 1 | FEATURE-002 Jev API Integration | FEATURE-003 Taxonomy & Policy | Shared contracts merged |
| 2 | FEATURE-004 English Dataset | FEATURE-005 Jev Benchmark scaffolding **only after 002–004 contracts stable** | Frozen goldset |
| 3 | FEATURE-006 Graph Foundation | FEATURE-008 DeepSeek Investigator | Stable graph state + agent port |
| 4 | FEATURE-007 Confidence Gate | FEATURE-010 Mock Task Management (after contracts stable) | Graph integration |
| 5 | FEATURE-009 Hybrid Routing | FEATURE-011 HITL/checkpoint exploration | Integration tests |
| 6 | FEATURE-012 API & Trace | FEATURE-013 Comparative Evaluation | E2E benchmark |
| 7 | FEATURE-014 Hardening | Independent code review & ADR check | Final acceptance |

This is a **suggestion**; actual execution must honor each issue's dependency list.

## Sample worktree commands

```bash
git fetch origin
git worktree add ../triage-jev -b feature/FEATURE-002-jev-client origin/main
git worktree add ../triage-taxonomy -b feature/FEATURE-003-taxonomy-policy origin/main
```

Launch Claude Code in `../triage-jev` and Codex in `../triage-taxonomy`. Do not share an untracked Maven output directory; keep build artifacts per worktree.

## PR checklist

- [ ] Links one FEATURE issue and its acceptance criteria.
- [ ] Small diff scoped to assigned files.
- [ ] Validated Java 25, pinned BOMs, unit and integration tests.
- [ ] Synthetic-only fixtures; no credentials or proprietary content.
- [ ] Exceptions, retry, abstention and partial outcomes handled.
- [ ] Does not bypass Policy Engine or enable unauthorized mock actions.
- [ ] ADR updated for intentional architectural divergence.
- [ ] Replay/side-effect idempotency tests if graph persistence changes.
- [ ] Follows main branch after upstream changes merged.

## Example prompts

**Claude Code:**

> Implement FEATURE-002 only. Read CLAUDE.md and linked design docs. Work solely in branch feature/FEATURE-002-jev-client. Verify TypeSafe API schemas against official docs, write mocked unit tests and opt-in live smoke. Do not add Graph or DeepSeek features. Report build and test outcomes and open a PR.

**Codex:**

> Implement FEATURE-003 only. Read AGENTS.md and design docs. Work solely in branch feature/FEATURE-003-taxonomy-policy. Build synthetic versioned taxonomy and pure Java policy contracts, with tests. Do not change the Jev HTTP adapter. Highlight shared interface changes in the PR.

## Coordination mechanism

GitHub Issues are the backlog/control plane; Git branches represent isolation; PRs and CI enforce integration. Maintain a short `docs/decisions-log.md` only if substantive decisions arise beyond existing ADRs. Human reviews remain the final merge approval.

Never assume the two agents have synchronized contexts or a shared in-memory understanding. Shared durable artifacts in Git are the source of truth.
