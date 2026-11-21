package io.github.denrzv.triage.domain.model;

/**
 * Versioned policy thresholds, all in [0, 1]. They are hypotheses until calibrated on evaluation data; minima of
 * {@code 0.0} and a maximum of {@code 1.0} never trigger (no-op). {@code signalMin} is the boundary that turns a Noul
 * probability into a rule condition.
 */
public record PolicyThresholds(
        double taxonomyFitMin, double choiceConfidenceMin, double choiceMarginMin, double multiIntentMax, double signalMin) {

    public PolicyThresholds {
        Checks.requireUnit(taxonomyFitMin, "taxonomyFitMin");
        Checks.requireUnit(choiceConfidenceMin, "choiceConfidenceMin");
        Checks.requireUnit(choiceMarginMin, "choiceMarginMin");
        Checks.requireUnit(multiIntentMax, "multiIntentMax");
        Checks.requireUnit(signalMin, "signalMin");
    }
}
