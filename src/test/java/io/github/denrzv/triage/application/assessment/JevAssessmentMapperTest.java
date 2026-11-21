package io.github.denrzv.triage.application.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevAssessmentMapperTest {

    private final JevAssessmentMapper mapper = new JevAssessmentMapper();

    @Test
    void mapsEveryChoiceAndNoulToItsOwnField() {
        SemanticAssessment assessment = mapper.toAssessment(JevResults.result(validAnswers()));

        assertThat(assessment.primaryIntent().selected()).isEqualTo(PrimaryIntent.COMPLAINT);
        assertThat(assessment.primaryIntent().confidence()).isEqualTo(0.8);
        assertThat(assessment.primaryIntent().probabilities())
                .containsEntry(PrimaryIntent.COMPLAINT, 0.8)
                .hasSize(PrimaryIntent.values().length);
        assertThat(assessment.problemDomain().selected()).isEqualTo(ProblemDomain.PAYMENTS);
        assertThat(assessment.requestedOutcome().selected()).isEqualTo(RequestedOutcome.REFUND);
        assertThat(assessment.refundRequestedNoul()).isEqualTo(0.1);
        assertThat(assessment.technicalProblemNoul()).isEqualTo(0.2);
        assertThat(assessment.multiIntentNoul()).isEqualTo(0.3);
        assertThat(assessment.taxonomyFitNoul()).isEqualTo(0.9);
        assertThat(assessment.modelUsed()).isEqualTo(JevResults.MODEL);
        assertThat(assessment.questionSetVersion()).isEqualTo(TriageQuestionSets.V1);
    }

    @Test
    void keepsConfidenceSeparateFromTopProbability() {
        Map<String, JevAnswer> answers = validAnswers();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("complaint", 0.55);
        probabilities.put("inquiry", 0.40);
        probabilities.put("action_request", 0.03);
        probabilities.put("incident_report", 0.02);
        answers.put(TriageQuestionSets.PRIMARY_INTENT, new ChoiceAnswer("complaint", 0.21, probabilities));

        SemanticAssessment assessment = mapper.toAssessment(JevResults.result(answers));

        assertThat(assessment.primaryIntent().confidence()).isEqualTo(0.21);
        assertThat(assessment.primaryIntent().probabilities()).containsEntry(PrimaryIntent.COMPLAINT, 0.55);
        assertThat(assessment.primaryIntent().margin()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void rejectsMissingAnswer() {
        Map<String, JevAnswer> answers = validAnswers();
        answers.remove(TriageQuestionSets.TAXONOMY_FIT);

        assertProtocolError(answers, "taxonomy_fit");
    }

    @Test
    void rejectsWrongAnswerType() {
        Map<String, JevAnswer> answers = validAnswers();
        answers.put(TriageQuestionSets.PROBLEM_DOMAIN, new NoulAnswer(0.5));
        assertProtocolError(answers, "problem_domain");

        Map<String, JevAnswer> second = validAnswers();
        second.put(TriageQuestionSets.MULTI_INTENT, JevResults.choice(PrimaryIntent.INQUIRY, PrimaryIntent.class, 0.9));
        assertProtocolError(second, "multi_intent");
    }

    @Test
    void rejectsUnknownOrMissingChoiceKeys() {
        Map<String, JevAnswer> unknown = validAnswers();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("complaint", 0.7);
        probabilities.put("inquiry", 0.1);
        probabilities.put("action_request", 0.1);
        probabilities.put("praise", 0.1);
        unknown.put(TriageQuestionSets.PRIMARY_INTENT, new ChoiceAnswer("complaint", 0.7, probabilities));
        assertProtocolError(unknown, "primary_intent");

        Map<String, JevAnswer> missing = validAnswers();
        missing.put(TriageQuestionSets.PRIMARY_INTENT, new ChoiceAnswer("complaint", 0.7, Map.of("complaint", 0.7)));
        assertProtocolError(missing, "primary_intent");
    }

    @Test
    void rejectsOtherQuestionSetVersion() {
        JevClassificationResult result = new JevClassificationResult(
                validAnswers(), JevResults.MODEL, new JevUsage(1, 1), "smoke-1", Duration.ZERO, 1);

        assertThatThrownBy(() -> mapper.toAssessment(result))
                .isInstanceOf(JevProtocolException.class)
                .hasMessageContaining("smoke-1");
        assertThatThrownBy(() -> mapper.toAssessment(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, JevAnswer> validAnswers() {
        return JevResults.answers(PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, RequestedOutcome.REFUND, 0.8, 0.9);
    }

    private void assertProtocolError(Map<String, JevAnswer> answers, String question) {
        assertThatThrownBy(() -> mapper.toAssessment(JevResults.result(answers)))
                .isInstanceOf(JevProtocolException.class)
                .hasMessageContaining(question);
    }
}
