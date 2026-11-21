package io.github.denrzv.triage.graph;

/**
 * Failure of a triage run. {@code detail} is a code-like value (validation rule, exception class simple name or
 * limit); it never contains exception messages or customer text.
 *
 * @param code failure category
 * @param node node where the failure occurred, {@code null} for graph-level failures
 * @param detail non-sensitive detail
 */
public record TriageFailure(FailureCode code, String node, String detail) {

    public TriageFailure {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        if (node != null && node.isBlank()) {
            throw new IllegalArgumentException("node must not be blank");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
