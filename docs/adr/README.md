# Architecture Decision Records (ADR)

**Status:** Proposed/accepted for the educational PoC unless marked provisional. Decisions are based on the November 2026 architecture discussion; implementation findings can supersede them in new ADRs. Preserve alternatives and trade-offs when changing a decision.

## ADR-001 — Conditional State Graph, not a Supervisor-led agent team
**Status:** Accepted for PoC

**Context:** SR triage has known stages (normalize → assess → gate → optional investigation → final gate → mock action). A fully autonomous supervisor would introduce unnecessary model calls and unpredictable business routes.

**Decision:** Spring AI Alibaba `StateGraph` with deterministic conditional edges and a bounded LLM branch. Each graph node has explicit inputs/outputs and safe failure transitions.

**Alternatives:** LangChain4j Supervisor, free-form multi-agent team, handwritten Java state machine, Temporal Workflow.

**Trade-offs:** Explicit state, testable branches and manageable latency; less autonomous exploration. A homegrown state machine is simpler initially but hides graph-runtime learning. Revisit if workflow scope changes.

## ADR-002 — Jev is the primary typed decision model; DeepSeek is optional fallback
**Status:** Accepted, provider compatibility smoke tests mandatory

**Context:** Classification into intents/domains/outcomes can be inexpensive and structured. Jev does not generate rationale or call tools; DeepSeek can investigate hard cases.

**Decision:** Call Jev through a `JevClassificationPort` implemented using Java HTTP client/RestClient, `POST https://api.typesafe.ai/v1/systemone` with Choice, Noul and optional Score. Call DeepSeek only for ambiguous cases and LLM-only evaluation baseline. Version model aliases in telemetry.

**Alternatives:** LLM for all traffic, prompt-only JSON classification, local fine-tuned text classifier, rules only.

**Trade-offs:** Lower potential per-case cost and explicit probabilities but vendor dependence, uncertain calibration and extra integration. Compare all routes objectively; do not assume Jev is more accurate than an LLM until tested.

## ADR-003 — Semantic understanding, formal SR type and routing are separate
**Status:** Accepted

**Context:** A technical malfunction described as a complaint does not unambiguously dictate whether formal SR type should be CLAIM or TECHNICAL_REQUEST. Risk of mixing user intent with enterprise registration policy.

**Decision:** Persist `SemanticAssessment`, `ClassificationProposal` and `RoutingDecision` separately. A deterministic, versioned Java Policy Engine maps model judgments and taxonomy definitions into allowable actions.

**Alternatives:** Single classifier directly predicts `serviceRequestType`, LLM decides routing and creates SR.

**Trade-offs:** More schema and rules work, but clear audit, testability and separation between probabilistic AI and domain invariants.

## ADR-004 — Confidence-aware abstention is a first-class output
**Status:** Accepted, initial thresholds provisional

**Context:** Jev Choice selects a category even if none fits. Confidence and class probabilities are not identical to verified correctness; high-confidence errors are possible.

**Decision:** Include Companion Noul (taxonomy fit), measure top-two separation, Choice confidence, risk/category-specific policies and out-of-taxonomy detection. Model can return `ABSTAIN` or `HUMAN_REVIEW`; no forced classification.

**Alternatives:** Always choose argmax; fixed global threshold; trust LLM 'confidence' explanations.

**Trade-offs:** Lower harmful auto-routing at cost of more escalations and human work. Thresholds are not fixed universal truths; tune on held-out English data.

## ADR-005 — Use Spring AI Alibaba Graph with a verified Spring generation
**Status:** Accepted — verified by the FEATURE-001 Maven spike (Boot 3.5.16, Spring AI 1.1.2, SAA 1.1.2.0; see `FEATURE-001-version-baseline.md` and README compatibility findings). Graph persistence APIs still to be validated in FEATURE-011.

**Context:** New Java 25 project should experiment with ready-made state graph rather than custom orchestration.

**Decision:** Baseline: Java 25; Spring Boot 3.5.x; Spring AI 1.1.2; Spring AI Alibaba 1.1.2.0 with BOM alignment. Confirm exact Boot patch and Graph API in the first issue. Use latest compatible patch only after reproducible build. Do not mix Spring AI 2.x / Boot 4.x until Alibaba compatibility is verified.

**Alternatives:** Spring Boot 4 + custom engine, LangGraph Python service, LangChain4j, Temporal.

**Trade-offs:** Supports direct Graph learning and Java stack, but has library compatibility/upgrade risk. Compiling and integration tests are acceptance gates.

## ADR-006 — Keep LLM access behind provider ports
**Status:** Accepted

**Context:** Jev TypeSafe API and DeepSeek generative API are different protocols with different failure/retry/cost semantics.

**Decision:** `ClassificationModelPort` / `InvestigationPort` or similarly named interfaces are application-owned. Jev uses HTTP; DeepSeek uses Spring AI `ChatClient` or `ChatModel` behind adapter. Keep prompts/questions separately versioned.

