package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class JevPropertiesTest {

    private static final String SECRET = "synthetic-secret-key";
    private static final String BASE_URL = "https://api.typesafe.ai";
    private static final String MODEL = "jev-latest";
    private static final Duration SECOND = Duration.ofSeconds(1);

    private static JevProperties with(int maxAttempts, Duration initial, Duration max) {
        return new JevProperties(true, SECRET, BASE_URL, MODEL, SECOND, SECOND, maxAttempts, initial, max);
    }

    @Test
    void bindsDefaultsFromEmptySource() {
        JevProperties bound = new Binder(new MapConfigurationPropertySource())
                .bind("triage.jev", JevProperties.class)
                .orElseGet(() -> new JevProperties(false, "", BASE_URL, MODEL, SECOND, SECOND, 3, SECOND, SECOND));
        assertThat(bound.enabled()).isFalse();
        assertThat(bound.hasApiKey()).isFalse();
        assertThat(bound.baseUrl()).isEqualTo(BASE_URL);
        assertThat(bound.model()).isEqualTo(MODEL);
    }

    @Test
    void bindsConfiguredValues() {
        var source = new MapConfigurationPropertySource(java.util.Map.of(
                "triage.jev.enabled", "true",
                "triage.jev.api-key", SECRET,
                "triage.jev.model", "jev-1.14.0",
                "triage.jev.max-attempts", "5",
                "triage.jev.max-backoff", "4s"));
        JevProperties bound = new Binder(source).bind("triage.jev", JevProperties.class).get();
        assertThat(bound.enabled()).isTrue();
        assertThat(bound.model()).isEqualTo("jev-1.14.0");
        assertThat(bound.maxAttempts()).isEqualTo(5);
        assertThat(bound.maxBackoff()).isEqualTo(Duration.ofSeconds(4));
        assertThat(bound.initialBackoff()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void toStringNeverContainsTheKey() {
        assertThat(with(3, SECOND, SECOND).toString()).doesNotContain(SECRET).contains("apiKeyPresent=true");
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> with(0, SECOND, SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(JevProperties.MAX_ATTEMPTS_LIMIT + 1, SECOND, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(3, Duration.ofSeconds(2), SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(3, Duration.ZERO, SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevProperties(true, SECRET, " ", MODEL, SECOND, SECOND, 3, SECOND, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
