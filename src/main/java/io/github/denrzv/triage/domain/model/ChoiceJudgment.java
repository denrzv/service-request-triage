package io.github.denrzv.triage.domain.model;

import java.util.Map;

/**
 * Provider-neutral Choice judgment over one taxonomy dimension. {@code confidence} and {@code probabilities} are
 * provider-reported values, not verified correctness.
 */
public record ChoiceJudgment<E extends Enum<E>>(E selected, double confidence, Map<E, Double> probabilities) {

    public ChoiceJudgment {
        Checks.requireNonNull(selected, "selected");
        Checks.requireUnit(confidence, "confidence");
        Checks.requireNonNull(probabilities, "probabilities");
        probabilities.forEach((key, value) -> {
            Checks.requireNonNull(key, "probabilities key");
            Checks.requireUnit(Checks.requireNonNull(value, "probability"), "probability");
        });
        probabilities = Map.copyOf(probabilities);
        if (!probabilities.containsKey(selected)) {
            throw new IllegalArgumentException("probabilities must contain the selected value");
        }
    }

    /** Difference between the highest and the second-highest probability; the top probability if only one exists. */
    public double margin() {
        double top = 0.0;
        double second = 0.0;
        for (double value : probabilities.values()) {
            if (value > top) {
                second = top;
                top = value;
            } else if (value > second) {
                second = value;
            }
        }
        return top - second;
    }
}
