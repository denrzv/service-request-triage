package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.port.JevClientException;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import io.github.denrzv.triage.infrastructure.typesafe.FakeJevServer.Reply;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JevHttpClassificationAdapterTest {

    private static final String API_KEY = "synthetic-test-key-123";
    private static final String LEAKED = "echoed-customer-text-should-not-surface";
    private static final String RETRY_AFTER = "Retry-After";
    private static final String RESOLVED_MODEL = "jev-1.14.0";
    private static final Duration INITIAL = Duration.ofMillis(200);
    private static final Duration CAP = Duration.ofSeconds(2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Duration> sleeps = new ArrayList<>();

    private JevHttpClassificationAdapter adapter(String baseUrl, int maxAttempts, Duration requestTimeout, Sleeper sleeper) {
        var properties = new JevProperties(
                true, API_KEY, baseUrl, "jev-latest", Duration.ofSeconds(2), requestTimeout, maxAttempts, INITIAL, CAP);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new JevHttpClassificationAdapter(properties, MAPPER, client, sleeper, System::nanoTime);
    }

    private JevHttpClassificationAdapter adapter(String baseUrl, int maxAttempts) {
        return adapter(baseUrl, maxAttempts, Duration.ofSeconds(5), sleeps::add);
    }

    private static String success() {
        return JevTestData.fixture("success-multi-type.json");
    }

    @Test
    void sendsDocumentedRequestAndParsesTypedResult() throws Exception {
        try (var server = new FakeJevServer(Reply.of(200, success()))) {
            JevClassificationResult result = adapter(server.baseUrl(), 3).classify(JevTestData.request());

            var recorded = server.requests().getFirst();
            assertThat(recorded.method()).isEqualTo("POST");
            assertThat(recorded.path()).isEqualTo("/v1/systemone");
            assertThat(recorded.header("Authorization")).isEqualTo("Bearer " + API_KEY);
            assertThat(recorded.header("Content-Type")).startsWith("application/json");
            JsonNode body = MAPPER.readTree(recorded.body());
            assertThat(body.get("model").asText()).isEqualTo("jev-latest");
            assertThat(body.at("/state/customerMessage").asText()).isEqualTo(JevTestData.MESSAGE);
            assertThat(body.get("questions").size()).isEqualTo(3);

            assertThat(result.resolvedModel()).isEqualTo(RESOLVED_MODEL);
            assertThat(result.usage().inputTokens()).isEqualTo(549);
            assertThat(result.usage().outputTokens()).isEqualTo(63);
            assertThat(result.questionSetVersion()).isEqualTo(JevTestData.QUESTION_SET_VERSION);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(result.answers().get(JevTestData.INTENT)).isInstanceOf(ChoiceAnswer.class);
            assertThat(result.answers().get(JevTestData.REFUND)).isEqualTo(new NoulAnswer(0.97));
            assertThat(sleeps).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void terminalStatusesAreNotRetriedAndLeakNothing(int status) throws Exception {
        String body = status == 422 ? JevTestData.fixture("error-422.json") : JevTestData.fixture("error-400.json");
        try (var server = new FakeJevServer(Reply.of(status, body))) {
            assertThatThrownBy(() -> adapter(server.baseUrl(), 3).classify(JevTestData.request()))
                    .isInstanceOfSatisfying(JevClientException.class, e -> assertThat(e.status()).isEqualTo(status))
                    .hasMessageNotContaining(API_KEY)
                    .hasMessageNotContaining(LEAKED)
                    .hasMessageNotContaining(JevTestData.MESSAGE);
            assertThat(server.requests()).hasSize(1);
            assertThat(sleeps).isEmpty();
        }
    }

    @Test
    void unprocessableReportsOnlyLocationAndCode() throws Exception {
        try (var server = new FakeJevServer(Reply.of(422, JevTestData.fixture("error-422.json")))) {
            assertThatThrownBy(() -> adapter(server.baseUrl(), 3).classify(JevTestData.request()))
                    .hasMessageContaining("type=missing")
                    .hasMessageContaining("body");
        }
    }

    @Test
    void retriesTooManyRequestsThenSucceeds() throws Exception {
        try (var server = new FakeJevServer(Reply.of(429, "{}"), Reply.of(200, success()))) {
            JevClassificationResult result = adapter(server.baseUrl(), 3).classify(JevTestData.request());
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(server.requests()).hasSize(2);
            assertThat(sleeps).containsExactly(INITIAL);
        }
    }

    @Test
    void exhaustedServerErrorsGiveBoundedBackoffAndUnavailable() throws Exception {
        try (var server = new FakeJevServer(Reply.of(503, "{}"))) {
            assertThatThrownBy(() -> adapter(server.baseUrl(), 3).classify(JevTestData.request()))
                    .isInstanceOfSatisfying(JevUnavailableException.class, e -> {
                        assertThat(e.attempts()).isEqualTo(3);
                        assertThat(e.lastStatus()).isEqualTo(503);
                    })
                    .hasMessageNotContaining(API_KEY);
            assertThat(server.requests()).hasSize(3);
            assertThat(sleeps).containsExactly(INITIAL, INITIAL.multipliedBy(2));
            assertThat(sleeps).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(CAP));
        }
    }

    @Test
    void retryAfterIsHonouredAndCapped() throws Exception {
        try (var server = new FakeJevServer(
                Reply.withHeader(429, "{}", RETRY_AFTER, "1"),
                Reply.withHeader(429, "{}", RETRY_AFTER, "60"),
                Reply.of(200, success()))) {
            adapter(server.baseUrl(), 3).classify(JevTestData.request());
            assertThat(sleeps).containsExactly(Duration.ofSeconds(1), CAP);
        }
    }

    @Test
    void invalidRetryAfterFallsBackToExponentialBackoff() throws Exception {
        try (var server = new FakeJevServer(
                Reply.withHeader(503, "{}", RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT"), Reply.of(200, success()))) {
            adapter(server.baseUrl(), 2).classify(JevTestData.request());
            assertThat(sleeps).containsExactly(INITIAL);
        }
    }

    @Test
    void readTimeoutIsRetried() throws Exception {
        try (var server = new FakeJevServer(Reply.delayed(200, success(), 1500), Reply.of(200, success()))) {
            JevClassificationResult result =
                    adapter(server.baseUrl(), 2, Duration.ofMillis(300), sleeps::add).classify(JevTestData.request());
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(sleeps).hasSize(1);
        }
    }

    @Test
    void connectionFailureEndsAsUnavailable() throws Exception {
        String deadUrl;
        try (var server = new FakeJevServer(Reply.of(200, success()))) {
            deadUrl = server.baseUrl();
        }
        assertThatThrownBy(() -> adapter(deadUrl, 2).classify(JevTestData.request()))
                .isInstanceOfSatisfying(JevUnavailableException.class, e -> {
                    assertThat(e.attempts()).isEqualTo(2);
                    assertThat(e.lastStatus()).isZero();
                    assertThat(e.getCause()).isInstanceOf(IOException.class);
                });
    }

    @Test
    void malformedAnswerIsAProtocolErrorAndNotRetried() throws Exception {
        String broken = success().replace("\"noul\": 0.97", "\"noul\": \"" + LEAKED + "\"");
        try (var server = new FakeJevServer(Reply.of(200, broken))) {
            assertThatThrownBy(() -> adapter(server.baseUrl(), 3).classify(JevTestData.request()))
                    .isInstanceOf(JevProtocolException.class)
                    .hasMessageNotContaining(LEAKED);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void nonJsonSuccessBodyIsAProtocolError() throws Exception {
        try (var server = new FakeJevServer(Reply.of(200, "<html>oops</html>"))) {
            assertThatThrownBy(() -> adapter(server.baseUrl(), 3).classify(JevTestData.request()))
                    .isInstanceOf(JevProtocolException.class);
        }
    }

    @Test
    void interruptedBackoffRestoresFlagAndFailsAsUnavailable() throws Exception {
        Sleeper interrupting = duration -> {
            throw new InterruptedException("test");
        };
        try (var server = new FakeJevServer(Reply.of(503, "{}"))) {
            try {
                assertThatThrownBy(() ->
                                adapter(server.baseUrl(), 3, Duration.ofSeconds(5), interrupting)
                                        .classify(JevTestData.request()))
                        .isInstanceOf(JevUnavailableException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void backoffDoublesUntilTheCap() {
        var adapter = adapter("http://localhost:1", 6);
        assertThat(adapter.backoff(2, null)).isEqualTo(INITIAL);
        assertThat(adapter.backoff(3, null)).isEqualTo(Duration.ofMillis(400));
        assertThat(adapter.backoff(5, null)).isEqualTo(Duration.ofMillis(1600));
        assertThat(adapter.backoff(6, null)).isEqualTo(CAP);
        assertThat(adapter.backoff(2, Duration.ofSeconds(9))).isEqualTo(CAP);
    }

    @Test
    void rejectsNullRequestBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> adapter("http://localhost:1", 3).classify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
