package io.github.denrzv.triage.graph;

/** Why a triage run ended as {@code FAILED} or {@code PARTIAL}. */
public enum FailureCode {
    /** {@code ValidateInput} rejected the request; no provider was called. */
    INPUT_REJECTED,
    /** Taxonomy or policy version not loaded or incompatible; no provider was called. */
    DEFINITIONS_UNAVAILABLE,
    /** A node threw an unexpected exception. */
    NODE_FAILED,
    /** A deterministic invariant of the graph was violated (e.g. investigation route without an investigator). */
    INVARIANT_VIOLATED,
    /** The graph stopped at its iteration limit before {@code BuildTrace} ran ({@code PARTIAL}). */
    ITERATION_LIMIT_REACHED,
    /** The graph runtime itself failed or returned no state. */
    GRAPH_RUNTIME_ERROR
}
