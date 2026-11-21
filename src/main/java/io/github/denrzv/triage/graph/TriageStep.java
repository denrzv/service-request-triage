package io.github.denrzv.triage.graph;

/**
 * Body of one graph node: a function from the current to the next {@link TriageGraphState}. The returned state must
 * carry the selected route (via {@link TriageGraphState#routed}); {@link GuardedNode} adapts it to the SAA node API.
 */
interface TriageStep {

    TriageNode node();

    TriageGraphState apply(TriageGraphState state);

    /** Appends this node's transition for {@code label} using the shared route table. */
    default TriageGraphState route(TriageGraphState state, String label) {
        return state.routed(node(), label, TriageRoutes.target(node(), label));
    }
}
