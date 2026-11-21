package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.TaxonomyPort;

/**
 * Resolves the versioned taxonomy and policy for a run. The state stores versions only; later nodes reload the same
 * versions so no definition object needs to be serialized.
 */
final class DefinitionSource {

    record Definitions(Taxonomy taxonomy, ClassificationPolicy policy) {}

    private final TaxonomyPort taxonomies;
    private final ClassificationPolicyPort policies;

    DefinitionSource(TaxonomyPort taxonomies, ClassificationPolicyPort policies) {
        if (taxonomies == null || policies == null) {
            throw new IllegalArgumentException("definition ports must not be null");
        }
        this.taxonomies = taxonomies;
        this.policies = policies;
    }

    /**
     * Loads the policy ({@code null} = active) and its taxonomy and checks compatibility.
     *
     * @throws io.github.denrzv.triage.domain.port.DefinitionNotFoundException unknown version
     * @throws IllegalArgumentException incompatible policy and taxonomy
     */
    Definitions resolve(String policyVersion) {
        ClassificationPolicy policy = policyVersion == null ? policies.active() : policies.get(policyVersion);
        Taxonomy taxonomy = taxonomies.get(policy.taxonomyVersion());
        policy.requireCompatibleWith(taxonomy);
        return new Definitions(taxonomy, policy);
    }

    /** Reloads the definitions recorded by {@code LoadTaxonomy}. */
    Definitions reload(TriageGraphState state) {
        if (state.policyVersion() == null || state.taxonomyVersion() == null) {
            throw new IllegalStateException("definitions were not loaded");
        }
        Definitions definitions = resolve(state.policyVersion());
        if (!definitions.taxonomy().version().equals(state.taxonomyVersion())) {
            throw new IllegalStateException("taxonomy version changed during the run");
        }
        return definitions;
    }
}
