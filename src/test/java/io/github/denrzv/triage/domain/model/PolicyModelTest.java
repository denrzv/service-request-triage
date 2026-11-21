package io.github.denrzv.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyModelTest {

    @Test
    void thresholdsMustBeWithinUnitInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyThresholds(-0.1, 0, 0, 1, 0.5))
                .withMessageContaining("taxonomyFitMin");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PolicyThresholds(0, 0, 0, 1.2, 0.5))
                .withMessageContaining("multiIntentMax");
    }

    @ParameterizedTest
    @CsvSource({"ANY,0.0,true", "PRESENT,0.5,true", "PRESENT,0.49,false", "ABSENT,0.49,true", "ABSENT,0.5,false"})
    void signalRequirementUsesInclusiveBoundary(SignalRequirement requirement, double noul, boolean expected) {
        assertThat(requirement.accepts(noul, 0.5)).isEqualTo(expected);
    }

    @Test
    void ruleConditionMatchesOnConstrainedDimensionsAndSignals() {
        RuleCondition refundComplaint = new RuleCondition(
                Set.of(PrimaryIntent.COMPLAINT), Set.of(), Set.of(), SignalRequirement.PRESENT, SignalRequirement.ANY);
        SemanticAssessment withRefund =
                DomainFixtures.assessment(PrimaryIntent.COMPLAINT, ProblemDomain.OTHER, 0.8, 0.0);
        SemanticAssessment withoutRefund =
                DomainFixtures.assessment(PrimaryIntent.COMPLAINT, ProblemDomain.OTHER, 0.2, 0.0);
        SemanticAssessment inquiry = DomainFixtures.assessment(PrimaryIntent.INQUIRY, ProblemDomain.OTHER, 0.8, 0.0);

        assertThat(refundComplaint.matches(withRefund, 0.5)).isTrue();
        assertThat(refundComplaint.matches(withoutRefund, 0.5)).isFalse();
        assertThat(refundComplaint.matches(inquiry, 0.5)).isFalse();
    }

    @Test
    void unconstrainedRuleConditionIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RuleCondition(
                        Set.of(), Set.of(), Set.of(), SignalRequirement.ANY, SignalRequirement.ANY))
                .withMessageContaining("at least one");
    }

    @Test
    void ruleMustNotProposeUnclassified() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClassificationRule(
                        "R1", DomainFixtures.complaintAboutPayments(), ServiceRequestType.UNCLASSIFIED, "NONE_FIT"))
                .withMessageContaining("UNCLASSIFIED");
    }

    @Test
    void policyRejectsInvalidStructure() {
        ClassificationRule rule = DomainFixtures.claimRule("R1", "PAYMENT_DISPUTE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainFixtures.policy(DomainFixtures.VERSION, List.of()))
                .withMessageContaining("rules must not be empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainFixtures.policy(DomainFixtures.VERSION, List.of(rule, rule)))
                .withMessageContaining("duplicate rule id R1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policyWith(RouteAction.DRAFT_PROPOSAL, 1))
                .withMessageContaining("lowTaxonomyFitAction");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policyWith(RouteAction.ABSTAIN, ClassificationPolicy.MAX_INVESTIGATIONS_LIMIT + 1))
                .withMessageContaining("maxInvestigations");
    }

    @Test
    void compatibilityChecksTaxonomyVersionAndReasonCodes() {
        Taxonomy taxonomy = DomainFixtures.taxonomy();

        assertThatNoException()
                .isThrownBy(() -> DomainFixtures.policy(
                                DomainFixtures.VERSION, List.of(DomainFixtures.claimRule("R1", "PAYMENT_DISPUTE")))
                        .requireCompatibleWith(taxonomy));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainFixtures.policy(
                                "other", List.of(DomainFixtures.claimRule("R1", "PAYMENT_DISPUTE")))
                        .requireCompatibleWith(taxonomy))
                .withMessageContaining("targets taxonomy other");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainFixtures.policy(
                                DomainFixtures.VERSION, List.of(DomainFixtures.claimRule("R1", "MISSING")))
                        .requireCompatibleWith(taxonomy))
                .withMessageContaining("rule R1: unknown reason code MISSING");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainFixtures.policy(
                                DomainFixtures.VERSION, List.of(DomainFixtures.claimRule("R1", ReasonCode.NONE_FIT)))
                        .requireCompatibleWith(taxonomy))
                .withMessageContaining("belongs to UNCLASSIFIED");
    }

    private static ClassificationPolicy policyWith(RouteAction lowFitAction, int maxInvestigations) {
        return new ClassificationPolicy(
                "p",
                "t",
                false,
                DomainFixtures.noOpThresholds(),
                lowFitAction,
                RouteAction.ABSTAIN,
                maxInvestigations,
                Set.of(),
                List.of(DomainFixtures.claimRule("R1", "PAYMENT_DISPUTE")));
    }
}
