package io.github.denrzv.triage.infrastructure.taxonomy;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable in-memory policies keyed by version. Construction verifies unique versions, that every policy is
 * compatible with its loaded taxonomy, and that the active version exists.
 */
final class InMemoryPolicyRepository implements ClassificationPolicyPort {

    private static final String KIND = "policy";

    private final Map<String, ClassificationPolicy> byVersion;
    private final ClassificationPolicy active;

    InMemoryPolicyRepository(List<ClassificationPolicy> policies, TaxonomyPort taxonomies, String activeVersion) {
        Map<String, ClassificationPolicy> map = new LinkedHashMap<>();
        for (ClassificationPolicy policy : policies) {
            if (map.putIfAbsent(policy.version(), policy) != null) {
                throw new IllegalStateException("duplicate policy version " + policy.version());
            }
            try {
                policy.requireCompatibleWith(taxonomies.get(policy.taxonomyVersion()));
            } catch (IllegalArgumentException | DefinitionNotFoundException e) {
                throw new IllegalStateException("policy " + policy.version() + " is invalid: " + e.getMessage(), e);
            }
        }
        this.byVersion = Map.copyOf(map);
        this.active = byVersion.get(activeVersion);
        if (active == null) {
            throw new IllegalStateException("active " + KIND + " version " + activeVersion + " is not loaded");
        }
    }

    @Override
    public ClassificationPolicy get(String version) {
        ClassificationPolicy policy = version == null ? null : byVersion.get(version);
        if (policy == null) {
            throw new DefinitionNotFoundException(KIND, version);
        }
        return policy;
    }

    @Override
    public ClassificationPolicy active() {
        return active;
    }
}
