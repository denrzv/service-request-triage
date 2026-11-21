package io.github.denrzv.triage.domain.model;

/** Provider-reported token usage for one successful call. */
public record JevUsage(int inputTokens, int outputTokens) {

    public JevUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
