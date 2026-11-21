# FEATURE-003: Taxonomy and Policy
Status: PRD_READY

Issue: https://github.com/denrzv/service-request-triage/issues/3 (depends on FEATURE-001, merged).
Branch: `claude/clever-mccarthy-i2uiet` (session-assigned branch; one ticket per branch).

## Context / Problem
The triage pipeline needs a versioned, purely synthetic Service Request
taxonomy and a deterministic Java Policy Engine that turns a semantic
assessment (intent, domain, outcome, Noul signals, provider scores) into a
formal SR classification proposal and a route (ADR-003, ADR-004). FEATURE-002
delivered raw typed Jev answers only; there is no domain vocabulary, no
`SemanticAssessment`, no `ClassificationProposal` / `RoutingDecision`, and no
policy. FEATURE-005 (benchmark), FEATURE-006/007 (graph, confidence routing)
and FEATURE-008 (investigator tools) depend on these contracts.

## Goals
- Versioned English taxonomy (`taxonomy/v1.json`) defining `PrimaryIntent`,
  `ProblemDomain`, `RequestedOutcome`, `ServiceRequestType` and reason codes.
- Domain records that keep semantic assessment, formal classification
  proposal, and routing decision separate; formal decisions always carry the
  taxonomy and policy versions.
- `RouteAction` with `DRAFT_PROPOSAL`, `LLM_INVESTIGATION`, `HUMAN_REVIEW`,
  `ABSTAIN` as explicit policy outcomes.
- A pure, deterministic Java `ClassificationPolicyEngine` driven by a
  versioned, file-based rule table and thresholds.
- Policy and taxonomy definitions loaded from configurable resource locations,
  validated at startup, so a new version needs no source change.
- Table-driven tests for complaint, technical issue, mixed intent and none-fit.

## Non-goals
- Mapping Jev answers to `SemanticAssessment`, the taxonomy-aware
  `ClassificationModelPort`, and Jev question-set content (FEATURE-005/007).
- Calibrating thresholds (FEATURE-005/007 using the FEATURE-004 goldset). The
  v1 policy ships explicitly uncalibrated, no-op thresholds.
- Graph nodes/state (FEATURE-006), DeepSeek and investigator tools (FEATURE-008),
  task proposals / `ProposedTaskAction` and mock Task Management (FEATURE-010),
  REST/trace (FEATURE-012).
- Real banking rules, customer identifiers, SLA, legal deadlines.

## Users & Roles
- Downstream feature developers (FEATURE-005..010) consuming the contracts and engine.
- Maintainers who publish a new policy/taxonomy version through configuration.

## User Stories
- As the graph's final policy gate, I want one pure function that returns a
  versioned routing decision so that model output never decides routing.
- As an evaluator, I want thresholds in a versioned file so that calibrated
  values can be swapped in without code changes and compared across versions.
- As a reviewer, I want each decision to list rule IDs and rationale codes so
  that the route is auditable.

## Main Scenarios
1. Complaint about a payment: proposal `CLAIM`, route `HUMAN_REVIEW` because the
   synthetic policy requires approval for `CLAIM`.
2. Incident report about a digital channel: proposal `TECHNICAL_REQUEST`, route
   `DRAFT_PROPOSAL`.
3. Mixed intent (complaint about an app malfunction): proposal `CLAIM` with
   `TECHNICAL_REQUEST` as an alternative; when the multi-intent signal exceeds the
   policy limit, route `LLM_INVESTIGATION` once, then `HUMAN_REVIEW`.
4. None-fit: no rule matches or taxonomy fit below the policy minimum: proposal
   `UNCLASSIFIED`, route `ABSTAIN` (configurable to `HUMAN_REVIEW`).
5. Model unavailable / no assessment: proposal `UNCLASSIFIED`, route `HUMAN_REVIEW`.

## Edge Scenarios
1. Low Choice confidence or near-tie on a dimension the matched rule depends
   on: `LLM_INVESTIGATION` while the investigation budget remains, else `HUMAN_REVIEW`.
2. Policy referencing an unknown reason code, a reason code of another SR type,
   an `UNCLASSIFIED` rule result, duplicate rule IDs, thresholds outside [0, 1],
   or a taxonomy version different from the loaded taxonomy: startup fails with a clear message.
3. Taxonomy missing a definition for any enum constant, or defining an unknown
   one: startup fails.
4. Unknown requested version: typed not-found error, never a silent fallback.
5. Probabilities/confidence outside [0, 1] or a selected value missing from its
   probabilities: rejected when constructing the assessment.
6. Identical inputs always produce equal decisions (determinism).

## Acceptance Criteria
(Mirrors issue #3.)
- AC1: Versioned taxonomy defines `PrimaryIntent`, `ProblemDomain`,
  `RequestedOutcome`, `ServiceRequestType` and reason codes.
- AC2: Formal type decisions (`ClassificationProposal`, `RoutingDecision`) are
  separate from `SemanticAssessment` and require a taxonomy and policy version.
- AC3: `ABSTAIN`, `HUMAN_REVIEW`, `DRAFT_PROPOSAL` and `LLM_INVESTIGATION` are explicit policy outcomes.
- AC4: The pure Java Policy Engine has deterministic table-based tests for
  complaints, technical issue, mixed intent and none-fit.
- AC5: Policy configuration is versioned and can be changed without modifying
  source code (resource locations and active version are configuration).
- AC6: No invented real banking rules, customer identifiers or SLA semantics;
  definitions are marked synthetic.
- AC7: Public contracts meet `docs/contracts.md` or documented ADR-based changes.

## Non-functional requirements
- Security: no secrets; definitions contain no customer data; policy files are
  trusted configuration, customer text never reaches the engine.
- Privacy/data protection: English synthetic definitions only.
- Reliability: fail fast at startup on invalid definitions; engine has no I/O,
  no randomness, no clock; bounded investigation count enforced by policy.
- Performance/scale: in-memory evaluation, linear in rule count.
- Observability: decisions carry policy/taxonomy versions, rule IDs, rationale
  codes and a `calibrated` marker for traces (FEATURE-012).
- Compatibility: Java 25, Boot 3.5.x; no new dependencies; additive to FEATURE-002 types.

## Data considerations
- Identifiers: policy/taxonomy versions (`v1`), rule IDs, reason codes.
- Sensitive data: none.
- Retention/lifecycle: classpath JSON resources, immutable once published; a
  change means a new version file.

## Constraints & Assumptions
- Domain records follow `docs/contracts.md` section 1 with two typed refinements
  (enum-typed `ChoiceJudgment`, enum `RationaleCode`), recorded in the ticket ADR.
- "No-op thresholds" means v1 threshold values that never trigger
  (`0.0` minima, `1.0` maximum) plus `calibrated: false`; branch behaviour is
  tested with a test-only policy file.
- The Noul signal boundary (`signalMin`) converts a probability to a rule
  condition; it is an uncalibrated configuration value, not a confidence gate.
- The v1 rule table is an illustrative synthetic mapping, not bank policy.

## Risks
- Shared-contract drift with FEATURE-005/007 (mitigate: contracts.md update, ADR).
- Uncalibrated v1 auto-proposes drafts on categorical rules alone (mitigate:
  `calibrated: false` marker and `THRESHOLDS_UNCALIBRATED` rationale in every decision).
- Rule table mistaken for real policy (mitigate: synthetic disclaimers in files and docs).

## Open Questions
- None blocking.
