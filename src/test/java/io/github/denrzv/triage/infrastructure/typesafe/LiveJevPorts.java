package io.github.denrzv.triage.infrastructure.typesafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Test-scope factory exposing the package-private HTTP adapter to opt-in live harnesses (benchmark, smoke). Uses the
 * same defaults as {@code application.yml}: connect 5 s, request 30 s, 3 attempts, 200 ms to 2 s backoff.
 */
public final class LiveJevPorts {

    public static final String BASE_URL = "https://api.typesafe.ai";

    private LiveJevPorts() {}

    public static JevClassificationPort create(String apiKey, String model) {
        JevProperties properties = new JevProperties(
                true,
                apiKey,
                BASE_URL,
                model,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(200),
                Duration.ofSeconds(2));
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        return new JevHttpClassificationAdapter(properties, new ObjectMapper(), client, Sleeper.THREAD, System::nanoTime);
    }
}
