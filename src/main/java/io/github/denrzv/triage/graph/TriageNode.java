package io.github.denrzv.triage.graph;

/** Nodes of the triage {@code StateGraph}; {@link #id()} is the node name registered with the graph and the trace. */
public enum TriageNode {
    VALIDATE_INPUT("ValidateInput"),
    LOAD_TAXONOMY("LoadTaxonomy"),
    JEV_CLASSIFY("JevClassify"),
    CONFIDENCE_GATE("ConfidenceGate"),
    FINAL_POLICY_GATE("FinalPolicyGate"),
    BUILD_TRACE("BuildTrace");

    private final String id;

    TriageNode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
