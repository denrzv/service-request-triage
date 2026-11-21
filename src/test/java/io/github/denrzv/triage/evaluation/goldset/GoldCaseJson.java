package io.github.denrzv.triage.evaluation.goldset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable JSON builder for synthetic goldset records in tests; starts from one valid simple complaint. */
final class GoldCaseJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private GoldCaseJson() {}

    static GoldCaseJson valid() {
        GoldCaseJson json = new GoldCaseJson();
        json.fields.put("caseId", "GS-0001");
        json.fields.put("message", "You charged my card a late fee I never owed and I am unhappy about it.");
        json.fields.put("channel", "WEB_FORM");
        json.fields.put("labelSource", "SYNTHETIC_AUTHOR");
        json.fields.put("taxonomyVersion", "v1");
        json.fields.put("stratum", "SIMPLE_COMPLAINT");
        json.fields.put("intent", "COMPLAINT");
        json.fields.put("secondaryIntents", List.of());
        json.fields.put("domain", "PAYMENTS");
        json.fields.put("requestedOutcome", "REFUND");
        json.fields.put("formalSrType", "CLAIM");
        json.fields.put("reasonCode", "PAYMENT_DISPUTE");
        json.fields.put("recommendedRoute", "HUMAN_REVIEW");
        json.fields.put("requiresHumanReview", true);
        json.fields.put("alternatives", alternatives(List.of(), List.of()));
        json.fields.put("paraphraseGroup", null);
        json.fields.put("labelRationale", "Explicit dissatisfaction about an unexpected fee with a refund request.");
        return json;
    }

    static Map<String, Object> alternatives(List<String> intents, List<String> formalSrTypes) {
        Map<String, Object> alternatives = new LinkedHashMap<>();
        alternatives.put("intent", intents);
        alternatives.put("domain", List.of());
        alternatives.put("requestedOutcome", List.of());
        alternatives.put("formalSrType", formalSrTypes);
        alternatives.put("recommendedRoute", List.of());
        return alternatives;
    }

    GoldCaseJson with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    GoldCaseJson without(String field) {
        fields.remove(field);
        return this;
    }

    String line() {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
