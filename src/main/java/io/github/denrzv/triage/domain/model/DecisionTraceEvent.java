package io.github.denrzv.triage.domain.model;

/**
 * One transition of a triage run: the node that ran, the route label it selected and the node the edge led to.
 * Contains identifiers only, never customer text.
 *
 * @param sequence 1-based position in the run
 * @param node node that ran
 * @param routeLabel route label the node selected (edge key)
 * @param nextNode target node of the selected edge, {@code END} for the terminal transition
 */
public record DecisionTraceEvent(int sequence, String node, String routeLabel, String nextNode) {

    public DecisionTraceEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Checks.requireText(node, "node");
        Checks.requireText(routeLabel, "routeLabel");
        Checks.requireText(nextNode, "nextNode");
    }
}
