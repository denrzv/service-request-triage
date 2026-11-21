package io.github.denrzv.triage.evaluation.benchmark;

/**
 * Per-class counts and scores. {@code precision}, {@code recall} and {@code f1} are {@code null} when undefined
 * (zero denominator); a class with neither gold support nor predictions is excluded from macro averages.
 */
public record ClassMetrics(String label, int support, int predicted, int truePositives, Double precision, Double recall,
        Double f1) {

    static ClassMetrics of(String label, int support, int predicted, int truePositives) {
        Double precision = Rates.ratio(truePositives, predicted);
        Double recall = Rates.ratio(truePositives, support);
        Double f1;
        if (support + predicted == 0) {
            f1 = null;
        } else {
            f1 = 2.0 * truePositives / (support + predicted);
        }
        return new ClassMetrics(label, support, predicted, truePositives, precision, recall, f1);
    }

    boolean inMacroAverage() {
        return support + predicted > 0;
    }
}
