package io.github.denrzv.triage.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClassificationPolicyEngineTest {

    private final ClassificationPolicyEngine engine = new ClassificationPolicyEngine();
    private final Taxonomy taxonomy = PolicyFixtures.taxonomy();

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            useHeadersInDisplayName = false,
            textBlock =
                    """
            # scenario,             intent,          domain,          conf, top,  second, tech, multi, fit, attempts, action,            type,                reason,                    rationale
            complaint payments,     COMPLAINT,       PAYMENTS,        0.9,  0.9,  0.05,   0.1,  0.1,   0.9, 0,        HUMAN_REVIEW,      CLAIM,               PAYMENT_DISPUTE,           HUMAN_APPROVAL_REQUIRED
            technical issue,        INCIDENT_REPORT, DIGITAL_CHANNELS, 0.9, 0.9,  0.05,   0.9,  0.1,   0.9, 0,        DRAFT_PROPOSAL,    TECHNICAL_REQUEST,   TECHNICAL_MALFUNCTION,     RULE_MATCHED
            technical low conf,     INCIDENT_REPORT, DIGITAL_CHANNELS, 0.3, 0.4,  0.35,   0.9,  0.1,   0.9, 0,        DRAFT_PROPOSAL,    TECHNICAL_REQUEST,   TECHNICAL_MALFUNCTION,     RULE_MATCHED
            mixed intent first,     COMPLAINT,       DIGITAL_CHANNELS, 0.9, 0.9,  0.05,   0.9,  0.8,   0.9, 0,        LLM_INVESTIGATION, CLAIM,               DIGITAL_CHANNEL_COMPLAINT, MULTI_INTENT
            mixed intent exhausted, COMPLAINT,       DIGITAL_CHANNELS, 0.9, 0.9,  0.05,   0.9,  0.8,   0.9, 1,        HUMAN_REVIEW,      CLAIM,               DIGITAL_CHANNEL_COMPLAINT, INVESTIGATION_BUDGET_EXHAUSTED
            mixed intent clear,     COMPLAINT,       DIGITAL_CHANNELS, 0.9, 0.9,  0.05,   0.9,  0.2,   0.9, 0,        HUMAN_REVIEW,      CLAIM,               DIGITAL_CHANNEL_COMPLAINT, HUMAN_APPROVAL_REQUIRED
            none fit,               ACTION_REQUEST,  OTHER,           0.9,  0.9,  0.05,   0.1,  0.1,   0.9, 0,        ABSTAIN,           UNCLASSIFIED,        NONE_FIT,                  NO_RULE_MATCHED
            out of taxonomy,        INQUIRY,         PAYMENTS,        0.9,  0.9,  0.05,   0.1,  0.1,   0.2, 0,        ABSTAIN,           UNCLASSIFIED,        LOW_TAXONOMY_FIT,          TAXONOMY_FIT_LOW
            inquiry clear,          INQUIRY,         ACCOUNTS,        0.9,  0.9,  0.05,   0.1,  0.1,   0.9, 0,        DRAFT_PROPOSAL,    INFORMATION_REQUEST, GENERAL_INFORMATION,       RULE_MATCHED
            inquiry low confidence, INQUIRY,         ACCOUNTS,        0.4,  0.9,  0.05,   0.1,  0.1,   0.9, 0,        LLM_INVESTIGATION, INFORMATION_REQUEST, GENERAL_INFORMATION,       LOW_CONFIDENCE
            inquiry near tie,       INQUIRY,         ACCOUNTS,        0.9,  0.45, 0.4,    0.1,  0.1,   0.9, 0,        LLM_INVESTIGATION, INFORMATION_REQUEST, GENERAL_INFORMATION,       NEAR_TIE
            near tie exhausted,     INQUIRY,         ACCOUNTS,        0.9,  0.45, 0.4,    0.1,  0.1,   0.9, 1,        HUMAN_REVIEW,      INFORMATION_REQUEST, GENERAL_INFORMATION,       INVESTIGATION_BUDGET_EXHAUSTED
            """)
    void decisionTable(
            String scenario,
            PrimaryIntent intent,
            ProblemDomain domain,
            double confidence,
            double top,
            double second,
            double technical,
            double multiIntent,
            double taxonomyFit,
            int attempts,
            RouteAction expectedAction,
            ServiceRequestType expectedType,
            String expectedReason,
            RationaleCode expectedRationale) {
        SemanticAssessment assessment =
                PolicyFixtures.assessment(intent, domain, confidence, top, second, technical, multiIntent, taxonomyFit);

        RoutingDecision decision = engine.decide(assessment, taxonomy, PolicyFixtures.testPolicy(), attempts);

        assertThat(decision.action()).as(scenario).isEqualTo(expectedAction);
        assertThat(decision.proposal().proposedType()).as(scenario).isEqualTo(expectedType);
        assertThat(decision.proposal().reasonCode()).as(scenario).isEqualTo(expectedReason);
        assertThat(decision.rationaleCodes()).as(scenario).contains(expectedRationale);
        assertThat(decision.proposal().taxonomyVersion()).isEqualTo(PolicyFixtures.TAXONOMY_VERSION);
        assertThat(decision.policyVersion()).isEqualTo(PolicyFixtures.POLICY_VERSION);
        assertThat(decision.humanApprovalRequired()).isEqualTo(expectedAction == RouteAction.HUMAN_REVIEW);
    }

    @Test
    void mixedIntentKeepsTechnicalRequestAsAlternativeAndListsAllMatchedRules() {
        SemanticAssessment assessment = PolicyFixtures.clear(PrimaryIntent.COMPLAINT, ProblemDomain.DIGITAL_CHANNELS, 0.9);

        RoutingDecision decision = engine.decide(assessment, taxonomy, PolicyFixtures.testPolicy(), 0);

        assertThat(decision.proposal().alternatives()).containsExactly(ServiceRequestType.TECHNICAL_REQUEST);
        assertThat(decision.ruleIds()).containsExactly("R-COMPLAINT-DIGITAL", "R-TECHNICAL-SIGNAL");
        assertThat(decision.proposal().supportingSignals())
                .containsExactly("primaryIntent=COMPLAINT", "problemDomain=DIGITAL_CHANNELS");
    }

    @Test
    void modelUnavailableRoutesToHumanReviewWithoutClassification() {
        RoutingDecision decision = engine.modelUnavailable(taxonomy, PolicyFixtures.testPolicy());

        assertThat(decision.action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(decision.proposal().proposedType()).isEqualTo(ServiceRequestType.UNCLASSIFIED);
        assertThat(decision.proposal().reasonCode()).isEqualTo(ReasonCode.MODEL_UNAVAILABLE);
        assertThat(decision.rationaleCodes()).containsExactly(RationaleCode.MODEL_UNAVAILABLE);
        assertThat(decision.ruleIds()).isEmpty();
    }

    @Test
    void uncalibratedNoOpThresholdsActOnlyOnCategoricalRulesAndAreMarked() {
        ClassificationPolicy noOp = PolicyFixtures.policy(false, PolicyFixtures.NO_OP_THRESHOLDS, RouteAction.ABSTAIN, 1);
        SemanticAssessment ambiguous = PolicyFixtures.assessment(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.1, 0.34, 0.33, 0.1, 0.99, 0.01);

        RoutingDecision decision = engine.decide(ambiguous, taxonomy, noOp, 0);

        assertThat(decision.action()).isEqualTo(RouteAction.DRAFT_PROPOSAL);
        assertThat(decision.policyCalibrated()).isFalse();
        assertThat(decision.rationaleCodes())
                .containsExactly(RationaleCode.RULE_MATCHED, RationaleCode.THRESHOLDS_UNCALIBRATED);
    }

    @Test
    void calibratedPolicyDecisionsCarryNoUncalibratedMarker() {
        RoutingDecision decision = engine.decide(
                PolicyFixtures.clear(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.1),
                taxonomy,
                PolicyFixtures.testPolicy(),
                0);

        assertThat(decision.policyCalibrated()).isTrue();
        assertThat(decision.rationaleCodes()).containsExactly(RationaleCode.RULE_MATCHED);
    }

    @Test
    void lowTaxonomyFitActionIsConfigurable() {
        ClassificationPolicy reviewOnLowFit =
                PolicyFixtures.policy(true, PolicyFixtures.TEST_THRESHOLDS, RouteAction.HUMAN_REVIEW, 1);
        SemanticAssessment lowFit =
                PolicyFixtures.assessment(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.9, 0.9, 0.05, 0.1, 0.1, 0.1);

        RoutingDecision decision = engine.decide(lowFit, taxonomy, reviewOnLowFit, 0);

        assertThat(decision.action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(decision.proposal().proposedType()).isEqualTo(ServiceRequestType.UNCLASSIFIED);
        assertThat(decision.rationaleCodes()).containsExactly(RationaleCode.TAXONOMY_FIT_LOW);
    }

    @Test
    void zeroInvestigationBudgetGoesStraightToHumanReview() {
        ClassificationPolicy noInvestigation =
                PolicyFixtures.policy(true, PolicyFixtures.TEST_THRESHOLDS, RouteAction.ABSTAIN, 0);
        SemanticAssessment lowConfidence =
                PolicyFixtures.assessment(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.4, 0.9, 0.05, 0.1, 0.1, 0.9);

        RoutingDecision decision = engine.decide(lowConfidence, taxonomy, noInvestigation, 0);

        assertThat(decision.action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(decision.rationaleCodes())
                .containsExactly(
                        RationaleCode.RULE_MATCHED,
                        RationaleCode.LOW_CONFIDENCE,
                        RationaleCode.INVESTIGATION_BUDGET_EXHAUSTED);
    }

    @Test
    void equalInputsProduceEqualDecisions() {
        SemanticAssessment assessment = PolicyFixtures.clear(PrimaryIntent.COMPLAINT, ProblemDomain.DIGITAL_CHANNELS, 0.9);
        ClassificationPolicy policy = PolicyFixtures.testPolicy();

        RoutingDecision first = engine.decide(assessment, taxonomy, policy, 0);
        RoutingDecision second = new ClassificationPolicyEngine().decide(assessment, PolicyFixtures.taxonomy(), policy, 0);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void invalidInputsAreRejected() {
        SemanticAssessment assessment = PolicyFixtures.clear(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.1);
        ClassificationPolicy policy = PolicyFixtures.testPolicy();
        ClassificationPolicy otherTaxonomy = new ClassificationPolicy(
                "p",
                "unknown-taxonomy",
                true,
                PolicyFixtures.TEST_THRESHOLDS,
                RouteAction.ABSTAIN,
                RouteAction.ABSTAIN,
                1,
                Set.of(),
                PolicyFixtures.rules());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.decide(assessment, taxonomy, policy, -1))
                .withMessageContaining("investigationAttempts");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.decide(null, taxonomy, policy, 0))
                .withMessageContaining("assessment");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.decide(assessment, taxonomy, otherTaxonomy, 0))
                .withMessageContaining("targets taxonomy unknown-taxonomy");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.modelUnavailable(null, policy))
                .withMessageContaining("taxonomy");
        assertThat(List.of(RouteAction.values()))
                .containsExactlyInAnyOrder(
                        RouteAction.DRAFT_PROPOSAL,
                        RouteAction.LLM_INVESTIGATION,
                        RouteAction.HUMAN_REVIEW,
                        RouteAction.ABSTAIN);
    }
}
