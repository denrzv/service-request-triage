package io.github.denrzv.triage.domain.model;

/** Closed set of reasons the Policy Engine attaches to a {@link RoutingDecision}. */
public enum RationaleCode {
    /** A classification rule matched the semantic assessment. */
    RULE_MATCHED,
    /** No semantic assessment was available (provider unavailable or invalid judgment). */
    MODEL_UNAVAILABLE,
    /** Companion taxonomy-fit signal is below the policy minimum. */
    TAXONOMY_FIT_LOW,
    /** No rule of the policy matched (none-fit). */
    NO_RULE_MATCHED,
    /** Multi-intent signal is above the policy maximum. */
    MULTI_INTENT,
    /** Choice confidence of a dimension the matched rule depends on is below the policy minimum. */
    LOW_CONFIDENCE,
    /** Top-two probability margin of a dimension the matched rule depends on is below the policy minimum. */
    NEAR_TIE,
    /** An investigation would be needed but the policy's investigation budget is used up. */
    INVESTIGATION_BUDGET_EXHAUSTED,
    /** The proposed SR type requires human approval under the policy. */
    HUMAN_APPROVAL_REQUIRED,
    /** The policy's thresholds are not calibrated on evaluation data. */
    THRESHOLDS_UNCALIBRATED
}
