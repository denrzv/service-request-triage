package io.github.denrzv.triage.application.assessment;

import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds synthetic {@value TriageQuestionSets#V1} results for tests; values are arbitrary, never goldset data. */
public final class JevResults {

    public static final String MODEL = "jev-test-1";

    private JevResults() {}

    /** Valid result whose selected values carry {@code top} probability and the rest share the remainder. */
    public static Map<String, JevAnswer> answers(
            PrimaryIntent intent, ProblemDomain domain, RequestedOutcome outcome, double top, double taxonomyFit) {
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        answers.put(TriageQuestionSets.PRIMARY_INTENT, choice(intent, PrimaryIntent.class, top));
        answers.put(TriageQuestionSets.PROBLEM_DOMAIN, choice(domain, ProblemDomain.class, top));
        answers.put(TriageQuestionSets.REQUESTED_OUTCOME, choice(outcome, RequestedOutcome.class, top));
        answers.put(TriageQuestionSets.REFUND_REQUESTED, new NoulAnswer(0.1));
        answers.put(TriageQuestionSets.TECHNICAL_PROBLEM, new NoulAnswer(0.2));
        answers.put(TriageQuestionSets.MULTI_INTENT, new NoulAnswer(0.3));
        answers.put(TriageQuestionSets.TAXONOMY_FIT, new NoulAnswer(taxonomyFit));
        return answers;
    }

    public static JevClassificationResult result(Map<String, JevAnswer> answers) {
        return new JevClassificationResult(
                answers, MODEL, new JevUsage(500, 70), TriageQuestionSets.V1, Duration.ofMillis(120), 1);
    }

    public static <E extends Enum<E>> ChoiceAnswer choice(E selected, Class<E> type, double top) {
        E[] constants = type.getEnumConstants();
        double rest = (1.0 - top) / (constants.length - 1);
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (E constant : constants) {
            probabilities.put(TriageQuestionSets.criterionKey(constant), constant == selected ? top : rest);
        }
        return new ChoiceAnswer(TriageQuestionSets.criterionKey(selected), top, probabilities);
    }
}
