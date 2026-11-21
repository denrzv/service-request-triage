package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.util.Map;

/**
 * Shipped taxonomy/policy {@code v1} plus a test-only policy {@value #STRICT} with the same rules and active
 * (synthetic, uncalibrated) thresholds, so ambiguity and low-fit paths are reachable.
 */
final class GraphTestDefinitions {

    static final String STRICT = "v1-strict-test";

    /** taxonomyFitMin 0.5, confidence 0.6, margin 0.2, multi-intent max 0.6, signal 0.5. */
    static final PolicyThresholds STRICT_THRESHOLDS = new PolicyThresholds(0.5, 0.6, 0.2, 0.6, 0.5);

    private GraphTestDefinitions() {}

    static TaxonomyPort taxonomies() {
        Taxonomy v1 = ShippedDefinitions.taxonomyV1();
        return version -> {
            if (!v1.version().equals(version)) {
                throw new DefinitionNotFoundException("taxonomy", version);
            }
            return v1;
        };
    }

    static ClassificationPolicyPort policies() {
        ClassificationPolicy v1 = ShippedDefinitions.policyV1();
        ClassificationPolicy strict = new ClassificationPolicy(
                STRICT,
                v1.taxonomyVersion(),
                false,
                STRICT_THRESHOLDS,
                v1.lowTaxonomyFitAction(),
                v1.noRuleMatchedAction(),
                v1.maxInvestigations(),
                v1.humanApprovalTypes(),
                v1.rules());
        Map<String, ClassificationPolicy> byVersion = Map.of(v1.version(), v1, STRICT, strict);
        return new ClassificationPolicyPort() {
            @Override
            public ClassificationPolicy get(String version) {
                ClassificationPolicy policy = byVersion.get(version);
                if (policy == null) {
                    throw new DefinitionNotFoundException("policy", version);
                }
                return policy;
            }

            @Override
            public ClassificationPolicy active() {
                return v1;
            }
        };
    }

    static DefinitionSource source() {
        return new DefinitionSource(taxonomies(), policies());
    }
}
