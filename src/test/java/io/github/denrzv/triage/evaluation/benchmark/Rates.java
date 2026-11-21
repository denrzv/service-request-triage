package io.github.denrzv.triage.evaluation.benchmark;

import java.util.List;

/** Small numeric helpers; an undefined ratio is {@code null}, never 0 or NaN. */
final class Rates {

    private Rates() {}

    static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /** Nearest-rank percentile ({@code p} in (0, 1]) of already sorted values; {@code null} when empty. */
    static Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }
}
