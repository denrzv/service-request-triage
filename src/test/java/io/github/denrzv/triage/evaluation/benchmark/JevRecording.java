package io.github.denrzv.triage.evaluation.benchmark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.ScoreAnswer;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevClientException;
import io.github.denrzv.triage.domain.port.JevException;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Record/replay of Jev calls for offline benchmarking (ADR FEATURE-005 decision 4). A recording line holds the
 * SHA-256 of the message, the channel, call metadata and the typed answers, or the failure class. It never holds
 * message text, labels or case IDs, so a recording can be committed next to the report. Replay is keyed by message
 * hash because the port only sees the request.
 */
public final class JevRecording {

    static final String OK = "OK";
    static final String ERROR = "ERROR";
    private static final String CHOICE = "choice";
    private static final String NOUL = "noul";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** One recorded call. Exactly one of {@code answers} (outcome OK) and {@code error} (outcome ERROR) is set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String messageSha256,
            String channel,
            String questionSetVersion,
            String outcome,
            String resolvedModel,
            Integer inputTokens,
            Integer outputTokens,
            Long latencyMs,
            Integer attempts,
            Map<String, Answer> answers,
            String error) {

        public Line {
            requireText(messageSha256, "messageSha256");
            requireText(questionSetVersion, "questionSetVersion");
            if (OK.equals(outcome)) {
                requireText(resolvedModel, "resolvedModel");
                if (answers == null || answers.isEmpty() || inputTokens == null || outputTokens == null
                        || latencyMs == null || attempts == null || error != null) {
                    throw new IllegalArgumentException("OK line requires answers, usage, latency and attempts only");
                }
                answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
            } else if (ERROR.equals(outcome)) {
                requireText(error, "error");
                if (answers != null) {
                    throw new IllegalArgumentException("ERROR line must not carry answers");
                }
            } else {
                throw new IllegalArgumentException("outcome must be OK or ERROR");
            }
        }
    }

    /** Wire form of a Choice or Noul answer, mirroring the System One response fields. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Answer(String type, String choice, Double confidence, Map<String, Double> probabilities, Double noul) {}

    private JevRecording() {}

    /** Lower-case hex SHA-256 of the UTF-8 message bytes. */
    public static String sha256(String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static List<Line> read(Path file) {
        List<Line> lines = new ArrayList<>();
        try {
            for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!text.isBlank()) {
                    lines.add(MAPPER.readValue(text, Line.class));
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid recording " + file.getFileName() + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return List.copyOf(lines);
    }

    /** Serializes lines as JSONL with a final newline; output is deterministic for equal input. */
    public static String toJsonl(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        try {
            for (Line line : lines) {
                out.append(MAPPER.writeValueAsString(line)).append('\n');
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize recording", e);
        }
        return out.toString();
    }

    /** Decorator that forwards to a live port and records every call, successful or failed. */
    public static final class RecordingPort implements JevClassificationPort {

        private final JevClassificationPort delegate;
        private final List<Line> lines = new ArrayList<>();

        public RecordingPort(JevClassificationPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public JevClassificationResult classify(JevClassificationRequest request) {
            String hash = sha256(request.message());
            String version = request.questionSet().version();
            try {
                JevClassificationResult result = delegate.classify(request);
                lines.add(new Line(hash, request.channel(), version, OK, result.resolvedModel(),
                        result.usage().inputTokens(), result.usage().outputTokens(), result.latency().toMillis(),
                        result.attempts(), toWire(result.answers()), null));
                return result;
            } catch (JevException e) {
                lines.add(new Line(hash, request.channel(), version, ERROR, null, null, null, null, null, null,
                        e.getClass().getSimpleName()));
                throw e;
            }
        }

        public List<Line> lines() {
            return List.copyOf(lines);
        }
    }

    /** Offline port serving recorded answers by message hash; never calls the network. */
    public static final class ReplayPort implements JevClassificationPort {

        private final Map<String, Line> byHash = new LinkedHashMap<>();

        public ReplayPort(List<Line> lines) {
            for (Line line : lines) {
                if (byHash.put(line.messageSha256(), line) != null) {
                    throw new IllegalStateException("duplicate recording for message sha256 " + line.messageSha256());
                }
            }
        }

        @Override
        public JevClassificationResult classify(JevClassificationRequest request) {
            String hash = sha256(request.message());
            Line line = byHash.get(hash);
            if (line == null) {
                throw new IllegalStateException("no recording for message sha256 " + hash);
            }
            if (!Objects.equals(line.channel(), request.channel())
                    || !line.questionSetVersion().equals(request.questionSet().version())) {
                throw new IllegalStateException("recording for message sha256 " + hash
                        + " was made with another channel or question set");
            }
            if (ERROR.equals(line.outcome())) {
                throw replayedFailure(line.error());
            }
            return new JevClassificationResult(fromWire(line.answers()), line.resolvedModel(),
                    new JevUsage(line.inputTokens(), line.outputTokens()), line.questionSetVersion(),
                    Duration.ofMillis(line.latencyMs()), line.attempts());
        }
    }

    private static JevException replayedFailure(String error) {
        return switch (error) {
            case "JevClientException" -> new JevClientException(0, "replayed client failure");
            case "JevProtocolException" -> new JevProtocolException("replayed protocol failure");
            case "JevUnavailableException" -> new JevUnavailableException(0, 0, null);
            default -> throw new IllegalStateException("unknown recorded failure " + error);
        };
    }

    private static Map<String, Answer> toWire(Map<String, JevAnswer> answers) {
        Map<String, Answer> wire = new LinkedHashMap<>();
        answers.forEach((name, answer) -> wire.put(name, switch (answer) {
            case ChoiceAnswer choice -> new Answer(CHOICE, choice.selected(), choice.confidence(),
                    new LinkedHashMap<>(choice.probabilities()), null);
            case NoulAnswer noul -> new Answer(NOUL, null, null, null, noul.probability());
            case ScoreAnswer ignored -> throw new IllegalArgumentException("score answers are not recorded: " + name);
        }));
        return wire;
    }

    private static Map<String, JevAnswer> fromWire(Map<String, Answer> wire) {
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        wire.forEach((name, answer) -> answers.put(name, switch (answer.type()) {
            case CHOICE -> new ChoiceAnswer(answer.choice(), requireValue(answer.confidence(), name),
                    Objects.requireNonNull(answer.probabilities(), name));
            case NOUL -> new NoulAnswer(requireValue(answer.noul(), name));
            default -> throw new IllegalStateException("unsupported recorded answer type for " + name);
        }));
        return answers;
    }

    private static double requireValue(Double value, String name) {
        if (value == null) {
            throw new IllegalStateException("recorded answer " + name + " is incomplete");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
