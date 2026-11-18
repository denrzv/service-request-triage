# Codex Engineering Instructions

Before coding, read `CLAUDE.md`, `README.md` and documents in `docs/`. These are binding architecture constraints for the educational PoC.

- English-only synthetic customer requests and domain rules. Do not access/transmit actual bank data.
- Implement exactly **one FEATURE-00X issue** per branch; use a separate Git worktree from Claude Code.
- Jev uses HTTP `POST /v1/systemone` and returns structured Choice/Noul/Score answers; no generative Tool Calling via Jev.
- Spring AI Alibaba **StateGraph** governs execution/conditional edges; Java Policy Engine governs formal SR classification/routing.
- DeepSeek is conditional investigator only; read-only bounded Tools; never writes Task Management.
- `HUMAN_REVIEW`, `ABSTAIN`, `PARTIAL`, failure paths must be deliberate, tested states.
- Keep graph checkpoint/human approval separate from domain SR lifecycle. Initial in-memory has no restart durability.
- Provider adapters behind application ports; unit test with fakes without cloud keys; live tests opt-in.
- Spring AI Alibaba Graph 1.1.2.0 + Spring AI 1.1.2 + Boot 3.5.x is the proposed baseline; verify exact compatible artifacts and Java 25 build.
- Run tests, report exact results; do not claim compiles from pseudocode.
- Document ADR changes and schema changes before downstream issues proceed.
- Never commit tokens, real client information or goldset answers in accessible agent tool corpus.

Review [Collaboration](docs/collaboration.md), [Roadmap](docs/implementation-plan.md) and [Contracts](docs/contracts.md) before creating a PR.
