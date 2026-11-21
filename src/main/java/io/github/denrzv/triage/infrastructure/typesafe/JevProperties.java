package io.github.denrzv.triage.infrastructure.typesafe;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Jev adapter settings bound from {@code triage.jev.*}. The API key comes from the {@code JEV_API_KEY}
 * environment variable via {@code application.yml}; {@link #toString()} never prints it.
 */
@ConfigurationProperties("triage.jev")
public record JevProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.typesafe.ai") String baseUrl,
        @DefaultValue("jev-latest") String model,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration initialBackoff,
        @DefaultValue("2s") Duration maxBackoff) {

    public static final int MAX_ATTEMPTS_LIMIT = 10;

    public JevProperties {
        requireText(baseUrl, "baseUrl");
        requireText(model, "model");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("maxAttempts must be within [1, " + MAX_ATTEMPTS_LIMIT + "]");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be smaller than initialBackoff");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "JevProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", model=" + model
                + ", maxAttempts=" + maxAttempts + ", apiKeyPresent=" + hasApiKey() + "]";
    }
}
