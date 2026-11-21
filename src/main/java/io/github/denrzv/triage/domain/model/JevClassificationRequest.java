package io.github.denrzv.triage.domain.model;

/**
 * Input to one Jev call. {@code message} is untrusted synthetic English text; {@code channel} is optional context.
 * {@link #toString()} deliberately omits the message so it cannot leak into logs.
 */
public record JevClassificationRequest(String message, String channel, JevQuestionSet questionSet) {

    public JevClassificationRequest {
        Checks.requireText(message, "message");
        if (channel != null) {
            Checks.requireText(channel, "channel");
        }
        if (questionSet == null) {
            throw new IllegalArgumentException("questionSet must not be null");
        }
    }

    @Override
    public String toString() {
        return "JevClassificationRequest[questionSetVersion=" + questionSet.version() + "]";
    }
}
