package io.github.denrzv.triage.domain.model;

/**
 * Execution status of one triage run (docs/contracts.md section 1). It describes the triage workflow only and is not
 * the lifecycle status of any Service Request, Claim or Task.
 */
public enum TriageStatus {
    /** Run accepted, graph not finished. */
    RECEIVED,
    /** Policy Engine proposed a formal type with route {@code DRAFT_PROPOSAL}; nothing has been created. */
    CLASSIFIED,
    /** Reserved for the bounded LLM investigation (FEATURE-009). */
    INVESTIGATING,
    /** Route {@code HUMAN_REVIEW}: a person must decide. */
    AWAITING_HUMAN,
    /** Run finished with a terminal outcome that needs no further step (route {@code ABSTAIN}). */
    COMPLETED,
    /** Graph stopped before producing a trace and status, e.g. iteration limit reached. */
    PARTIAL,
    /** Run could not produce a decision: rejected input, missing definitions or an unexpected failure. */
    FAILED
}
