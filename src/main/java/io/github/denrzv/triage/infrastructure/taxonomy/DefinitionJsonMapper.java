package io.github.denrzv.triage.infrastructure.taxonomy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.ClassificationRule;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RuleCondition;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;

/**
 * Strict JSON-to-domain mapping for definition files. Missing, null or unknown properties, unknown enum names and
 * duplicate keys are rejected; domain invariants then validate content. Every failure is an
 * {@link IllegalArgumentException} with a reason (definition files contain no secrets or customer text).
 */
final class DefinitionJsonMapper {

    private final ObjectReader taxonomyReader;
    private final ObjectReader policyReader;

    DefinitionJsonMapper(ObjectMapper objectMapper) {
        ObjectReader strict = objectMapper
                .reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withFeatures(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.taxonomyReader = strict.forType(DefinitionWire.TaxonomyFile.class);
        this.policyReader = strict.forType(DefinitionWire.PolicyFile.class);
    }

    Taxonomy readTaxonomy(InputStream json) {
        DefinitionWire.TaxonomyFile file = read(taxonomyReader, json);
        requireSynthetic(file.synthetic());
        List<ReasonCode> codes = file.reasonCodes().stream()
                .map(entry -> new ReasonCode(entry.code(), entry.serviceRequestType(), entry.description()))
                .toList();
        return new Taxonomy(
                file.version(),
                file.primaryIntents(),
                file.problemDomains(),
                file.requestedOutcomes(),
                file.serviceRequestTypes(),
                codes);
    }

    ClassificationPolicy readPolicy(InputStream json) {
        DefinitionWire.PolicyFile file = read(policyReader, json);
        requireSynthetic(file.synthetic());
        DefinitionWire.Thresholds t = file.thresholds();
        PolicyThresholds thresholds = new PolicyThresholds(
                t.taxonomyFitMin(), t.choiceConfidenceMin(), t.choiceMarginMin(), t.multiIntentMax(), t.signalMin());
        List<ClassificationRule> rules = file.rules().stream().map(DefinitionJsonMapper::rule).toList();
        return new ClassificationPolicy(
                file.version(),
                file.taxonomyVersion(),
                file.calibrated(),
                thresholds,
                file.lowTaxonomyFitAction(),
                file.noRuleMatchedAction(),
                file.maxInvestigations(),
                new HashSet<>(file.humanApprovalTypes()),
                rules);
    }

    private static ClassificationRule rule(DefinitionWire.Rule rule) {
        DefinitionWire.Condition when = rule.when();
        RuleCondition condition = new RuleCondition(
                new HashSet<>(when.primaryIntents()),
                new HashSet<>(when.problemDomains()),
                new HashSet<>(when.requestedOutcomes()),
                when.refundRequested(),
                when.technicalProblem());
        return new ClassificationRule(
                rule.id(), condition, rule.then().serviceRequestType(), rule.then().reasonCode());
    }

    private static void requireSynthetic(Boolean synthetic) {
        if (!Boolean.TRUE.equals(synthetic)) {
            throw new IllegalArgumentException("definition must be marked synthetic: true");
        }
    }

    private static <T> T read(ObjectReader reader, InputStream json) {
        try {
            return reader.readValue(json);
        } catch (IOException e) {
            String reason = e instanceof JsonProcessingException processing ? processing.getOriginalMessage() : e.getMessage();
            throw new IllegalArgumentException("malformed definition JSON: " + reason, e);
        }
    }
}
