package io.github.denrzv.triage.infrastructure.typesafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevClientException;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP adapter for TypeSafe System One. Contains no routing, taxonomy or task logic.
 *
 * <p>HTTP 2xx is parsed strictly; 429, 5xx, I/O errors and timeouts are retried with bounded exponential backoff;
 * every other status is terminal. Logs and exceptions carry only metadata (status, attempt, latency, model,
 * token counts), never the API key, customer text or response bodies.
 */
final class JevHttpClassificationAdapter implements JevClassificationPort {

    static final String PATH = "/v1/systemone";

    private static final Logger LOG = LoggerFactory.getLogger(JevHttpClassificationAdapter.class);
    private static final int STATUS_OK_MIN = 200;
    private static final int STATUS_OK_MAX = 299;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR_MIN = 500;
    private static final int STATUS_UNPROCESSABLE = 422;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int NO_STATUS = 0;
    private static final String RETRY_AFTER = "Retry-After";
    private static final String TOKEN_PREFIX = "Bearer ";

    /** Result of one HTTP attempt that did not terminate the call. */
    private sealed interface Outcome permits Success, Retryable {}

    private record Success(String body) implements Outcome {}

    private record Retryable(int status, Throwable cause, Duration retryAfter) implements Outcome {}

    private final JevProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Sleeper sleeper;
    private final LongSupplier nanoClock;
    private final JevRequestMapper requestMapper;
    private final JevResponseParser responseParser;
    private final URI endpoint;

    JevHttpClassificationAdapter(
            JevProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Sleeper sleeper,
            LongSupplier nanoClock) {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException("A Jev API key is required");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.sleeper = sleeper;
        this.nanoClock = nanoClock;
        this.requestMapper = new JevRequestMapper(objectMapper);
        this.responseParser = new JevResponseParser(objectMapper);
        this.endpoint = URI.create(stripTrailingSlash(properties.baseUrl()) + PATH);
    }

    @Override
    public JevClassificationResult classify(JevClassificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        HttpRequest httpRequest = buildHttpRequest(request);
        long start = nanoClock.getAsLong();
        Retryable last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (last != null) {
                pause(attempt, last);
            }
            Outcome outcome = execute(httpRequest, attempt, start);
            if (outcome instanceof Success success) {
                return toResult(success.body(), request, attempt, start);
            }
            last = (Retryable) outcome;
        }
        throw new JevUnavailableException(properties.maxAttempts(), last.status(), last.cause());
    }

    private HttpRequest buildHttpRequest(JevClassificationRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(requestMapper.toBody(properties.model(), request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the Jev request", e);
        }
        return HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Authorization", TOKEN_PREFIX + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private Outcome execute(HttpRequest httpRequest, int attempt, long start) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            logAttempt(attempt, NO_STATUS, start, e.getClass().getSimpleName());
            return new Retryable(NO_STATUS, e, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevUnavailableException(attempt, NO_STATUS, e);
        }
        int status = response.statusCode();
        logAttempt(attempt, status, start, "response");
        if (status >= STATUS_OK_MIN && status <= STATUS_OK_MAX) {
            return new Success(response.body());
        }
        if (status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR_MIN) {
            return new Retryable(status, null, retryAfter(response));
        }
        throw new JevClientException(status, safeDetail(status, response.body()));
    }

    private JevClassificationResult toResult(String body, JevClassificationRequest request, int attempts, long start) {
        JevResponseParser.Parsed parsed = responseParser.parse(body, request.questionSet());
        Duration latency = elapsed(start);
        LOG.info(
                "jev call succeeded model={} attempts={} latencyMs={} inputTokens={} outputTokens={}",
                parsed.model(),
                attempts,
                latency.toMillis(),
                parsed.usage().inputTokens(),
                parsed.usage().outputTokens());
        return new JevClassificationResult(
                parsed.answers(),
                parsed.model(),
                parsed.usage(),
                request.questionSet().version(),
                latency,
                attempts);
    }

    private void pause(int nextAttempt, Retryable previous) {
        Duration delay = backoff(nextAttempt, previous.retryAfter());
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevUnavailableException(nextAttempt - 1, previous.status(), e);
        }
    }

    /** Delay before attempt {@code nextAttempt} (2 or later): Retry-After if sent, else exponential, always capped. */
    Duration backoff(int nextAttempt, Duration retryAfter) {
        Duration cap = properties.maxBackoff();
        if (retryAfter != null) {
            return retryAfter.compareTo(cap) > 0 ? cap : retryAfter;
        }
        Duration delay = properties.initialBackoff();
        for (int i = 2; i < nextAttempt && delay.compareTo(cap) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue(RETRY_AFTER).map(JevHttpClassificationAdapter::parseSeconds).orElse(null);
    }

    private static Duration parseSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds a diagnostic that cannot echo customer text: 422 reports only field locations and error codes. */
    private String safeDetail(int status, String body) {
        if (status == STATUS_UNAUTHORIZED || status == STATUS_FORBIDDEN) {
            return "authentication or authorization failed";
        }
        if (status == STATUS_UNPROCESSABLE) {
            return validationDetail(body);
        }
        return "request rejected (details withheld)";
    }

    private String validationDetail(String body) {
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            List<String> parts = new ArrayList<>();
            for (JsonNode item : detail) {
                parts.add("loc=" + item.path("loc") + " type=" + item.path("type").asText("unknown"));
            }
            return parts.isEmpty() ? "validation failed" : "validation failed: " + String.join("; ", parts);
        } catch (IOException | RuntimeException e) {
            return "validation failed";
        }
    }

    private void logAttempt(int attempt, int status, long start, String kind) {
        LOG.info("jev attempt={} status={} kind={} elapsedMs={}", attempt, status, kind, elapsed(start).toMillis());
    }

    private Duration elapsed(long start) {
        return Duration.ofNanos(Math.max(0L, nanoClock.getAsLong() - start));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
