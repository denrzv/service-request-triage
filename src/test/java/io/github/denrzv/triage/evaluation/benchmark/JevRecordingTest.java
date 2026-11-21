package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.application.assessment.JevResults;
import io.github.denrzv.triage.application.assessment.TriageQuestionSets;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevClientException;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JevRecordingTest {

    private static final String OK_MESSAGE = "Synthetic recording message about a savings account.";
    private static final String FAILING_MESSAGE = "Synthetic recording message that fails upstream.";

    @Test
    void recordsAndReplaysIdenticalResultsWithoutMessageText(@TempDir Path dir) throws IOException {
        JevClassificationResult live = JevResults.result(JevResults.answers(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.7, 0.8));
        JevRecording.RecordingPort recorder = new JevRecording.RecordingPort(request -> live);
        recorder.classify(request(OK_MESSAGE));

        Path file = dir.resolve("jev-responses.jsonl");
        Files.writeString(file, JevRecording.toJsonl(recorder.lines()), StandardCharsets.UTF_8);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JevClassificationResult replayed =
                new JevRecording.ReplayPort(JevRecording.read(file)).classify(request(OK_MESSAGE));

        assertThat(content).doesNotContain(OK_MESSAGE).contains(JevRecording.sha256(OK_MESSAGE)).endsWith("\n");
        assertThat(replayed).isEqualTo(live);
        assertThat(JevRecording.toJsonl(JevRecording.read(file))).isEqualTo(content);
    }

    @Test
    void recordsFailuresAndReplaysTheSameCategory() {
        assertReplayedFailure(new JevUnavailableException(3, 503, null), JevUnavailableException.class);
        assertReplayedFailure(new JevClientException(401, "auth"), JevClientException.class);
        assertReplayedFailure(new JevProtocolException("bad"), JevProtocolException.class);
    }

    @Test
    void replayFailsLoudlyOnMissingOrMismatchedRecording() {
        JevRecording.RecordingPort recorder = new JevRecording.RecordingPort(request -> JevResults.result(
                JevResults.answers(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.7, 0.8)));
        recorder.classify(request(OK_MESSAGE));
        JevRecording.ReplayPort replay = new JevRecording.ReplayPort(recorder.lines());

        assertThatThrownBy(() -> replay.classify(request("A message that was never recorded here.")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no recording");
        assertThatThrownBy(() -> replay.classify(
                        new JevClassificationRequest(OK_MESSAGE, "CONTACT_CENTER", TriageQuestionSets.v1())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another channel");
    }

    @Test
    void rejectsDuplicateRecordingsAndUnknownFields(@TempDir Path dir) throws IOException {
        JevRecording.RecordingPort recorder = new JevRecording.RecordingPort(failing(new JevProtocolException("x")));
        try {
            recorder.classify(request(FAILING_MESSAGE));
        } catch (JevProtocolException expected) {
            // recorded
        }
        List<JevRecording.Line> twice = List.of(recorder.lines().getFirst(), recorder.lines().getFirst());
        assertThatThrownBy(() -> new JevRecording.ReplayPort(twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");

        Path file = dir.resolve("bad.jsonl");
        Files.writeString(file, JevRecording.toJsonl(recorder.lines()).replace("}", ",\"extra\":1}"));
        assertThatThrownBy(() -> JevRecording.read(file)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lineInvariantsRejectInconsistentOutcomes() {
        assertThatThrownBy(() -> new JevRecording.Line("abc", null, "v", "MAYBE", null, null, null, null, null, null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevRecording.Line("abc", null, "v", JevRecording.OK, "m", 1, 1, 1L, 1, null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertReplayedFailure(RuntimeException failure, Class<? extends RuntimeException> type) {
        JevRecording.RecordingPort recorder = new JevRecording.RecordingPort(failing(failure));
        assertThatThrownBy(() -> recorder.classify(request(FAILING_MESSAGE))).isSameAs(failure);
        JevRecording.Line line = recorder.lines().getFirst();
        assertThat(line.outcome()).isEqualTo(JevRecording.ERROR);
        assertThat(line.error()).isEqualTo(type.getSimpleName());

        assertThatThrownBy(() -> new JevRecording.ReplayPort(recorder.lines()).classify(request(FAILING_MESSAGE)))
                .isInstanceOf(type);
    }

    private static JevClassificationPort failing(RuntimeException failure) {
        return request -> {
            throw failure;
        };
    }

    private static JevClassificationRequest request(String message) {
        return new JevClassificationRequest(message, "WEB_FORM", TriageQuestionSets.v1());
    }
}
