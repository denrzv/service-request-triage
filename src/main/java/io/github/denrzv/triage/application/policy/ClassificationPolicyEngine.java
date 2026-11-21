package io.github.denrzv.triage.application.policy;

import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.ClassificationProposal;
import io.github.denrzv.triage.domain.model.ClassificationRule;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.RuleCondition;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.SignalRequirement;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic Policy Engine (ADR-003/ADR-004). It never calls a model, performs no I/O and holds no state:
 * equal inputs always give equal decisions. Evaluation order:
 *
 * <ol>
 *   <li>taxonomy fit below {@code taxonomyFitMin} &rarr; policy's low-fit action ({@code ABSTAIN|HUMAN_REVIEW});
 *   <li>no matching rule (none-fit) &rarr; policy's no-match action;
 *   <li>multi-intent above {@code multiIntentMax}, or low confidence / near-tie on a dimension the matched rule
 *       constrains &rarr; {@code LLM_INVESTIGATION} while the investigation budget remains, else {@code HUMAN_REVIEW};
 *   <li>proposed type requiring approval &rarr; {@code HUMAN_REVIEW};
 *   <li>otherwise {@code DRAFT_PROPOSAL}.
 * </ol>
 *
 * <p>{@code ruleIds} lists every matching rule in table order; the first one determined the proposal and the SR types
 * of the others become alternatives. Decisions of an uncalibrated policy carry {@link
 * RationaleCode#THRESHOLDS_UNCALIBRATED}.
 */
public final class ClassificationPolicyEngine {

    private static final String PRIMARY_INTENT = "primaryIntent=";
    private static final String PROBLEM_DOMAIN = "problemDomain=";
    private static final String REQUESTED_OUTCOME = "requestedOutcome=";
    private static final String REFUND_REQUESTED = "refundRequested=";
    private static final String TECHNICAL_PROBLEM = "technicalProblem=";
    private static final String TAXONOMY_FIT_BELOW_MINIMUM = "taxonomyFit<min";

    /**
     * Decides formal type and route for an available assessment.
     *
     * @param investigationAttempts LLM investigations already performed for this triage (bounded by the policy)
     */
    public RoutingDecision decide(
            SemanticAssessment assessment, Taxonomy taxonomy, ClassificationPolicy policy, int investigationAttempts) {
        requireNonNull(assessment, "assessment");
        requireDefinitions(taxonomy, policy);
        if (investigationAttempts < 0) {
            throw new IllegalArgumentException("investigationAttempts must not be negative");
        }
        PolicyThresholds thresholds = policy.thresholds();
        if (assessment.taxonomyFitNoul() < thresholds.taxonomyFitMin()) {
            return unclassified(
                    policy.lowTaxonomyFitAction(),
                    ReasonCode.LOW_TAXONOMY_FIT,
                    RationaleCode.TAXONOMY_FIT_LOW,
                    List.of(TAXONOMY_FIT_BELOW_MINIMUM),
                    taxonomy,
                    policy);
        }
        List<ClassificationRule> matches = policy.rules().stream()
                .filter(rule -> rule.when().matches(assessment, thresholds.signalMin()))
                .toList();
        if (matches.isEmpty()) {
            return unclassified(
                    policy.noRuleMatchedAction(),
                    ReasonCode.NONE_FIT,
                    RationaleCode.NO_RULE_MATCHED,
                    List.of(
                            PRIMARY_INTENT + assessment.primaryIntent().selected(),
                            PROBLEM_DOMAIN + assessment.problemDomain().selected()),
                    taxonomy,
                    policy);
        }
        return classified(assessment, taxonomy, policy, matches, investigationAttempts);
    }

    /** Safe decision when no valid assessment exists (provider unavailable or invalid judgment). */
    public RoutingDecision modelUnavailable(Taxonomy taxonomy, ClassificationPolicy policy) {
        requireDefinitions(taxonomy, policy);
        return unclassified(
                RouteAction.HUMAN_REVIEW,
                ReasonCode.MODEL_UNAVAILABLE,
                RationaleCode.MODEL_UNAVAILABLE,
                List.of(),
                taxonomy,
                policy);
    }

    private static RoutingDecision classified(
            SemanticAssessment assessment,
            Taxonomy taxonomy,
            ClassificationPolicy policy,
            List<ClassificationRule> matches,
            int investigationAttempts) {
        ClassificationRule decisive = matches.getFirst();
        ServiceRequestType type = decisive.serviceRequestType();
        List<ServiceRequestType> alternatives = matches.stream()
                .map(ClassificationRule::serviceRequestType)
                .filter(candidate -> candidate != type)
                .distinct()
                .toList();
        ClassificationProposal proposal = new ClassificationProposal(
                taxonomy.version(), type, decisive.reasonCode(), alternatives, signals(decisive.when(), assessment));
        List<String> ruleIds = matches.stream().map(ClassificationRule::id).toList();

        List<RationaleCode> rationale = new ArrayList<>();
        rationale.add(RationaleCode.RULE_MATCHED);
        List<RationaleCode> concerns = concerns(assessment, decisive.when(), policy.thresholds());
        RouteAction action;
        if (!concerns.isEmpty()) {
            rationale.addAll(concerns);
            if (investigationAttempts < policy.maxInvestigations()) {
                action = RouteAction.LLM_INVESTIGATION;
            } else {
                action = RouteAction.HUMAN_REVIEW;
                rationale.add(RationaleCode.INVESTIGATION_BUDGET_EXHAUSTED);
            }
        } else if (policy.humanApprovalTypes().contains(type)) {
            action = RouteAction.HUMAN_REVIEW;
            rationale.add(RationaleCode.HUMAN_APPROVAL_REQUIRED);
        } else {
            action = RouteAction.DRAFT_PROPOSAL;
        }
        return decision(action, proposal, ruleIds, rationale, policy);
    }

    /** Ambiguity concerns, checked only on the dimensions the decisive rule depends on. */
    private static List<RationaleCode> concerns(
            SemanticAssessment assessment, RuleCondition condition, PolicyThresholds thresholds) {
        List<ChoiceJudgment<?>> relevant = new ArrayList<>();
        if (!condition.primaryIntents().isEmpty()) {
            relevant.add(assessment.primaryIntent());
        }
        if (!condition.problemDomains().isEmpty()) {
            relevant.add(assessment.problemDomain());
        }
        if (!condition.requestedOutcomes().isEmpty()) {
            relevant.add(assessment.requestedOutcome());
        }
        List<RationaleCode> concerns = new ArrayList<>();
        if (assessment.multiIntentNoul() > thresholds.multiIntentMax()) {
            concerns.add(RationaleCode.MULTI_INTENT);
        }
        if (relevant.stream().anyMatch(judgment -> judgment.confidence() < thresholds.choiceConfidenceMin())) {
            concerns.add(RationaleCode.LOW_CONFIDENCE);
        }
        if (relevant.stream().anyMatch(judgment -> judgment.margin() < thresholds.choiceMarginMin())) {
            concerns.add(RationaleCode.NEAR_TIE);
        }
        return concerns;
    }

    private static List<String> signals(RuleCondition condition, SemanticAssessment assessment) {
        List<String> signals = new ArrayList<>();
        if (!condition.primaryIntents().isEmpty()) {
            signals.add(PRIMARY_INTENT + assessment.primaryIntent().selected());
        }
        if (!condition.problemDomains().isEmpty()) {
            signals.add(PROBLEM_DOMAIN + assessment.problemDomain().selected());
        }
        if (!condition.requestedOutcomes().isEmpty()) {
            signals.add(REQUESTED_OUTCOME + assessment.requestedOutcome().selected());
        }
        if (condition.refundRequested() != SignalRequirement.ANY) {
            signals.add(REFUND_REQUESTED + condition.refundRequested());
        }
        if (condition.technicalProblem() != SignalRequirement.ANY) {
            signals.add(TECHNICAL_PROBLEM + condition.technicalProblem());
        }
        return signals;
    }

    private static RoutingDecision unclassified(
            RouteAction action,
            String reasonCode,
            RationaleCode rationaleCode,
            List<String> signals,
            Taxonomy taxonomy,
            ClassificationPolicy policy) {
        ClassificationProposal proposal = new ClassificationProposal(
                taxonomy.version(), ServiceRequestType.UNCLASSIFIED, reasonCode, List.of(), signals);
        return decision(action, proposal, List.of(), new ArrayList<>(List.of(rationaleCode)), policy);
    }

    private static RoutingDecision decision(
            RouteAction action,
            ClassificationProposal proposal,
            List<String> ruleIds,
            List<RationaleCode> rationale,
            ClassificationPolicy policy) {
        if (!policy.calibrated()) {
            rationale.add(RationaleCode.THRESHOLDS_UNCALIBRATED);
        }
        return new RoutingDecision(
                action,
                proposal,
                ruleIds,
                rationale,
                policy.version(),
                policy.calibrated(),
                action == RouteAction.HUMAN_REVIEW);
    }

    private static void requireDefinitions(Taxonomy taxonomy, ClassificationPolicy policy) {
        requireNonNull(taxonomy, "taxonomy");
        requireNonNull(policy, "policy");
        policy.requireCompatibleWith(taxonomy);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
