# Assumptions, Security Constraints, Risks

## Assumptions

| ID | Assumption | Validation / design consequence |
|---|---|---|
| A-01 | **English-only** customer messages, prompts, labels and docs | No translation/multilingual evaluation in this PoC |
| A-02 | All source data and banking processes are synthetic | No confidential data sent to cloud providers |
| A-03 | Java 25, Spring Boot 3.5.x, Spring AI 1.1.2, Alibaba Graph 1.1.2.0 | Compile against exact released versions before framework-specific code |
| A-04 | Jev is a **cloud System One decision model**, not a generative agent | Use HTTP `/v1/systemone`, no Tool Calling expectations |
| A-05 | Jev `Choice`, `Noul`, `Score` provide typed answers as documented | Run live protocol smoke test; parse type-discriminated responses |
| A-06 | DeepSeek may be used for only ambiguous cases | Gate must be deterministic and default to safe fallback |
| A-07 | Threshold values are not known before calibration | Store in versioned policies, tune on labeled synthetic data |
| A-08 | Mock Task Management has no external side effects | Safe synthetic draft/task creation with idempotency keys |
| A-09 | In-memory graph does not survive JVM restarts | Durable Human Review added after Graph baseline |
| A-10 | Graph checkpointing may not guarantee exactly-once effects | Idempotent mocks and restart/replay integration tests |
| A-11 | No real SR, Case or Task state machine is owned by this PoC | Clear boundaries between execution graph and business domain |
| A-12 | Full provider billing available separately from ChatGPT/Dify | Live calls opt-in; token/cost metrics recorded |
| A-13 | Claude Code and Codex work in separate branches/worktrees | Merge shared contracts before downstream dependent branches |
| A-14 | Formal complaint categories are local synthetic examples | No inference about bank policy, legal requirements or SLA |

## Risks and mitigations

| Risk | Severity / effect | Mitigation |
|---|---|---|
| Misclassification of formal complaint | Harmful task route | Sensitive category review + risk-weighted evaluation |
| High-confidence wrong predictions | Silent misrouting | Held-out calibration, Companion Noul, error-cost thresholds |
| Out-of-taxonomy forcing | Incorrect SR type | Abstain/human review rather than argmax |
| Jev/DeepSeek provider drift | Changed output/accuracy | Record resolved model, prompt/question version, periodic rerun |
| Dependency incompatibility | Build/runtime failure | Version compatibility spike in first issue; BOMs; CI |
| LLM fabricated evidence | False recommendation | Require policy/taxonomy citations, Java validation |
| Prompt injection in customer messages | Agent jailbreak / tool misuse | Read-only bounded tools; model cannot alter routing rights |
| Secret/PII exposure | Privacy/compliance breach | Synthetic only, env secrets, redact logs; no real bank data |
| Graph loop or repeated escalation | Excessive cost/latency | One fallback max + execution budgets/timeouts |
| Checkpoint replay with mock side effects | Duplicate SR/task drafts | Stable triageId + idempotency key, integration recovery tests |
| Dataset contamination | Inflated benchmark scores | Hidden goldset; tools only retrieve non-labeled synthetic examples |
| Parallel AI assistants changing same files | Conflicting/invalid implementation | Distinct Git worktrees/branches and PR review gates |
| High API costs despite inexpensive Jev | Unexpected usage | Tracing, response sizes, token usage, budget guardrails |
| Business-domain leakage | Fake policy mistaken for actual workflow | Prominent synthetic-only disclaimer in docs/API/UI |

## Required privacy statement

Do not integrate any real bank repository, SR payload, account identifiers, complainant data, internal service catalog, routing rules or actual customer messages with TypeSafe, DeepSeek or other cloud providers without enterprise authorization and a data-protection review. This repository is public and must contain no real secrets or sensitive data.

## Open questions

1. Will Jev's results remain sufficiently calibrated across different styles of English financial-support complaints?
2. Is the TypeSafe `jev-latest` alias stable enough for reproductions, or should the model version be pinned by experiment?
3. Which graph checkpoint implementation is compatible with the selected Spring AI Alibaba artifact?
4. Does the selected Spring AI version's DeepSeek adapter handle structured output and tool calls as required?
5. Do classification thresholds hold under intentionally ambiguous and rare classes?
6. Is graph checkpointing sufficient for future long-running employee-review tasks, or should a durable workflow engine be evaluated then?
7. Is an LLM investigator actually better than directly routing unclear cases to people?
