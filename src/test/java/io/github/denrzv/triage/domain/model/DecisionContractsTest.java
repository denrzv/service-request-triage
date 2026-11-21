package io.github.denrzv.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DecisionContractsTest {

    private static final String POLICY = "p1";

    @Test
    void choiceJudgmentComputesTopTwoMargin() {
        ChoiceJudgment<PrimaryIntent> judgment = new ChoiceJudgment<>(
                PrimaryIntent.COMPLAINT,
                0.7,
                Map.of(PrimaryIntent.COMPLAINT, 0.6, PrimaryIntent.INQUIRY, 0.25, PrimaryIntent.ACTION_REQUEST, 0.15));

        assertThat(judgment.margin()).isCloseTo(0.35, Offset.offset(1e-9));
        assertThat(DomainFixtures.sure(PrimaryIntent.INQUIRY).margin()).isEqualTo(0.9);
    }

    @Test
    void choiceJudgmentRejectsInvalidValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChoiceJudgment<>(PrimaryIntent.COMPLAINT, 1.1, Map.of(PrimaryIntent.COMPLAINT, 1.0)))
                .withMessageContaining("confidence");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChoiceJudgment<>(PrimaryIntent.COMPLAINT, 0.5, Map.of(PrimaryIntent.COMPLAINT, -0.1)))
                .withMessageContaining("probability");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChoiceJudgment<>(PrimaryIntent.COMPLAINT, 0.5, Map.of(PrimaryIntent.INQUIRY, 0.5)))
                .withMessageContaining("selected value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChoiceJudgment<PrimaryIntent>(null, 0.5, Map.of()))
                .withMessageContaining("selected");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChoiceJudgment<>(PrimaryIntent.COMPLAINT, Double.NaN, Map.of(PrimaryIntent.COMPLAINT, 1.0)))
                .withMessageContaining("confidence");
    }

    @Test
    void semanticAssessmentRequiresEveryJudgmentAndUnitNouls() {
        ChoiceJudgment<PrimaryIntent> intent = DomainFixtures.sure(PrimaryIntent.COMPLAINT);
        ChoiceJudgment<ProblemDomain> domain = DomainFixtures.sure(ProblemDomain.PAYMENTS);
        ChoiceJudgment<RequestedOutcome> outcome = DomainFixtures.sure(RequestedOutcome.REFUND);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticAssessment(null, domain, outcome, 0, 0, 0, 0, "m", "q"))
                .withMessageContaining("primaryIntent");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticAssessment(intent, domain, outcome, 0, 0, 0, 1.5, "m", "q"))
                .withMessageContaining("taxonomyFitNoul");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticAssessment(intent, domain, outcome, 0, 0, 0, 0, " ", "q"))
                .withMessageContaining("modelUsed");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticAssessment(intent, domain, outcome, 0, 0, 0, 0, "m", ""))
                .withMessageContaining("questionSetVersion");
    }

    @Test
    void proposalRequiresTaxonomyVersionAndDistinctAlternatives() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proposal(" ", ServiceRequestType.CLAIM, List.of()))
                .withMessageContaining("taxonomyVersion");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proposal("v1", ServiceRequestType.CLAIM, List.of(ServiceRequestType.CLAIM)))
                .withMessageContaining("alternatives");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> proposal(
                        "v1",
                        ServiceRequestType.CLAIM,
                        List.of(ServiceRequestType.TECHNICAL_REQUEST, ServiceRequestType.TECHNICAL_REQUEST)))
                .withMessageContaining("alternatives");
    }

    @Test
    void decisionRequiresPolicyVersionAndRationale() {
        ClassificationProposal claim = proposal("v1", ServiceRequestType.CLAIM, List.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> decision(RouteAction.DRAFT_PROPOSAL, claim, "", false))
                .withMessageContaining("policyVersion");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RoutingDecision(
                        RouteAction.DRAFT_PROPOSAL, claim, List.of(), List.of(), POLICY, false, false))
                .withMessageContaining("rationaleCodes");
    }

    @ParameterizedTest
    @EnumSource(RouteAction.class)
    void humanApprovalFlagMatchesHumanReviewExactly(RouteAction action) {
        ServiceRequestType type =
                action == RouteAction.ABSTAIN ? ServiceRequestType.UNCLASSIFIED : ServiceRequestType.CLAIM;
        ClassificationProposal proposal = proposal("v1", type, List.of());
        boolean review = action == RouteAction.HUMAN_REVIEW;

        assertThat(decision(action, proposal, POLICY, review).humanApprovalRequired()).isEqualTo(review);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> decision(action, proposal, POLICY, !review))
                .withMessageContaining("humanApprovalRequired");
    }

    @Test
    void abstainOnlyWithUnclassifiedAndDraftNeverUnclassified() {
        ClassificationProposal unclassified = proposal("v1", ServiceRequestType.UNCLASSIFIED, List.of());
        ClassificationProposal claim = proposal("v1", ServiceRequestType.CLAIM, List.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> decision(RouteAction.ABSTAIN, claim, POLICY, false))
                .withMessageContaining("ABSTAIN");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> decision(RouteAction.DRAFT_PROPOSAL, unclassified, POLICY, false))
                .withMessageContaining("DRAFT_PROPOSAL");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> decision(RouteAction.LLM_INVESTIGATION, unclassified, POLICY, false))
                .withMessageContaining("LLM_INVESTIGATION");
        assertThat(decision(RouteAction.HUMAN_REVIEW, unclassified, POLICY, true).action())
                .isEqualTo(RouteAction.HUMAN_REVIEW);
    }

    private static ClassificationProposal proposal(
            String taxonomyVersion, ServiceRequestType type, List<ServiceRequestType> alternatives) {
        return new ClassificationProposal(taxonomyVersion, type, "CODE", alternatives, List.of("signal"));
    }

    private static RoutingDecision decision(
            RouteAction action, ClassificationProposal proposal, String policyVersion, boolean approval) {
        return new RoutingDecision(
                action, proposal, List.of("R1"), List.of(RationaleCode.RULE_MATCHED), policyVersion, false, approval);
    }
}
