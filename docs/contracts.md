# Contracts and Illustrative Payloads

**Status:** Architecture draft; code fragments are **design contracts**, not complete runnable implementations. Actual framework imports and method signatures must be verified after dependency resolution.

## 1. Domain concepts

```java
record IncomingCustomerRequest(
    String triageId,
    String message,             // English synthetic text only
    Channel channel,
    String syntheticCustomerId
) {}

enum Channel { INTERNET_BANK, CONTACT_CENTER, WEB_FORM }
enum PrimaryIntent { COMPLAINT, INQUIRY, ACTION_REQUEST, INCIDENT_REPORT }
enum ProblemDomain { PAYMENTS, ACCOUNTS, DIGITAL_CHANNELS, SUPPORT_SERVICE, OTHER }
enum RequestedOutcome { REFUND, FIX_PROBLEM, INVESTIGATE, EXPLAIN, PERFORM_ACTION, NOT_SPECIFIED }
enum ServiceRequestType { CLAIM, TECHNICAL_REQUEST, INFORMATION_REQUEST, SERVICE_REQUEST, UNCLASSIFIED }
enum RouteAction { DRAFT_PROPOSAL, LLM_INVESTIGATION, HUMAN_REVIEW, ABSTAIN }
enum TriageStatus { RECEIVED, CLASSIFIED, INVESTIGATING, AWAITING_HUMAN, COMPLETED, PARTIAL, FAILED }
```

```java
record ChoiceJudgment(
    String selected,
    double confidence,
    Map<String, Double> probabilities
) {}

record SemanticAssessment(
    ChoiceJudgment primaryIntent,
    ChoiceJudgment problemDomain,
    ChoiceJudgment requestedOutcome,
    double refundRequestedNoul,
    double technicalProblemNoul,
    double multiIntentNoul,
    double taxonomyFitNoul,
    String modelUsed,
    String questionSetVersion
) {}

record ClassificationProposal(
    String taxonomyVersion,
    ServiceRequestType proposedType,
    String reasonCode,
    List<ServiceRequestType> alternatives,
    List<String> supportingSignals
) {}

record RoutingDecision(
    RouteAction action,
    ClassificationProposal proposal,
    List<String> ruleIds,
    List<String> rationaleCodes,
    String policyVersion,
    boolean humanApprovalRequired
) {}

record ProposedTaskAction(
    String taskType,
    String assignedGroup,    // synthetic role, not real bank identifier
    String description,
    boolean requiresHumanApproval
) {}

record TriageResult(
    String triageId,
    TriageStatus status,
    SemanticAssessment semanticAssessment,
    RoutingDecision routingDecision,
    List<ProposedTaskAction> taskProposals,
    List<String> unresolvedQuestions,
    List<DecisionTraceEvent> trace
) {}
```

**Implemented in FEATURE-003 (ADR `FEATURE-003-taxonomy-policy-engine`):** the enums above and the decision records live in `domain.model` with two typed refinements. `ChoiceJudgment<E extends Enum<E>>` has an enum-typed `selected` and enum-keyed `probabilities`, so invalid categories are rejected when a `SemanticAssessment` is built. `RoutingDecision.rationaleCodes` is a `List<RationaleCode>` (closed enum emitted by the engine). `RoutingDecision` also has `policyCalibrated`. `humanApprovalRequired` is true exactly for `HUMAN_REVIEW`, `ABSTAIN` always proposes `UNCLASSIFIED`, and `DRAFT_PROPOSAL` / `LLM_INVESTIGATION` never do. `ClassificationProposal` requires `taxonomyVersion`; `RoutingDecision` requires `policyVersion`. `Taxonomy`, `ReasonCode`, `ClassificationPolicy`, `PolicyThresholds`, `ClassificationRule` and `RuleCondition` model the versioned definitions. `ProposedTaskAction` and `TriageResult` are left for FEATURE-010/012.

