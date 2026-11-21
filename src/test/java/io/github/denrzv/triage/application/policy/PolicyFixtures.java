package io.github.denrzv.triage.application.policy;

import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.ClassificationRule;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RuleCondition;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.SignalRequirement;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic taxonomy, policies and assessments for engine tests. Values are test inputs, not calibrated policy. */
final class PolicyFixtures {

    static final String TAXONOMY_VERSION = "t-test";
    static final String POLICY_VERSION = "p-test";
    static final PolicyThresholds TEST_THRESHOLDS = new PolicyThresholds(0.5, 0.6, 0.2, 0.6, 0.5);
    static final PolicyThresholds NO_OP_THRESHOLDS = new PolicyThresholds(0.0, 0.0, 0.0, 1.0, 0.5);

    private static final ProblemDomain OTHER_DOMAIN_FOR_SECOND_PROBABILITY = ProblemDomain.OTHER;

    private PolicyFixtures() {}

    static Taxonomy taxonomy() {
        return new Taxonomy(
                TAXONOMY_VERSION,
                definitions(PrimaryIntent.class),
                definitions(ProblemDomain.class),
                definitions(RequestedOutcome.class),
                definitions(ServiceRequestType.class),
                List.of(
                        new ReasonCode(ReasonCode.NONE_FIT, ServiceRequestType.UNCLASSIFIED, "No rule fits"),
                        new ReasonCode(ReasonCode.LOW_TAXONOMY_FIT, ServiceRequestType.UNCLASSIFIED, "Low fit"),
                        new ReasonCode(ReasonCode.MODEL_UNAVAILABLE, ServiceRequestType.UNCLASSIFIED, "No data"),
                        new ReasonCode("PAYMENT_DISPUTE", ServiceRequestType.CLAIM, "Payment complaint"),
                        new ReasonCode("DIGITAL_CHANNEL_COMPLAINT", ServiceRequestType.CLAIM, "Channel complaint"),
                        new ReasonCode("TECHNICAL_MALFUNCTION", ServiceRequestType.TECHNICAL_REQUEST, "Malfunction"),
                        new ReasonCode("GENERAL_INFORMATION", ServiceRequestType.INFORMATION_REQUEST, "Question")));
    }

    static List<ClassificationRule> rules() {
        return List.of(
                new ClassificationRule(
                        "R-COMPLAINT-PAYMENTS",
                        condition(Set.of(PrimaryIntent.COMPLAINT), Set.of(ProblemDomain.PAYMENTS), SignalRequirement.ANY),
                        ServiceRequestType.CLAIM,
                        "PAYMENT_DISPUTE"),
                new ClassificationRule(
                        "R-COMPLAINT-DIGITAL",
                        condition(
                                Set.of(PrimaryIntent.COMPLAINT),
                                Set.of(ProblemDomain.DIGITAL_CHANNELS),
                                SignalRequirement.ANY),
                        ServiceRequestType.CLAIM,
                        "DIGITAL_CHANNEL_COMPLAINT"),
                new ClassificationRule(
                        "R-TECHNICAL-SIGNAL",
                        condition(Set.of(), Set.of(), SignalRequirement.PRESENT),
                        ServiceRequestType.TECHNICAL_REQUEST,
                        "TECHNICAL_MALFUNCTION"),
                new ClassificationRule(
                        "R-INQUIRY",
                        condition(
                                Set.of(PrimaryIntent.INQUIRY),
                                Set.of(ProblemDomain.PAYMENTS, ProblemDomain.ACCOUNTS, ProblemDomain.DIGITAL_CHANNELS),
                                SignalRequirement.ANY),
                        ServiceRequestType.INFORMATION_REQUEST,
                        "GENERAL_INFORMATION"));
    }

    static ClassificationPolicy testPolicy() {
        return policy(true, TEST_THRESHOLDS, RouteAction.ABSTAIN, 1);
    }

    static ClassificationPolicy policy(
            boolean calibrated, PolicyThresholds thresholds, RouteAction lowFitAction, int maxInvestigations) {
        return new ClassificationPolicy(
                POLICY_VERSION,
                TAXONOMY_VERSION,
                calibrated,
                thresholds,
                lowFitAction,
                RouteAction.ABSTAIN,
                maxInvestigations,
                Set.of(ServiceRequestType.CLAIM),
                rules());
    }

    /**
     * Assessment whose intent and domain judgments share {@code confidence}, a top probability {@code top} for the
     * selected value and {@code second} for one other value (controls the top-two margin).
     */
    static SemanticAssessment assessment(
            PrimaryIntent intent,
            ProblemDomain domain,
            double confidence,
            double top,
            double second,
            double technicalNoul,
            double multiIntentNoul,
            double taxonomyFitNoul) {
        return new SemanticAssessment(
                judgment(intent, other(intent), confidence, top, second),
                judgment(domain, otherDomain(domain), confidence, top, second),
                new ChoiceJudgment<>(RequestedOutcome.NOT_SPECIFIED, 0.2, Map.of(RequestedOutcome.NOT_SPECIFIED, 0.2)),
                0.0,
                technicalNoul,
                multiIntentNoul,
                taxonomyFitNoul,
                "jev-test",
                "qs-test");
    }

    static SemanticAssessment clear(PrimaryIntent intent, ProblemDomain domain, double technicalNoul) {
        return assessment(intent, domain, 0.9, 0.9, 0.05, technicalNoul, 0.1, 0.9);
    }

    private static <E extends Enum<E>> ChoiceJudgment<E> judgment(
            E selected, E runnerUp, double confidence, double top, double second) {
        Map<E, Double> probabilities = new LinkedHashMap<>();
        probabilities.put(selected, top);
        probabilities.put(runnerUp, second);
        return new ChoiceJudgment<>(selected, confidence, probabilities);
    }

    private static PrimaryIntent other(PrimaryIntent intent) {
        return intent == PrimaryIntent.INQUIRY ? PrimaryIntent.COMPLAINT : PrimaryIntent.INQUIRY;
    }

    private static ProblemDomain otherDomain(ProblemDomain domain) {
        return domain == OTHER_DOMAIN_FOR_SECOND_PROBABILITY ? ProblemDomain.PAYMENTS : OTHER_DOMAIN_FOR_SECOND_PROBABILITY;
    }

    private static RuleCondition condition(
            Set<PrimaryIntent> intents, Set<ProblemDomain> domains, SignalRequirement technical) {
        return new RuleCondition(intents, domains, Set.of(), SignalRequirement.ANY, technical);
    }

    private static <E extends Enum<E>> Map<E, String> definitions(Class<E> type) {
        Map<E, String> map = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            map.put(constant, "Synthetic definition of " + constant.name());
        }
        return map;
    }
}
