package io.github.denrzv.triage.domain.model;

import java.util.Set;

/**
 * Match condition of a classification rule. An empty set accepts any value of that dimension; at least one dimension
 * or signal must be constrained so that a rule can never silently become a catch-all.
 */
public record RuleCondition(
        Set<PrimaryIntent> primaryIntents,
        Set<ProblemDomain> problemDomains,
        Set<RequestedOutcome> requestedOutcomes,
        SignalRequirement refundRequested,
        SignalRequirement technicalProblem) {

    public RuleCondition {
        primaryIntents = Checks.enumSet(PrimaryIntent.class, primaryIntents, "primaryIntents");
        problemDomains = Checks.enumSet(ProblemDomain.class, problemDomains, "problemDomains");
        requestedOutcomes = Checks.enumSet(RequestedOutcome.class, requestedOutcomes, "requestedOutcomes");
        Checks.requireNonNull(refundRequested, "refundRequested");
        Checks.requireNonNull(technicalProblem, "technicalProblem");
        if (primaryIntents.isEmpty()
                && problemDomains.isEmpty()
                && requestedOutcomes.isEmpty()
                && refundRequested == SignalRequirement.ANY
                && technicalProblem == SignalRequirement.ANY) {
            throw new IllegalArgumentException("rule condition must constrain at least one dimension or signal");
        }
    }

    public boolean matches(SemanticAssessment assessment, double signalMin) {
        return accepts(primaryIntents, assessment.primaryIntent().selected())
                && accepts(problemDomains, assessment.problemDomain().selected())
                && accepts(requestedOutcomes, assessment.requestedOutcome().selected())
                && refundRequested.accepts(assessment.refundRequestedNoul(), signalMin)
                && technicalProblem.accepts(assessment.technicalProblemNoul(), signalMin);
    }

    private static <E extends Enum<E>> boolean accepts(Set<E> allowed, E value) {
        return allowed.isEmpty() || allowed.contains(value);
    }
}
