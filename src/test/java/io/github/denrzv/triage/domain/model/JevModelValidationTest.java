package io.github.denrzv.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JevModelValidationTest {

    private static final String INSTRUCTIONS = "Pick one";
    private static final String SECRET_TEXT = "my-synthetic-secret-phrase";
    private static final String VERSION = "qs-1";

    private static JevQuestionSet questionSet() {
        return new JevQuestionSet(VERSION, Map.of("tone", new NoulQuestion(INSTRUCTIONS)));
    }

    @Test
    void choiceQuestionRejectsEmptyAndOversizedCriteria() {
        assertThatThrownBy(() -> new ChoiceQuestion(INSTRUCTIONS, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> tooMany = IntStream.rangeClosed(0, ChoiceQuestion.MAX_CRITERIA)
                .boxed()
                .collect(Collectors.toMap(i -> "c" + i, i -> "d"));
        assertThatThrownBy(() -> new ChoiceQuestion(INSTRUCTIONS, tooMany))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreQuestionRequiresOneToTenLevels() {
        assertThatThrownBy(() -> new ScoreQuestion(INSTRUCTIONS, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "l" + i).toList();
        assertThatThrownBy(() -> new ScoreQuestion(INSTRUCTIONS, eleven)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ScoreQuestion(INSTRUCTIONS, List.of("low", "high")).levels()).hasSize(2);
    }

    @Test
    void choiceQuestionKeepsCriteriaOrderAndIsImmutable() {
        Map<String, String> source = new java.util.LinkedHashMap<>();
        source.put("b", "second");
        source.put("a", "first");
        ChoiceQuestion question = new ChoiceQuestion(INSTRUCTIONS, source);
        source.put("c", "late");
        assertThat(question.criteria().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> question.criteria().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void questionSetNeedsVersionAndQuestions() {
        assertThatThrownBy(() -> new JevQuestionSet(" ", Map.of("q", new NoulQuestion(INSTRUCTIONS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevQuestionSet(VERSION, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRejectsBlankMessageAndNeverPrintsIt() {
        assertThatThrownBy(() -> new JevClassificationRequest(" ", null, questionSet()))
                .isInstanceOf(IllegalArgumentException.class);
        JevClassificationRequest request = new JevClassificationRequest(SECRET_TEXT, "WEB_FORM", questionSet());
        assertThat(request.toString()).doesNotContain(SECRET_TEXT).contains(VERSION);
    }

    @Test
    void validationMessagesDoNotEchoMessage() {
        assertThatThrownBy(() -> new JevClassificationRequest(SECRET_TEXT, " ", questionSet()))
                .hasMessageNotContaining(SECRET_TEXT);
    }

    @Test
    void choiceAnswerValidatesRangesAndSelection() {
        assertThat(new ChoiceAnswer("a", 0.7, Map.of("a", 0.7, "b", 0.3)).selected()).isEqualTo("a");
        assertThatThrownBy(() -> new ChoiceAnswer("a", 1.2, Map.of("a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChoiceAnswer("z", 0.5, Map.of("a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Double> nan = new HashMap<>();
        nan.put("a", Double.NaN);
        assertThatThrownBy(() -> new ChoiceAnswer("a", 0.5, nan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noulAnswerHasOnlyProbability() {
        assertThat(new NoulAnswer(0.98).probability()).isEqualTo(0.98);
        assertThat(NoulAnswer.class.getRecordComponents()).hasSize(1);
        assertThatThrownBy(() -> new NoulAnswer(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreAnswerKeepsFractionalScoreWithinLevelRange() {
        assertThat(new ScoreAnswer(1.7, 0.55, List.of(0.1, 0.1, 0.8)).score()).isEqualTo(1.7);
        assertThatThrownBy(() -> new ScoreAnswer(2.5, 0.5, List.of(0.1, 0.1, 0.8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreAnswer(0.0, 0.5, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultValidatesMetadata() {
        Map<String, JevAnswer> answers = Map.of("q", new NoulAnswer(0.5));
        JevUsage usage = new JevUsage(10, 2);
        assertThat(new JevClassificationResult(answers, "jev-1", usage, VERSION, Duration.ofMillis(5), 1).attempts())
                .isEqualTo(1);
        assertThatThrownBy(() -> new JevClassificationResult(answers, "jev-1", usage, VERSION, Duration.ofMillis(5), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevClassificationResult(answers, " ", usage, VERSION, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevUsage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
