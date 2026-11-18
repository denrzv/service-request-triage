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

Use Spring AI Alibaba's actual `StateGraph` / state update strategy API once pinned. Graph state and domain SR lifecycle state are not the same thing. On resume, verify checkpoints carry state and do not accidentally replay committed mock draft creations. `triageId` is the stable idempotency key for side effects.

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
