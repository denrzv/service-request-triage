package io.github.denrzv.triage.infrastructure.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.Taxonomy;

/** Test helper exposing the shipped definitions to tests outside this package (for example goldset checks). */
public final class ShippedDefinitions {

    private static final Taxonomy TAXONOMY_V1 = new DefinitionJsonMapper(new ObjectMapper())
            .readTaxonomy(DefinitionJsonMapperTest.resource("/taxonomy/v1.json"));
    private static final ClassificationPolicy POLICY_V1 = new DefinitionJsonMapper(new ObjectMapper())
            .readPolicy(DefinitionJsonMapperTest.resource("/policy/v1.json"));

    private ShippedDefinitions() {}

    /** The shipped {@code taxonomy/v1.json}, loaded through the production mapper and its validation. */
    public static Taxonomy taxonomyV1() {
        return TAXONOMY_V1;
    }

    /** The shipped {@code policy/v1.json}, loaded through the production mapper and its validation. */
    public static ClassificationPolicy policyV1() {
        return POLICY_V1;
    }
}
