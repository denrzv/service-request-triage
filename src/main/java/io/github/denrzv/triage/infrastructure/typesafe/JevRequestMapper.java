package io.github.denrzv.triage.infrastructure.typesafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevQuestion;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.ScoreQuestion;

/** Maps a domain request to the System One JSON body. Choice criteria are an object, Score levels an array. */
final class JevRequestMapper {

    private final ObjectMapper objectMapper;

    JevRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode toBody(String model, JevClassificationRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put(JevWire.MODEL, model);
        ObjectNode state = body.putObject(JevWire.STATE);
        state.put(JevWire.CUSTOMER_MESSAGE, request.message());
        if (request.channel() != null) {
            state.put(JevWire.CHANNEL, request.channel());
        }
        ObjectNode questions = body.putObject(JevWire.QUESTIONS);
        request.questionSet().questions().forEach((name, question) -> questions.set(name, toNode(question)));
        return body;
    }

    private ObjectNode toNode(JevQuestion question) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(JevWire.INSTRUCTIONS, question.instructions());
        switch (question) {
            case ChoiceQuestion choice -> {
                node.put(JevWire.TYPE, JevWire.CHOICE);
                ObjectNode criteria = node.putObject(JevWire.CRITERIA);
                choice.criteria().forEach(criteria::put);
            }
            case ScoreQuestion score -> {
                node.put(JevWire.TYPE, JevWire.SCORE);
                ArrayNode levels = node.putArray(JevWire.CRITERIA);
                score.levels().forEach(levels::add);
            }
            case NoulQuestion ignored -> node.put(JevWire.TYPE, JevWire.NOUL);
        }
        return node;
    }
}
