package io.github.denrzv.triage.application.assessment;

import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps typed answers of the {@value TriageQuestionSets#V1} question set to a {@link SemanticAssessment}. The mapping is
 * strict: a different question-set version, a missing or mistyped answer, or Choice probabilities whose keys are not
 * exactly the taxonomy enum constants raise {@link JevProtocolException}, so callers take the safe
 * {@code MODEL_UNAVAILABLE} path instead of defaulting a value. Messages name questions only, never customer text.
 */
public final class JevAssessmentMapper {

    public SemanticAssessment toAssessment(JevClassificationResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (!TriageQuestionSets.V1.equals(result.questionSetVersion())) {
            throw new JevProtocolException("unsupported question set " + result.questionSetVersion());
        }
        Map<String, JevAnswer> answers = result.answers();
        try {
            return new SemanticAssessment(
                    choice(answers, TriageQuestionSets.PRIMARY_INTENT, PrimaryIntent.class),
                    choice(answers, TriageQuestionSets.PROBLEM_DOMAIN, ProblemDomain.class),
                    choice(answers, TriageQuestionSets.REQUESTED_OUTCOME, RequestedOutcome.class),
                    noul(answers, TriageQuestionSets.REFUND_REQUESTED),
                    noul(answers, TriageQuestionSets.TECHNICAL_PROBLEM),
                    noul(answers, TriageQuestionSets.MULTI_INTENT),
                    noul(answers, TriageQuestionSets.TAXONOMY_FIT),
                    result.resolvedModel(),
                    result.questionSetVersion());
        } catch (IllegalArgumentException e) {
            throw new JevProtocolException("answers violate the assessment contract: " + e.getMessage(), e);
        }
    }

    private static <E extends Enum<E>> ChoiceJudgment<E> choice(
            Map<String, JevAnswer> answers, String question, Class<E> type) {
        if (!(answers.get(question) instanceof ChoiceAnswer answer)) {
            throw new JevProtocolException("missing or non-choice answer for " + question);
        }
        Map<String, E> byKey = new HashMap<>();
        for (E constant : type.getEnumConstants()) {
            byKey.put(TriageQuestionSets.criterionKey(constant), constant);
        }
        if (!answer.probabilities().keySet().equals(byKey.keySet())) {
            throw new JevProtocolException("choice keys of " + question + " do not match the question set");
        }
        Map<E, Double> probabilities = new EnumMap<>(type);
        answer.probabilities().forEach((key, value) -> probabilities.put(byKey.get(key), value));
        return new ChoiceJudgment<>(byKey.get(answer.selected()), answer.confidence(), probabilities);
    }

    private static double noul(Map<String, JevAnswer> answers, String question) {
        if (!(answers.get(question) instanceof NoulAnswer answer)) {
            throw new JevProtocolException("missing or non-noul answer for " + question);
        }
        return answer.probability();
    }
}
