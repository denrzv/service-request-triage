# Claude Code — Implementation Instructions

**Project:** Service Request Intelligent Triage — English-only synthetic data, Java 25, Spring Boot 3.5.x, Spring AI Alibaba Graph, Jev, optional DeepSeek.

## Read before any work

1. `README.md`
2. `docs/architecture.md`
3. `docs/adr/README.md`
4. `docs/contracts.md`
5. `docs/implementation-plan.md`
6. `docs/evaluation.md`
7. `docs/assumptions-and-risks.md`
8. `docs/collaboration.md`

## Mandatory architecture rules

- **Execution orchestration is a conditional StateGraph**, not a supervisor team or an independently invented graph engine.
- Jev is a **typed decision HTTP API** (`/v1/systemone`), not a generative model/agent; do not ask it to generate explanations.
- DeepSeek only investigates ambiguity via **bounded read-only tools** and returns advice. **Pure Java Policy Engine** makes formal SR classification/routing decisions.
- Separate semantic intent/domain/outcome from formal SR type, task proposal and graph execution state.
- `ABSTAIN` and `HUMAN_REVIEW` are first-class valid outcomes.
- Do not assume a particular confidence threshold is calibrated; derive from goldset.
- Graph in-memory v1 is **not durable**. Add PostgreSQL checkpoints and test pause/restart in later issue; do not add Temporal by default.
- Mock Task Management creates synthetic drafts only; no actual bank/customer data or real service integrations.
- Keep exact versions aligned with Alibaba BOM/Spring AI and compile with Java 25; **verify source-compatible API instead of guessing**.
- Protect Jev/DeepSeek API keys and outputs; source/customer text is untrusted input.
- Never give the agent access to evaluation ground-truth labels in any prompt/tool.
- All code, fixtures, prompt content, GitHub updates and documentation **in English**.

## Parallel development with Codex

- Work on **only your assigned FEATURE issue** in a **separate worktree and branch**.
- Do not edit another agent's branch/worktree or push to main.
- Communicate incompatible shared contract changes through PR/ADR first, then let other branches rebase.
- Include tests and a link to the acceptance criteria in every PR.
- Document precise build and live API smoke results; mark tests not executed rather than asserting success.

## Implementation approach

1. Read your exact GitHub issue and dependencies.
2. Check official Jev API schema and SAA compatibility docs.
3. Implement smallest vertical slice with offline tests and mocked cloud providers.
4. Test failure paths, cost budgets and rejection/abstention states.
5. Update docs/ADRs for deviations, then open PR.
6. Reuse existing ports; do not replace bounded deterministic policies with prompt-only instructions.

## Out of scope

Real banking data, refunds, claims registration, production routing, multi-tenant authentication, RAG over private sources, shell tools, autonomous code modification, any real customer account actions.
