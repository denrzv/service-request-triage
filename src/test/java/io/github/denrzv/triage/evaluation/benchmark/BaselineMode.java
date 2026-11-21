package io.github.denrzv.triage.evaluation.benchmark;

/** Baselines of ADR-009 measured by this harness. Both use the same Policy Engine and policy without LLM fallback. */
public enum BaselineMode {
    /** Deterministic keyword assessor ({@link KeywordRulesAssessor}). */
    RULES_ONLY,
    /** Jev judgments of the {@code triage-jev-v1} question set. */
    JEV_ONLY
}
