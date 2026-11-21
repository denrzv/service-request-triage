package io.github.denrzv.triage.domain.model;

import java.time.Duration;
import java.util.Map;

/**
 * Typed answers keyed by question name plus call metadata. {@code latency} spans all attempts;
 * {@code attempts} is the number of HTTP attempts made (at least 1).
 */
public record JevClassificationResult(
        Map<String, JevAnswer> answers,
        String resolvedModel,
        JevUsage usage,
        String questionSetVersion,
        Duration latency,
        int attempts) {

    public JevClassificationResult {
        answers = Checks.orderedCopy(answers, "answers");
        Checks.requireSize(answers.size(), 1, Integer.MAX_VALUE, "answers");
        Checks.requireText(resolvedModel, "resolvedModel");
        Checks.requireText(questionSetVersion, "questionSetVersion");
        if (usage == null || latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("usage and a non-negative latency are required");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
    }
}
