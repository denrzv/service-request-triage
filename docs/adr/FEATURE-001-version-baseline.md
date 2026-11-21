# ADR: FEATURE-001 — Pinned version baseline (Boot 3.5.16, Spring AI 1.1.2, SAA 1.1.2.0)
Status: ACCEPTED
Date: 2026-11-21

## Context
ADR-005 left the stack provisional until a Maven spike. Research (`docs/research/FEATURE-001.md`) found on Maven Central: Spring Boot 3.5.16 (latest 3.5.x), Spring AI 1.1.2 (latest 1.1.x is 1.1.8), `spring-ai-alibaba-bom` 1.1.2.0 whose `spring-ai-alibaba-graph-core:1.1.2.0` depends on Spring AI 1.1.2 (all 1.1.2.x SAA builds, including `1.1.2.4-security-fix`, keep Spring AI 1.1.2). Boot 4.x / Spring AI 2.x must not be mixed in. The issue baseline is SAA 1.1.2.0.

## Decision
- Parent: `spring-boot-starter-parent:3.5.16`; Java release 25.
- Import `spring-ai-bom:1.1.2` explicitly (so Spring AI stays at 1.1.2) and `spring-ai-alibaba-bom:1.1.2.0`.
- Graph core artifact: `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` (version from the BOM). The DeepSeek adapter `org.springframework.ai:spring-ai-deepseek:1.1.2` arrives transitively; the Spring AI DeepSeek starter is not added in this ticket.
- Plugin versions (compiler, surefire, failsafe, enforcer) are pinned explicitly; Mockito/ByteBuddy and ArchUnit versions are chosen for Java 25 and verified by running tests.
- Moving to a later SAA 1.1.2.x build (for example the security-fix build) is a follow-up recorded in the README, not part of this ticket, because only 1.1.2.0 matches the issue baseline and the content of the fix build is unverified.

## Alternatives considered
### Option A: SAA 1.1.2.4-security-fix / Spring AI 1.1.8
- Pros: newer fixes.
- Cons: deviates from the issue baseline; unverified API deltas; risk for later tickets.

### Option B: Boot 4.x + Spring AI 2.x
- Pros: newer.
- Cons: forbidden by ADR-005 until Alibaba compatibility is verified; SAA 2.0 is milestone only.

## Consequences
- Positive: reproducible, BOM-aligned build matching the issue baseline.
- Negative: later Spring AI patches are not picked up.
- Risks: Java 25 plugin/agent incompatibilities; transitive version conflicts.
- Mitigations: `dependency:tree` recorded; Maven Enforcer rules (dependency convergence where feasible, banned Boot 4); compatibility findings in README; follow-up issue for patch upgrade.

## Rollout / rollback
- Rollout: single PR creating the skeleton.
- Rollback: revert the PR; no runtime data affected.

## Links
- PRD: docs/prd/FEATURE-001.prd.md
- Plan: docs/plan/FEATURE-001.md
- Tasklist: docs/tasklist/FEATURE-001.md
