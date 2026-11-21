package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JevConfigurationTest {

    private static final String SECRET = "synthetic-secret-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JevConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledByDefaultCreatesNoBeanAndNeedsNoKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JevClassificationPort.class);
        });
    }

    @Test
    void enabledWithKeyCreatesTheAdapter() {
        runner.withPropertyValues("triage.jev.enabled=true", "triage.jev.api-key=" + SECRET)
                .run(context -> assertThat(context).hasSingleBean(JevClassificationPort.class));
    }

    @Test
    void enabledWithoutKeyFailsStartupWithoutLeakingAnything() {
        runner.withPropertyValues("triage.jev.enabled=true", "triage.jev.api-key=").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).hasMessageContaining("JEV_API_KEY").hasMessageNotContaining(SECRET);
        });
    }
}
