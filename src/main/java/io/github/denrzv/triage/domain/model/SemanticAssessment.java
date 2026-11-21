package io.github.denrzv.triage.domain.model;

/**
 * Semantic understanding of a message (ADR-003): what the customer means, independent of any formal SR type.
 * Noul values are provider-reported probabilities in [0, 1]; an absent judgment must never be defaulted, so every
 * field is mandatory.
 */
public record SemanticAssessment(
        ChoiceJudgment<PrimaryIntent> primaryIntent,
        ChoiceJudgment<ProblemDomain> problemDomain,
        ChoiceJudgment<RequestedOutcome> requestedOutcome,
        double refundRequestedNoul,
        double technicalProblemNoul,
        double multiIntentNoul,
        double taxonomyFitNoul,
        String modelUsed,
        String questionSetVersion) {

    public SemanticAssessment {
        Checks.requireNonNull(primaryIntent, "primaryIntent");
        Checks.requireNonNull(problemDomain, "problemDomain");
        Checks.requireNonNull(requestedOutcome, "requestedOutcome");
        Checks.requireUnit(refundRequestedNoul, "refundRequestedNoul");
        Checks.requireUnit(technicalProblemNoul, "technicalProblemNoul");
        Checks.requireUnit(multiIntentNoul, "multiIntentNoul");
        Checks.requireUnit(taxonomyFitNoul, "taxonomyFitNoul");
        Checks.requireText(modelUsed, "modelUsed");
        Checks.requireText(questionSetVersion, "questionSetVersion");
    }
}
