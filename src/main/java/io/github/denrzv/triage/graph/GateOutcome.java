package io.github.denrzv.triage.graph;

/**
 * Path selected by {@code ConfidenceGate}. It labels the path only; the formal decision is always made by the Policy
 * Engine in {@code FinalPolicyGate}.
 */
public enum GateOutcome {
    /** Engine reports no ambiguity concern. */
    CLEAR,
    /** Engine would ask for an LLM investigation (multi-intent, low confidence or near-tie). */
    AMBIGUOUS,
    /** Engine proposes {@code UNCLASSIFIED} (low taxonomy fit or no rule matched). */
    ABSTAIN,
    /** No valid assessment exists (provider failure or invalid judgment). */
    ERROR;

    /** Route label used on the conditional edge, e.g. {@code clear}. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