**Alternatives:** Put HTTP and model SDK calls in graph nodes; use LangChain4j for both.

**Trade-offs:** More interfaces but easy replacement, offline testing, baseline comparisons and separation of supplier-specific schemas.

## ADR-007 — In-memory checkpoint initially; PostgreSQL for durable HITL later
**Status:** Accepted with explicit reliability limitation

**Context:** We want to learn StateGraph transitions first, then check recovery across a human decision that may take minutes/hours.

**Decision:** Prototype with in-memory graph state; then add **supported PostgreSQL graph checkpointer** after verifying its released version/API. Test pause/resume and restart. Do not use Temporal simultaneously as a second orchestrator in v1.

**Alternatives:** Temporal as primary orchestrator, custom PostgreSQL task runner, always in-memory.

**Trade-offs:** Less infrastructure initially, clear graph learning path; checkpointing has different guarantees from Temporal Durable Execution. Any external side effects require idempotency and explicit replay tests. A future ADR can choose Temporal for long-lived real business workflows.

## ADR-008 — Mock Task Management only; no autonomous real-world side effects
**Status:** Accepted

**Context:** Incorrect classification in real banking can lead to misrouted customer complaints, legal deadlines, and operational harm.

**Decision:** `TaskManagementPort` creates **synthetic draft/proposal records** only. External effects require explicit policy and optional human approval. No real payment/refund/claim registration. Synthetic mock supports idempotent keys and audit.

**Alternatives:** Connect real bank 1839 / Task Management or real claims system from the start.

**Trade-offs:** Lower fidelity but safe, reproducible PoC. Production integration requires separate threat model, RBAC, data residency and business sign-off.

## ADR-009 — Benchmark before adding graph/LLM complexity
**Status:** Accepted

**Context:** A graph and LLM fallback can be strictly worse than a simple classifier.

**Decision:** Prepare independent synthetic English goldset and run baselines: deterministic rules, Jev-only, DeepSeek-only, and hybrid. Measure macro-F1, complaint recall, **wrong automated route** rate, abstention, escalation, latency, provider tokens/cost and recovery. Hold back test set from agent retrieval.

**Alternatives:** Demo-based qualitative judgments, inspect 5 cases and declare success.

**Trade-offs:** Additional dataset/labeling effort but evidence-based tuning and trustworthy conclusions.

## ADR-010 — English-only synthetic data and versioned policies
**Status:** Accepted

**Context:** Need fast development without Russian-language evaluation or bank-data governance issues.

**Decision:** All messages, category descriptions, prompts, coding guidelines and documentation in English. Taxonomy/policies are synthetic and versioned; no production bank/customer data or proprietary policy text transmitted to external models.

**Alternatives:** Multilingual or bank-specific data.

**Trade-offs:** Simplifies early experiments; results do not generalize to a bank's actual traffic, taxonomy, SLA or regulatory constraints.

## ADR-011 — Human review correction is auditable and must not mutate ground truth
**Status:** Accepted

**Context:** Training/evaluation labels and runtime human reviewer decisions serve different purposes.

**Decision:** Store graph branch/decision provenance and human corrections separately from hidden evaluation goldset. Treat manual decision as authoritative for the mock action, but not as an implicit model training update. Record taxonomy/policy versions and reviewer action.

**Alternatives:** Overwrite Jev confidence or evaluation ground truth after a user correction.

**Trade-offs:** More metadata but eliminates hidden label leakage and supports future model evaluation.

## ADR-012 — Parallel development uses branch/worktree ownership
**Status:** Accepted

**Context:** Claude Code and Codex will run in parallel against the same repository. Writing to one branch or shared worktree causes broken changes and accidental cross-ticket commits.

**Decision:** Assign each tool a distinct issue, Git branch and worktree; never both edit the same checkout/branch. Use GitHub PRs, required green checks and sequential merges for shared contracts. See [collaboration](../collaboration.md).

**Alternatives:** Both assistants operate directly on main; unrelated changes batched in one PR.

**Trade-offs:** More discipline and integration work; fewer collisions, traceable review and controlled architecture evolution.

## Open decisions / validation spikes

1. Exact released Graph checkpointer artifact and pause/resume semantics; **do not invent** checkpoint API names.
2. DeepSeek Spring AI 1.1.2 native adapter support for the chosen current model alias; verify tool calls and structured responses.
3. Jev alias/current model pinning: the API can resolve `jev-latest` to a different model over time.
4. Whether hierarchical Jev classification (beam search) improves over flat independent questions enough to justify extra calls.
5. Whether v1 can simulate a human review with a synchronous mock before implementing real graph interrupts.
6. Calibration on goldset: whether confidence alone, probability margin, Companion Noul or risk-weighted thresholds provide best selective accuracy.

## Reference documentation

- https://docs.typesafe.ai/api/system-one
- https://docs.typesafe.ai/patterns/companion-noul
- https://docs.typesafe.ai/cookbooks/hierarchical_classification
- https://java2ai.com/docs/versions/
- https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html
