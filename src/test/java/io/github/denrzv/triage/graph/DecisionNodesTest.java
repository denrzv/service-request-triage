package io.github.denrzv.triage.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationProposal;
import io.github.denrzv.triage.domain.model.DecisionTraceEvent;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.TriageStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionNodesTest {

    private static final String STRICT = GraphTestDefinitions.STRICT;

    private final ClassificationPolicyEngine engine = new ClassificationPolicyEngine();
    private final ConfidenceGateNode gate = new ConfidenceGateNode(GraphTestDefinitions.source(), engine);
    private final FinalPolicyGateNode finalGate = new FinalPolicyGateNode(GraphTestDefinitions.source(), engine);
    private final BuildTraceNode buildTrace = new BuildTraceNode();

    private static SemanticAssessment assessment(
            PrimaryIntent intent, ProblemDomain domain, double top, double taxonomyFit) {
        return new SemanticAssessment(
                GraphStateFixtures.choice(intent, PrimaryIntent.class, top),
                GraphStateFixtures.choice(domain, ProblemDomain.class, top),
                GraphStateFixtures.choice(RequestedOutcome.NOT_SPECIFIED, RequestedOutcome.class, top),
                0.1,
                0.1,
                0.1,
                taxonomyFit,
                "jev-test-1",
                "triage-jev-v1");
    }

    private static TriageGraphState classified(String policyVersion, SemanticAssessment assessment) {
        return TriageGraphState.received(GraphStateFixtures.request(), policyVersion)
                .withDefinitions(policyVersion, "v1")
                .withAssessment(assessment, "triage-jev-v1", "jev-test-1");
    }

    private static TriageGraphState providerFailed() {
        return TriageGraphState.received(GraphStateFixtures.request(), "v1")
                .withDefinitions("v1", "v1")
                .withProviderError("JevUnavailableException", "triage-jev-v1");
    }

    // ---- ConfidenceGate ----------------------------------------------------------------------------------------

    @Test
    void gateLabelsErrorWhenNoAssessmentExists() {
        TriageGraphState out = gate.apply(providerFailed());

        assertThat(out.gateOutcome()).isEqualTo(GateOutcome.ERROR);
        assertThat(out.trace().getLast())
                .isEqualTo(new DecisionTraceEvent(1, "ConfidenceGate", "error", "FinalPolicyGate"));
        assertThat(out.routingDecision()).isNull();
    }

    @Test
    void gateLabelsClearForConfidentMatchedRule() {
        TriageGraphState out = gate.apply(classified("v1", assessment(PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS,
                0.8, 0.9)));

        assertThat(out.gateOutcome()).isEqualTo(GateOutcome.CLEAR);
        assertThat(out.route()).isEqualTo("clear");
        assertThat(out.routingDecision()).isNull();
    }

    @Test
    void gateLabelsAbstainWhenNoRuleMatches() {
        TriageGraphState out = gate.apply(classified("v1", assessment(PrimaryIntent.INQUIRY, ProblemDomain.OTHER,
                0.8, 0.9)));

        assertThat(out.gateOutcome()).isEqualTo(GateOutcome.ABSTAIN);
    }

    @Test
    void gateLabelsAbstainOnLowTaxonomyFit() {
        TriageGraphState out = gate.apply(classified(STRICT, assessment(PrimaryIntent.COMPLAINT,
                ProblemDomain.PAYMENTS, 0.8, 0.3)));

        assertThat(out.gateOutcome()).isEqualTo(GateOutcome.ABSTAIN);
    }

    @Test
    void gateLabelsAmbiguousOnLowConfidence() {
        TriageGraphState out = gate.apply(classified(STRICT, assessment(PrimaryIntent.ACTION_REQUEST,
                ProblemDomain.ACCOUNTS, 0.5, 0.9)));

        assertThat(out.gateOutcome()).isEqualTo(GateOutcome.AMBIGUOUS);
        assertThat(out.trace().getLast().nextNode()).isEqualTo("FinalPolicyGate");
    }

    // ---- FinalPolicyGate ---------------------------------------------------------------------------------------

    @Test
    void errorPathUsesModelUnavailable() {
        TriageGraphState out = finalGate.apply(gate.apply(providerFailed()));

        RoutingDecision decision = out.routingDecision();
        assertThat(decision.action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(decision.proposal().reasonCode()).isEqualTo(ReasonCode.MODEL_UNAVAILABLE);
        assertThat(decision.rationaleCodes()).contains(RationaleCode.MODEL_UNAVAILABLE);
        assertThat(out.trace().getLast())
                .isEqualTo(new DecisionTraceEvent(2, "FinalPolicyGate", "decided", "BuildTrace"));
    }

    @Test
    void clearClaimRequiresHumanApproval() {
        TriageGraphState out = finalGate.apply(gate.apply(classified("v1", assessment(PrimaryIntent.COMPLAINT,
                ProblemDomain.PAYMENTS, 0.8, 0.9))));

        assertThat(out.routingDecision().action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(out.routingDecision().proposal().proposedType()).isEqualTo(ServiceRequestType.CLAIM);
        assertThat(out.routingDecision().rationaleCodes()).contains(RationaleCode.HUMAN_APPROVAL_REQUIRED);
    }

    @Test
    void clearInquiryIsADraftProposal() {
        TriageGraphState out = finalGate.apply(gate.apply(classified("v1", assessment(PrimaryIntent.INQUIRY,
                ProblemDomain.ACCOUNTS, 0.8, 0.9))));

        assertThat(out.routingDecision().action()).isEqualTo(RouteAction.DRAFT_PROPOSAL);
        assertThat(out.routingDecision().proposal().proposedType())
                .isEqualTo(ServiceRequestType.INFORMATION_REQUEST);
    }

    @Test
    void ambiguousPathEndsInHumanReviewBecauseNoInvestigatorIsWired() {
        TriageGraphState out = finalGate.apply(gate.apply(classified(STRICT, assessment(PrimaryIntent.ACTION_REQUEST,
                ProblemDomain.ACCOUNTS, 0.5, 0.9))));

        assertThat(out.routingDecision().action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(out.routingDecision().rationaleCodes())
                .contains(RationaleCode.LOW_CONFIDENCE, RationaleCode.INVESTIGATION_BUDGET_EXHAUSTED);
        assertThat(out.investigationAttempts()).isZero();
    }

    @Test
    void abstainPathYieldsConfiguredAbstention() {
        TriageGraphState out = finalGate.apply(gate.apply(classified(STRICT, assessment(PrimaryIntent.COMPLAINT,
                ProblemDomain.PAYMENTS, 0.8, 0.3))));

        assertThat(out.routingDecision().action()).isEqualTo(RouteAction.ABSTAIN);
        assertThat(out.routingDecision().proposal().reasonCode()).isEqualTo(ReasonCode.LOW_TAXONOMY_FIT);
    }

    @Test
    void investigationRouteFromTheEngineIsAnInvariantViolation() {
        RoutingDecision investigate = new RoutingDecision(
                RouteAction.LLM_INVESTIGATION,
                new ClassificationProposal("v1", ServiceRequestType.CLAIM, "PAYMENT_DISPUTE", List.of(), List.of()),
                List.of("R01-COMPLAINT-PAYMENTS"),
                List.of(RationaleCode.RULE_MATCHED, RationaleCode.NEAR_TIE),
                "v1",
                false,
                false);
        FinalPolicyGateNode stubbed = new FinalPolicyGateNode(
                GraphTestDefinitions.source(), engine, (assessment, taxonomy, policy, attempts) -> investigate);
        TriageGraphState in = classified("v1", assessment(PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, 0.8, 0.9))
                .withGateOutcome(GateOutcome.AMBIGUOUS);

        TriageGraphState out = stubbed.apply(in);

        assertThat(out.routingDecision()).isNull();
        assertThat(out.failure().code()).isEqualTo(FailureCode.INVARIANT_VIOLATED);
        assertThat(out.route()).isEqualTo(TriageRoutes.FAILED);
        assertThat(out.trace().getLast().nextNode()).isEqualTo("BuildTrace");
    }

    @Test
    void finalGateRequiresGateOutcome() {
        TriageGraphState in = classified("v1", assessment(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.8, 0.9));

        assertThatThrownBy(() -> finalGate.apply(in)).isInstanceOf(IllegalStateException.class);
    }

    // ---- BuildTrace --------------------------------------------------------------------------------------------

    @Test
    void statusIsDerivedFromTheDecision() {
        assertThat(buildTrace.apply(finalGate.apply(gate.apply(providerFailed()))).status())
                .isEqualTo(TriageStatus.AWAITING_HUMAN);
        assertThat(buildTrace.apply(finalGate.apply(gate.apply(classified("v1", assessment(PrimaryIntent.INQUIRY,
                        ProblemDomain.ACCOUNTS, 0.8, 0.9))))).status())
                .isEqualTo(TriageStatus.CLASSIFIED);
        assertThat(buildTrace.apply(finalGate.apply(gate.apply(classified(STRICT, assessment(PrimaryIntent.COMPLAINT,
                        ProblemDomain.PAYMENTS, 0.8, 0.3))))).status())
                .isEqualTo(TriageStatus.COMPLETED);
    }

    @Test
    void terminalTransitionLeadsToEnd() {
        TriageGraphState out = buildTrace.apply(finalGate.apply(gate.apply(providerFailed())));

        assertThat(out.trace().getLast()).isEqualTo(new DecisionTraceEvent(3, "BuildTrace", "finished", "END"));
        assertThat(out.finished()).isTrue();
    }

    @Test
    void recordedFailureEndsAsFailed() {
        TriageGraphState in = TriageGraphState.received(GraphStateFixtures.request(), null)
                .withFailure(new TriageFailure(FailureCode.INPUT_REJECTED, "ValidateInput", "MESSAGE_MISSING"));

        TriageGraphState out = buildTrace.apply(in);

        assertThat(out.status()).isEqualTo(TriageStatus.FAILED);
        assertThat(out.trace().getLast()).isEqualTo(new DecisionTraceEvent(1, "BuildTrace", "failed", "END"));
    }

    @Test
    void missingDecisionAndFailureIsRejected() {
        TriageGraphState in = TriageGraphState.received(GraphStateFixtures.request(), null);

        assertThatThrownBy(() -> buildTrace.apply(in)).isInstanceOf(IllegalStateException.class);
    }
}
