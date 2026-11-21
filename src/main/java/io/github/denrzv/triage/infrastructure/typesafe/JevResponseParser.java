package io.github.denrzv.triage.infrastructure.typesafe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevQuestion;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.ScoreAnswer;
import io.github.denrzv.triage.domain.model.ScoreQuestion;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict parser for System One responses. Anything missing or malformed raises {@link JevProtocolException};
 * no default value or confidence is ever invented. Unknown extra fields are tolerated. Error messages never
 * include response text.
 */
final class JevResponseParser {

    /** Parsed successful response before call metadata is attached. */
    record Parsed(Map<String, JevAnswer> answers, String model, JevUsage usage) {}

    private final ObjectMapper objectMapper;

    JevResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Parsed parse(String body, JevQuestionSet questionSet) {
        JsonNode root = readRoot(body);
        try {
            String model = text(root, JevWire.MODEL);
            JevUsage usage = parseUsage(object(root, JevWire.USAGE));
            JsonNode answers = object(root, JevWire.ANSWERS);
            Map<String, JevAnswer> parsed = new LinkedHashMap<>();
            questionSet.questions().forEach((name, question) -> parsed.put(name, parseAnswer(name, question, answers)));
            return new Parsed(parsed, model, usage);
        } catch (IllegalArgumentException e) {
            throw new JevProtocolException("value outside the documented ranges", e);
        }
    }

    private JsonNode readRoot(String body) {
        if (body == null || body.isBlank()) {
            throw new JevProtocolException("empty body");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new JevProtocolException("body is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new JevProtocolException("body is not valid JSON");
        }
    }

    private JevUsage parseUsage(JsonNode usage) {
        return new JevUsage(integer(usage, JevWire.INPUT_TOKENS), integer(usage, JevWire.OUTPUT_TOKENS));
    }

    private JevAnswer parseAnswer(String name, JevQuestion question, JsonNode answers) {
        JsonNode node = answers.get(name);
        if (node == null || !node.isObject()) {
            throw new JevProtocolException("answer missing for a requested question");
        }
        String expectedType = typeOf(question);
        if (!expectedType.equals(text(node, JevWire.TYPE))) {
            throw new JevProtocolException("answer type does not match the question type");
        }
        return switch (question) {
            case ChoiceQuestion choice -> parseChoice(choice, node);
            case NoulQuestion ignored -> new NoulAnswer(number(node, JevWire.NOUL));
            case ScoreQuestion score -> parseScore(score, node);
        };
    }

    private static String typeOf(JevQuestion question) {
        return switch (question) {
            case ChoiceQuestion ignored -> JevWire.CHOICE;
            case NoulQuestion ignored -> JevWire.NOUL;
            case ScoreQuestion ignored -> JevWire.SCORE;
        };
    }

    private ChoiceAnswer parseChoice(ChoiceQuestion question, JsonNode node) {
        String selected = text(node, JevWire.CHOICE);
        if (!question.criteria().containsKey(selected)) {
            throw new JevProtocolException("selected choice is not a requested criterion");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        object(node, JevWire.PROBABILITIES).fields().forEachRemaining(entry -> {
            if (!question.criteria().containsKey(entry.getKey())) {
                throw new JevProtocolException("probability for an unknown criterion");
            }
            probabilities.put(entry.getKey(), requireNumber(entry.getValue()));
        });
        return new ChoiceAnswer(selected, number(node, JevWire.CONFIDENCE), probabilities);
    }

    private ScoreAnswer parseScore(ScoreQuestion question, JsonNode node) {
        int levels = question.levels().size();
        JsonNode legend = object(node, JevWire.LEGEND);
        JsonNode probabilities = object(node, JevWire.PROBABILITIES);
        List<Double> perLevel = new ArrayList<>(levels);
        for (int level = 0; level < levels; level++) {
            String key = Integer.toString(level);
            if (!legend.has(key)) {
                throw new JevProtocolException("score legend is missing a level");
            }
            perLevel.add(requireNumber(probabilities.get(key)));
        }
        if (probabilities.size() != levels) {
            throw new JevProtocolException("score probabilities do not match the requested levels");
        }
        return new ScoreAnswer(number(node, JevWire.SCORE), number(node, JevWire.CONFIDENCE), perLevel);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new JevProtocolException("missing or non-text field '" + field + "'");
        }
        return node.asText();
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new JevProtocolException("missing or empty object field '" + field + "'");
        }
        return node;
    }

    private static double number(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw new JevProtocolException("missing numeric field '" + field + "'");
        }
        return requireNumber(node);
    }

    private static double requireNumber(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw new JevProtocolException("expected a numeric value");
        }
        return node.doubleValue();
    }

    private static int integer(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new JevProtocolException("missing or non-integer field '" + field + "'");
        }
        return node.intValue();
    }
}
