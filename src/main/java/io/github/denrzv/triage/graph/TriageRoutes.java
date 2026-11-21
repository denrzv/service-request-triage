package io.github.denrzv.triage.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single route table of the triage graph: route labels each node may select and the node each label leads to.
 * Nodes record the target in the trace and conditional edges use the same maps, so the trace and the compiled graph
 * cannot drift. Later features change the graph shape here (e.g. FEATURE-009 remaps {@code ambiguous}).
 */
public final class TriageRoutes {

    /** Trace name of the graph end; the compiled graph uses the framework's {@code END} constant. */
    public static final String END = "END";

    public static final String VALID = "valid";
    public static final String INVALID = "invalid";
    public static final String LOADED = "loaded";
    public static final String CLASSIFIED = "classified";
    public static final String ERROR = "error";
    public static final String DECIDED = "decided";
    public static final String FAILED = "failed";
    public static final String FINISHED = "finished";

    private static final Map<TriageNode, Map<String, String>> ROUTES = buildRoutes();

    private TriageRoutes() {}

    /** Label to target-node id for a node with conditional edges (target {@link #END} for the terminal node). */
    public static Map<String, String> of(TriageNode node) {
        return ROUTES.get(node);
    }

    /** Target node id for {@code label}; rejects labels the node may not select. */
    public static String target(TriageNode node, String label) {
        String target = ROUTES.get(node).get(label);
        if (target == null) {
            throw new IllegalArgumentException("node " + node.id() + " has no route " + label);
        }
        return target;
    }

    private static Map<TriageNode, Map<String, String>> buildRoutes() {
        String buildTrace = TriageNode.BUILD_TRACE.id();
        String finalGate = TriageNode.FINAL_POLICY_GATE.id();
        Map<TriageNode, Map<String, String>> routes = new LinkedHashMap<>();
        routes.put(TriageNode.VALIDATE_INPUT, ordered(
                VALID, TriageNode.LOAD_TAXONOMY.id(),
                INVALID, buildTrace,
                FAILED, buildTrace));
        routes.put(TriageNode.LOAD_TAXONOMY, ordered(
                LOADED, TriageNode.JEV_CLASSIFY.id(),
                FAILED, buildTrace));
        routes.put(TriageNode.JEV_CLASSIFY, ordered(
                CLASSIFIED, TriageNode.CONFIDENCE_GATE.id(),
                ERROR, TriageNode.CONFIDENCE_GATE.id(),
                FAILED, buildTrace));
        // v1: every gate path converges on the Policy Engine; FEATURE-009 remaps "ambiguous" to the investigator.
        routes.put(TriageNode.CONFIDENCE_GATE, ordered(
                GateOutcome.CLEAR.label(), finalGate,
                GateOutcome.AMBIGUOUS.label(), finalGate,
                GateOutcome.ABSTAIN.label(), finalGate,
                GateOutcome.ERROR.label(), finalGate,
                FAILED, buildTrace));
        routes.put(TriageNode.FINAL_POLICY_GATE, ordered(
                DECIDED, buildTrace,
                FAILED, buildTrace));
        routes.put(TriageNode.BUILD_TRACE, ordered(
                FINISHED, END,
                FAILED, END));
        return Map.copyOf(routes);
    }

    private static Map<String, String> ordered(String... labelTargetPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < labelTargetPairs.length; i += 2) {
            map.put(labelTargetPairs[i], labelTargetPairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