**Implemented in FEATURE-006:** `IncomingCustomerRequest` (unvalidated carrier; the graph's `ValidateInput` applies the bounds, `toString` omits the message), `TriageStatus` (values as above; v1 uses `CLASSIFIED`, `AWAITING_HUMAN`, `COMPLETED`, `PARTIAL`, `FAILED`, with `RECEIVED` as initial state and `INVESTIGATING` reserved) and `DecisionTraceEvent(int sequence, String node, String routeLabel, String nextNode)` in `domain.model`.

**Version and uncertainty invariants:** validate enum mappings, model outputs and probability ranges; do not treat model `confidence` as truth. `UNCLASSIFIED`, `ABSTAIN` and `HUMAN_REVIEW` are normal safe outcomes.

## 2. Ports (inside the application boundary)

```java
interface ClassificationModelPort {
    SemanticAssessment classify(IncomingCustomerRequest request, Taxonomy taxonomy);
}

interface InvestigationPort {
    InvestigationAssessment investigate(
        IncomingCustomerRequest request,
        SemanticAssessment initial,
        Taxonomy taxonomy,
        ClassificationPolicy policy
    );
}

interface TaxonomyPort { Taxonomy get(String version); }
interface ClassificationPolicyPort { ClassificationPolicy get(String version); }
interface TaskManagementPort {
    MockDraftReference createDraft(IdempotentDraftCommand command);
    MockReviewTaskReference requestManualReview(IdempotentReviewCommand command);
}
interface DecisionTracePort { void append(DecisionTraceEvent event); }
```

Implement `ClassificationPolicyEngine.decide(assessment, taxonomy, version)` as a **pure Java function** (deterministic, no LLM invocation). Model adapters never directly write Task Management.

**Implemented in FEATURE-003:** `TaxonomyPort.get(version)` and `ClassificationPolicyPort.get(version)` / `active()` (in-memory, loaded from versioned JSON at startup; an unknown version raises `DefinitionNotFoundException`). The engine is `ClassificationPolicyEngine.decide(assessment, taxonomy, policy, investigationAttempts)` plus `modelUnavailable(taxonomy, policy)`. The extra `investigationAttempts` argument lets the pure function enforce the bounded LLM fallback. `ClassificationModelPort`, `InvestigationPort`, `TaskManagementPort` and `DecisionTracePort` are left for later tickets.

## 3. Jev HTTP request

Official TypeSafe API: `POST https://api.typesafe.ai/v1/systemone`, Bearer API key. The `model` may be an alias (`jev-latest`); record the resolved model returned in response. Send multiple `questions` in one call where possible.

```json
{
  "model": "jev-latest",
  "state": {
    "customerMessage": "The app rejected my login and support did not help. I want to file a complaint.",
    "channel": "INTERNET_BANK"
  },
  "questions": {
    "primary_intent": {
      "type": "choice",
      "instructions": "What is the customer's primary intent?",
      "criteria": {
        "complaint": "Express dissatisfaction or a formal grievance",
        "inquiry": "Ask for clarification or information",
        "action_request": "Ask for a service action",
        "incident_report": "Report malfunction without clearly expressing a formal complaint"
      }
    },
    "problem_domain": {
      "type": "choice",
      "instructions": "Which domain best matches the central problem?",
      "criteria": {
        "payments": "Charges, transfers, payments or billing",
        "accounts": "Account access, account details or status",
        "digital_channels": "App, login, website or online bank malfunction",
        "support_service": "Staff or support quality",
        "other": "Anything not in these domains"
      }
    },
    "refund_requested": {
      "type": "noul",
      "instructions": "Does the customer explicitly request a monetary refund?"
    },
    "taxonomy_fit": {
      "type": "noul",
      "instructions": "Does at least one offered primary-intent category accurately describe the request?"
    },
    "customer_urgency": {
      "type": "score",
      "instructions": "How time-sensitive does the customer state the problem is?",
      "criteria": [
        "No explicit urgency",
        "Requests timely attention",
        "Explicitly states urgent harm or deadline"
      ]
    }
  }
}
```

This is a **schema illustration**, not a live Jev response. Provider supports `Choice`, `Noul` and `Score`; check JSON precisely and add golden contract fixture based on a tested response.

Response parsing must handle:

- `answers.primary_intent.choice`, `.confidence`, `.probabilities`;
- `answers.refund_requested.noul` (no `confidence` field for Noul);
- `answers.customer_urgency.score`, `.confidence`, `.probabilities`, `.legend`;
- `usage.input_tokens`, `usage.output_tokens`; `model`.

Do not assume all questions have the same response type. An absent or malformed answer is a provider error / human-review path, **not default 0 confidence**.

**Implemented in FEATURE-002 (verified against the TypeSafe OpenAPI 0.2.0 schema):** the lower-level `JevClassificationPort.classify(JevClassificationRequest) -> JevClassificationResult` sits beneath the taxonomy-aware `ClassificationModelPort` above, which FEATURE-003/007 layer on top. The Score `criteria` is an **array** (1 to 10 levels), Choice `criteria` is an object (1 to 255 entries), and Noul has no `confidence`. Score probabilities and `legend` are keyed `"0"`, `"1"`, and so on. Only 200, 400 and 422 responses are documented; other statuses are handled by class (see README, "Jev adapter"). Failures surface as `JevClientException`, `JevUnavailableException` or `JevProtocolException`, which a later graph node maps to `HUMAN_REVIEW` / `MODEL_UNAVAILABLE`.

### 3a. Triage question set `triage-jev-v1` (FEATURE-005)

`application.assessment.TriageQuestionSets.v1()` is the versioned runtime question set sent in **one** Jev call with
state `{customerMessage, channel}`:

| Question | Type | Maps to |
|---|---|---|
| `primary_intent` | Choice; criteria keys `complaint`, `inquiry`, `action_request`, `incident_report` | `SemanticAssessment.primaryIntent` |
| `problem_domain` | Choice; `payments`, `accounts`, `digital_channels`, `support_service`, `other` | `problemDomain` |
| `requested_outcome` | Choice; `refund`, `fix_problem`, `investigate`, `explain`, `perform_action`, `not_specified` | `requestedOutcome` |
| `refund_requested` | Noul | `refundRequestedNoul` |
| `technical_problem` | Noul | `technicalProblemNoul` |
| `multi_intent` | Noul | `multiIntentNoul` |
| `taxonomy_fit` | Noul (Companion Noul: does any offered category fit?) | `taxonomyFitNoul` |

Choice criteria keys are the lower-case taxonomy enum names. `JevAssessmentMapper.toAssessment(result)` is strict: a
different question-set version, a missing or mistyped answer, or choice probabilities whose keys are not exactly the
enum constants raise `JevProtocolException` (safe `MODEL_UNAVAILABLE` path). `modelUsed` is the resolved model and
`questionSetVersion` is echoed. Any change to question names, instructions or criteria requires a new version.

## 4. Policy gate (examples only)

```text
if provider_unavailable or invalid_judgment:
    -> HUMAN_REVIEW (reason=MODEL_UNAVAILABLE)
if taxonomy_fit_noul below calibrated threshold:
    -> ABSTAIN or HUMAN_REVIEW
if multi_intent_noul high and formal type uncertain:
    -> LLM_INVESTIGATION or HUMAN_REVIEW
if high consequence CLAIM and risk policy requires approval:
    -> HUMAN_REVIEW (even if Jev confident)
if enough calibrated evidence for safe draft:
    -> DRAFT_PROPOSAL
otherwise:
    -> LLM_INVESTIGATION (at most once), then HUMAN_REVIEW
```

All thresholds must live in **versioned** policy files and be **calibrated** with evaluation data. Do not hard-code `0.8` or `0.9` in runtime code as universal guarantees.

**Implemented in FEATURE-003:** the engine order is taxonomy fit, then rule match (none-fit), then multi-intent / low confidence / near-tie (`LLM_INVESTIGATION` while budget remains, else `HUMAN_REVIEW`), then the approval-required type, then `DRAFT_PROPOSAL`. "Provider unavailable" is the separate `modelUnavailable` entry point. Thresholds live in `policy/<version>.json`; v1 is explicitly uncalibrated (`calibrated: false`, no-op values). See README "Taxonomy and policy".

## 5. Graph state (serialization design)

```java
record TriageGraphState(
    String triageId,
    IncomingCustomerRequest input,
    String taxonomyVersion,
    String policyVersion,
    SemanticAssessment semanticAssessment,
    InvestigationAssessment investigation,
    RoutingDecision routingDecision,
    int investigationAttempts,
    TriageStatus status,
    List<DecisionTraceEvent> events
) {}
```

Use Spring AI Alibaba's actual `StateGraph` / state update strategy API once pinned.

**Implemented in FEATURE-006:** `graph.TriageGraphState(input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment, providerError, gateOutcome, routingDecision, investigationAttempts, status, failure, route, trace)`; `triageId()` is derived from `input`, the trace is the event list and there is no separate `investigation` field until FEATURE-009. It is stored as the only `OverAllState` key (`triage`, `KeyStrategy.REPLACE`) in the strict JSON form `triage-graph-state-v1` produced by `TriageGraphStateCodec` (explicit wire records, enum names as strings, unknown fields/enums and other schema versions rejected). `providerError` keeps the exception class name only; `failure` is `TriageFailure(code, node, detail)` with a non-sensitive detail. A format change requires a new schema version; FEATURE-011 checkpoints will persist this form. Graph state and domain SR lifecycle state are not the same thing. On resume, verify checkpoints carry state and do not accidentally replay committed mock draft creations. `triageId` is the stable idempotency key for side effects.

## 5a. Evaluation goldset record (FEATURE-004)

Not a runtime contract: the record is read only by test-scoped evaluation code and must never be exposed to
prompts, providers or tools. `Channel` from section 1 is implemented as `domain.model.Channel`.

```java
record GoldCase(
    String caseId,                       // GS-NNNN, non-informative join key
    String message,                      // synthetic English, untrusted
    Channel channel,
    LabelSource labelSource,             // SYNTHETIC_AUTHOR
    String taxonomyVersion,              // v1
    Stratum stratum,                     // test design, not a prediction target
    PrimaryIntent intent,
    List<PrimaryIntent> secondaryIntents,
    ProblemDomain domain,
    RequestedOutcome requestedOutcome,
    ServiceRequestType formalSrType,
    String reasonCode,                   // belongs to formalSrType in the taxonomy
    RouteAction recommendedRoute,        // DRAFT_PROPOSAL | HUMAN_REVIEW | ABSTAIN
    boolean requiresHumanReview,         // == recommendedRoute != DRAFT_PROPOSAL
    GoldAlternatives alternatives,       // acceptable alternative labels per dimension
    String paraphraseGroup,              // PG-NN or null
    String labelRationale
) {}
```

Full schema, invariants and split recipe: `samples/english-goldset/README.md`.

## 6. Investigation Agent Tool interface

```java
interface ReadOnlyInvestigationTools {
    TaxonomyDefinition getTaxonomyDefinition(String categoryOrCode);
    PolicyExtract getClassificationPolicy(String policyId);
    List<SimilarSyntheticCase> findSimilarCases(String query, int limit);
    SyntheticCustomerContext getSyntheticCustomerContext(String syntheticRef);
}
```

Tools are read-only and limited to synthetic sources. Exclude evaluation goldset from tool indexes. LLM output is typed `InvestigationAssessment`: suggested types, cited policy/case IDs, unresolved questions, whether human review needed. Java Policy Engine makes the final decision.

## 7. Proposed REST endpoints

- `POST /api/triage` → start triage with English synthetic message; return `triageId`, status and optional immediate decision.
- `GET /api/triage/{triageId}` → status + decision trace.
- `GET /api/triage/{triageId}/result` → classification and proposed SR/task plan (if available).
- `POST /api/triage/{triageId}/review` → approve / correct / reject (mock; only when awaiting human; later resume graph).

Status/error semantics and sync/async behavior are defined incrementally in FEATURE-012. Use a stable `triageId`; duplicate review submissions must not double-create drafts.

## 8. Example expected **proposal**, not a live result

```json
{
  "triageId": "demo-001",
  "status": "AWAITING_HUMAN",
  "semantic": {
    "primaryIntent": "COMPLAINT",
    "problemDomain": "PAYMENTS",
    "requestedOutcome": "REFUND"
  },
  "proposal": {
    "taxonomyVersion": "v1",
    "srType": "CLAIM",
    "reasonCode": "DUPLICATE_CHARGE"
  },
  "decision": {
    "action": "HUMAN_REVIEW",
    "policyVersion": "v1",
    "rationaleCodes": ["FINANCIAL_DISPUTE_APPROVAL_REQUIRED"]
  },
  "proposedTasks": [
    {
      "taskType": "CLASSIFICATION_REVIEW",
      "assignedGroup": "CLAIMS_OPERATIONS"
    }
  ]
}
```

The schema above is illustrative; align eventual API DTOs with actual Java domain contracts.

## 9. Error handling and model pricing

- Timeouts + retries with backoff for rate limits/transient transport errors; avoid retry on bad credentials/schema.
- No exactly-once claim for cloud API calls, and no speculative confidence when no model response exists.
- Record model alias/resolved ID, question-set version, prompt version, usage, time, route and estimated cost.
- Check current vendor pricing at implementation time; do **not** embed a fixed price in business policy.

## References

- https://docs.typesafe.ai/api/system-one
- https://docs.typesafe.ai/primitives/choice
- https://docs.typesafe.ai/primitives/noul
- https://docs.typesafe.ai/primitives/score
- https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html
