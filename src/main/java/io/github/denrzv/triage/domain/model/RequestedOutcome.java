package io.github.denrzv.triage.domain.model;

/** Outcome the customer asks for (taxonomy dimension). */
public enum RequestedOutcome {
    REFUND,
    FIX_PROBLEM,
    INVESTIGATE,
    EXPLAIN,
    PERFORM_ACTION,
    NOT_SPECIFIED
}
