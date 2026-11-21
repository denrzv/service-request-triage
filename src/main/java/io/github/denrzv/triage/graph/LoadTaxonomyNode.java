package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;

/**
 * {@code LoadTaxonomy}: resolves the requested (or active) policy and its taxonomy and records both versions
 * ({@code loaded}); an unknown or incompatible version ends the run without a provider call ({@code failed}).
 */
final class LoadTaxonomyNode implements TriageStep {

    private final DefinitionSource definitions;

    LoadTaxonomyNode(DefinitionSource definitions) {
        this.definitions = definitions;
    }

    @Override
    public TriageNode node() {
        return TriageNode.LOAD_TAXONOMY;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        DefinitionSource.Definitions loaded;
        try {
            loaded = definitions.resolve(state.policyVersion());
        } catch (DefinitionNotFoundException | IllegalArgumentException e) {
            TriageFailure failure = new TriageFailure(
                    FailureCode.DEFINITIONS_UNAVAILABLE, node().id(), e.getClass().getSimpleName());
            return route(state.withFailure(failure), TriageRoutes.FAILED);
        }
        TriageGraphState next = state.withDefinitions(loaded.policy().version(), loaded.taxonomy().version());
        return route(next, TriageRoutes.LOADED);
    }
}
