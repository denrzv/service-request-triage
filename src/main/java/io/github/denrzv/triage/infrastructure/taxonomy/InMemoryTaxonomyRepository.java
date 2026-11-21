package io.github.denrzv.triage.infrastructure.taxonomy;

import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable in-memory taxonomies keyed by version; duplicate versions are rejected. */
final class InMemoryTaxonomyRepository implements TaxonomyPort {

    private final Map<String, Taxonomy> byVersion;

    InMemoryTaxonomyRepository(List<Taxonomy> taxonomies) {
        Map<String, Taxonomy> map = new LinkedHashMap<>();
        for (Taxonomy taxonomy : taxonomies) {
            if (map.putIfAbsent(taxonomy.version(), taxonomy) != null) {
                throw new IllegalStateException("duplicate taxonomy version " + taxonomy.version());
            }
        }
        this.byVersion = Map.copyOf(map);
    }

    @Override
    public Taxonomy get(String version) {
        Taxonomy taxonomy = version == null ? null : byVersion.get(version);
        if (taxonomy == null) {
            throw new DefinitionNotFoundException("taxonomy", version);
        }
        return taxonomy;
    }
}
