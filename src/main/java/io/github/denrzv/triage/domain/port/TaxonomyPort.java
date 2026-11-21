package io.github.denrzv.triage.domain.port;

import io.github.denrzv.triage.domain.model.Taxonomy;

/** Read-only access to versioned synthetic taxonomies. */
public interface TaxonomyPort {

    /**
     * Returns the taxonomy with exactly this version.
     *
     * @throws DefinitionNotFoundException if the version is not loaded
     */
    Taxonomy get(String version);
}
