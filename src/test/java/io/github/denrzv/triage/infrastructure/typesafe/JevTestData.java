package io.github.denrzv.triage.infrastructure.typesafe;

import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevQuestion;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.ScoreQuestion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic English test data. Fixtures under {@code src/test/resources/jev} are schema-derived from the
 * TypeSafe OpenAPI 0.2.0 document, not captured live responses.
 */
final class JevTestData {

    static final String QUESTION_SET_VERSION = "qs-test-1";
    static final String MESSAGE = "The app rejected my login twice and I would like a refund of the monthly fee.";
    static final String INTENT = "primary_intent";
    static final String REFUND = "refund_requested";
    static final String URGENCY = "customer_urgency";

    private JevTestData() {}

    static JevQuestionSet questionSet() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("complaint", "Express dissatisfaction");
        criteria.put("inquiry", "Ask for information");
        criteria.put("action_request", "Ask for a service action");
        criteria.put("incident_report", "Report a malfunction");
        Map<String, JevQuestion> questions = new LinkedHashMap<>();
        questions.put(INTENT, new ChoiceQuestion("What is the primary intent?", criteria));
        questions.put(REFUND, new NoulQuestion("Does the customer explicitly request a refund?"));
        questions.put(
                URGENCY,
                new ScoreQuestion(
                        "How urgent is the problem?",
                        List.of("No explicit urgency", "Requests timely attention", "Explicit urgent harm")));
        return new JevQuestionSet(QUESTION_SET_VERSION, questions);
    }

    static JevClassificationRequest request() {
        return new JevClassificationRequest(MESSAGE, "WEB_FORM", questionSet());
    }

    static String fixture(String name) {
        try (var in = JevTestData.class.getResourceAsStream("/jev/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
