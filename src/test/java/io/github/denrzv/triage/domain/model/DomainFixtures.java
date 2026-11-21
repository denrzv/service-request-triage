package io.github.denrzv.triage.domain.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic builders shared by the domain model tests. */
final class DomainFixtures {

    static final String VERSION = "v-test";

    private DomainFixtures() {}

    static <E extends Enum<E>> Map<E, String> definitions(Class<E> type) {
        Map<E, String> map = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            map.put(constant, "Synthetic definition of " + constant.name());
        }
        return map;
    }

    static List<ReasonCode> reasonCodes() {
        return List.of(
                new ReasonCode(ReasonCode.NONE_FIT, ServiceRequestType.UNCLASSIFIED, "No rule fits"),
                new ReasonCode(ReasonCode.LOW_TAXONOMY_FIT, ServiceRequestType.UNCLASSIFIED, "Low fit"),
                new ReasonCode(ReasonCode.MODEL_UNAVAILABLE, ServiceRequestType.UNCLASSIFIED, "No assessment"),
                new ReasonCode("PAYMENT_DISPUTE", ServiceRequestType.CLAIM, "Payment complaint"));
    }

    static Taxonomy taxonomy() {
        return new Taxonomy(
                VERSION,
                definitions(PrimaryIntent.class),
                definitions(ProblemDomain.class),
                definitions(RequestedOutcome.class),
                definitions(ServiceRequestType.class),
                reasonCodes());
    }

    static PolicyThresholds noOpThresholds() {
        return new PolicyThresholds(0.0, 0.0, 0.0, 1.0, 0.5);
    }

    static RuleCondition complaintAboutPayments() {
        return new RuleCondition(
                Set.of(PrimaryIntent.COMPLAINT),
                Set.of(ProblemDomain.PAYMENTS),
                Set.of(),
                SignalRequirement.ANY,
                SignalRequirement.ANY);
    }

    static ClassificationRule claimRule(String id, String reasonCode) {
        return new ClassificationRule(id, complaintAboutPayments(), ServiceRequestType.CLAIM, reasonCode);
    }

    static ClassificationPolicy policy(String taxonomyVersion, List<ClassificationRule> rules) {
        return new ClassificationPolicy(
                "p-test",
                taxonomyVersion,
                false,
                noOpThresholds(),
                RouteAction.ABSTAIN,
                RouteAction.HUMAN_REVIEW,
                1,
                Set.of(ServiceRequestType.CLAIM),
                rules);
    }

    static <E extends Enum<E>> ChoiceJudgment<E> sure(E value) {
        return new ChoiceJudgment<>(value, 0.9, Map.of(value, 0.9));
    }

    static SemanticAssessment assessment(
            PrimaryIntent intent, ProblemDomain domain, double refundNoul, double technicalNoul) {
        return new SemanticAssessment(
                sure(intent),
                sure(domain),
                sure(RequestedOutcome.NOT_SPECIFIED),
                refundNoul,
                technicalNoul,
                0.1,
                0.9,
                "jev-test",
                "qs-test");
    }
}
