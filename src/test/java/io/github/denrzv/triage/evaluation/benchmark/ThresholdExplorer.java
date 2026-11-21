package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One-threshold-at-a-time sweep over a copy of the base policy on a {@link CalibrationSet} (ADR FEATURE-005
 * decision 5). Every grid value re-decides the recorded assessments with the pure Policy Engine; no provider is
 * called. A candidate is the grid value with the highest auto-route coverage whose wrong auto-route rate does not
 * exceed the target; ties go to the lower wrong rate, then to the least restrictive value. Candidates are
 * provisional evidence for FEATURE-007, never written back into the base policy.
 */
public final class ThresholdExplorer {

    /** Grid 0.00, 0.05, ..., 1.00. */
    static final int GRID_STEPS = 20;

    /** Explored thresholds; {@code tightensUpwards} is false for a maximum (lower value = stricter). */
    public enum Threshold {
        TAXONOMY_FIT_MIN(true),
        CHOICE_CONFIDENCE_MIN(true),
        CHOICE_MARGIN_MIN(true),
        MULTI_INTENT_MAX(false);

        private final boolean tightensUpwards;

        Threshold(boolean tightensUpwards) {
            this.tightensUpwards = tightensUpwards;
        }

        PolicyThresholds apply(PolicyThresholds base, double value) {
            return switch (this) {
                case TAXONOMY_FIT_MIN -> new PolicyThresholds(value, base.choiceConfidenceMin(),
                        base.choiceMarginMin(), base.multiIntentMax(), base.signalMin());
                case CHOICE_CONFIDENCE_MIN -> new PolicyThresholds(base.taxonomyFitMin(), value,
                        base.choiceMarginMin(), base.multiIntentMax(), base.signalMin());
                case CHOICE_MARGIN_MIN -> new PolicyThresholds(base.taxonomyFitMin(), base.choiceConfidenceMin(),
                        value, base.multiIntentMax(), base.signalMin());
                case MULTI_INTENT_MAX -> new PolicyThresholds(base.taxonomyFitMin(), base.choiceConfidenceMin(),
                        base.choiceMarginMin(), value, base.signalMin());
            };
        }

        /** Restrictiveness order: larger means stricter. */
        double strictness(double value) {
            return tightensUpwards ? value : -value;
        }
    }

    public record SweepRow(Threshold threshold, double value, BenchmarkMetrics metrics) {}

    /** {@code value} is empty when no grid value meets the target. */
    public record Candidate(Threshold threshold, Optional<Double> value, SweepRow row) {}

    public record Exploration(
            BaselineMode mode,
            double maxWrongAutoRouteRate,
            List<SweepRow> rows,
            List<Candidate> candidates,
            ClassificationPolicy candidatePolicy) {}

    private final BaselineRunner runner;
    private final ClassificationPolicy basePolicy;
    private final double maxWrongAutoRouteRate;

    public ThresholdExplorer(BaselineRunner runner, ClassificationPolicy basePolicy, double maxWrongAutoRouteRate) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.basePolicy = Objects.requireNonNull(basePolicy, "basePolicy");
        if (maxWrongAutoRouteRate < 0.0 || maxWrongAutoRouteRate > 1.0) {
            throw new IllegalArgumentException("maxWrongAutoRouteRate must be within [0, 1]");
        }
        this.maxWrongAutoRouteRate = maxWrongAutoRouteRate;
    }

    public Exploration explore(CalibrationSet calibration) {
        Objects.requireNonNull(calibration, "calibration");
        List<SweepRow> rows = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        PolicyThresholds joint = basePolicy.thresholds();
        for (Threshold threshold : Threshold.values()) {
            List<SweepRow> sweep = sweep(threshold, calibration);
            rows.addAll(sweep);
            Candidate candidate = select(threshold, sweep);
            candidates.add(candidate);
            if (candidate.value().isPresent()) {
                joint = threshold.apply(joint, candidate.value().get());
            }
        }
        return new Exploration(calibration.mode(), maxWrongAutoRouteRate, List.copyOf(rows), List.copyOf(candidates),
                withThresholds(basePolicy, joint, basePolicy.version() + "-candidate"));
    }

    /** Re-decides outcomes under {@code policy}; used for the single held-out check of a candidate policy. */
    public List<CaseOutcome> redecide(List<CaseOutcome> outcomes, ClassificationPolicy policy) {
        return outcomes.stream().map(outcome -> runner.redecide(outcome, policy)).toList();
    }

    static ClassificationPolicy withThresholds(ClassificationPolicy base, PolicyThresholds thresholds, String version) {
        return new ClassificationPolicy(version, base.taxonomyVersion(), false, thresholds,
                base.lowTaxonomyFitAction(), base.noRuleMatchedAction(), base.maxInvestigations(),
                base.humanApprovalTypes(), base.rules());
    }

    private List<SweepRow> sweep(Threshold threshold, CalibrationSet calibration) {
        List<SweepRow> sweep = new ArrayList<>();
        for (int step = 0; step <= GRID_STEPS; step++) {
            double value = (double) step / GRID_STEPS;
            ClassificationPolicy policy = withThresholds(basePolicy,
                    threshold.apply(basePolicy.thresholds(), value), basePolicy.version() + "-sweep");
            sweep.add(new SweepRow(threshold, value,
                    BenchmarkMetrics.compute(redecide(calibration.outcomes(), policy))));
        }
        return sweep;
    }

    private Candidate select(Threshold threshold, List<SweepRow> sweep) {
        Comparator<SweepRow> order = Comparator
                .comparingDouble((SweepRow row) -> row.metrics().routing().autoRouteCoverage())
                .reversed()
                .thenComparingDouble(row -> row.metrics().routing().wrongAutoRouteRate())
                .thenComparingDouble(row -> threshold.strictness(row.value()));
        return sweep.stream()
                .filter(row -> row.metrics().routing().autoRouted() > 0)
                .filter(row -> row.metrics().routing().wrongAutoRouteRate() <= maxWrongAutoRouteRate)
                .min(order)
                .map(row -> new Candidate(threshold, Optional.of(row.value()), row))
                .orElseGet(() -> new Candidate(threshold, Optional.empty(), null));
    }
}
