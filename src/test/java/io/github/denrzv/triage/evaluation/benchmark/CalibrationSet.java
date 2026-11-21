package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import java.util.List;

/**
 * Outcomes of one baseline on the development/calibration split only. Construction rejects any held-out case, so
 * threshold exploration cannot read test labels by construction (AC5, ADR FEATURE-005 decision 5).
 */
public record CalibrationSet(BaselineMode mode, List<CaseOutcome> outcomes) {

    public CalibrationSet {
        if (mode == null || outcomes == null || outcomes.isEmpty()) {
            throw new IllegalArgumentException("a mode and at least one calibration outcome are required");
        }
        outcomes = List.copyOf(outcomes);
        for (CaseOutcome outcome : outcomes) {
            if (outcome.split() != GoldSplit.DEV) {
                throw new IllegalArgumentException(
                        "calibration accepts development cases only; got " + outcome.split() + " " + outcome.caseId());
            }
            if (outcome.mode() != mode) {
                throw new IllegalArgumentException("calibration outcomes must all come from " + mode);
            }
        }
    }
}
