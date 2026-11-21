package io.github.denrzv.triage.infrastructure.typesafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Jev adapter only when {@code triage.jev.enabled=true}. With the flag off (the default) no bean and
 * no key are needed. Enabled without a key fails startup with a clear message instead of failing on first use.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JevProperties.class)
public class JevConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "triage.jev", name = "enabled", havingValue = "true")
    JevClassificationPort jevClassificationPort(JevProperties properties, ObjectMapper objectMapper) {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException("triage.jev.enabled=true requires the JEV_API_KEY environment variable");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new JevHttpClassificationAdapter(properties, objectMapper, client, Sleeper.THREAD, System::nanoTime);
    }
}
