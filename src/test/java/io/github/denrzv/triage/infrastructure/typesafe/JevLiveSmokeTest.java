package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.JevQuestion;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.ScoreQuestion;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in live smoke test against the real TypeSafe API. It is skipped unless {@code JEV_LIVE_SMOKE=true} and
 * {@code JEV_API_KEY} is set, because it sends one synthetic English message to a cloud provider and is billed
 * per input token. It checks the response shape only, never classification accuracy.
 */
@EnabledIfEnvironmentVariable(named = "JEV_LIVE_SMOKE", matches = "true")
class JevLiveSmokeTest {

    private static final String SMOKE_MESSAGE = "I was charged twice for the same card payment and I want my money back.";
    private static final String INTENT = "primary_intent";
    private static final String REFUND = "refund_requested";
    private static final String URGENCY = "customer_urgency";

    @Test
    void oneSyntheticMessageReturnsTypedAnswers() {
        String key = System.getenv("JEV_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "JEV_API_KEY is not set");
        String model = System.getenv().getOrDefault("JEV_MODEL", "jev-latest");

        var properties = new JevProperties(
                true,
                key,
                "https://api.typesafe.ai",
                model,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(200),
                Duration.ofSeconds(2));
        var adapter = new JevHttpClassificationAdapter(
                properties, new ObjectMapper(), HttpClient.newHttpClient(), Sleeper.THREAD, System::nanoTime);

        JevClassificationResult result = adapter.classify(
                new JevClassificationRequest(SMOKE_MESSAGE, "WEB_FORM", questionSet()));

        assertThat(result.answers()).containsOnlyKeys(INTENT, REFUND, URGENCY);
        assertThat(result.resolvedModel()).isNotBlank();
        assertThat(result.usage().inputTokens()).isPositive();
        System.out.printf(
                "jev live smoke ok: model=%s inputTokens=%d outputTokens=%d attempts=%d latencyMs=%d%n",
                result.resolvedModel(),
                result.usage().inputTokens(),
                result.usage().outputTokens(),
                result.attempts(),
                result.latency().toMillis());
    }

    private static JevQuestionSet questionSet() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("complaint", "Express dissatisfaction or a formal grievance");
        criteria.put("inquiry", "Ask for clarification or information");
        criteria.put("action_request", "Ask for a service action");
        Map<String, JevQuestion> questions = new LinkedHashMap<>();
        questions.put(INTENT, new ChoiceQuestion("What is the customer's primary intent?", criteria));
        questions.put(REFUND, new NoulQuestion("Does the customer explicitly request a monetary refund?"));
        questions.put(
                URGENCY,
                new ScoreQuestion(
                        "How time-sensitive does the customer state the problem is?",
                        List.of("No explicit urgency", "Requests timely attention", "Explicit urgent harm or deadline")));
        return new JevQuestionSet("smoke-1", questions);
    }
}
