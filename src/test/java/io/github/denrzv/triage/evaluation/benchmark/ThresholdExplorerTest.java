package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.evaluation.benchmark.ThresholdExplorer.Candidate;
import io.github.denrzv.triage.evaluation.benchmark.ThresholdExplorer.Threshold;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Three correct information cases (confidence 0.9, fit 0.9) and two out-of-taxonomy cases that policy v1 wrongly
 * auto-routes as information requests (confidence 0.4, fit 0.3). Wrong auto-route rate at the no-op policy is 2/5.
 */
class ThresholdExplorerTest {

    private static final double TARGET = 0.10;

    private final ClassificationPolicy policy = ShippedDefinitions.policyV1();
    private final BaselineRunner runner =
            new BaselineRunner(ShippedDefinitions.taxonomyV1(), new ClassificationPolicyEngine());
    private final ThresholdExplorer explorer = new ThresholdExplorer(runner, policy, TARGET);

    @Test
    void calibrationSetRejectsHeldOutCasesAndMixedModes() {
        List<CaseOutcome> dev = outcomes(GoldSplit.DEV);
        List<CaseOutcome> withTest = new ArrayList<>(dev);
        withTest.add(outcomes(GoldSplit.TEST).getFirst());

        assertThatThrownBy(() -> new CalibrationSet(BaselineMode.JEV_ONLY, withTest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("development cases only");
        assertThatThrownBy(() -> new CalibrationSet(BaselineMode.RULES_ONLY, dev))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalibrationSet(BaselineMode.JEV_ONLY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sweepsEveryThresholdOverTheFullGrid() {
        ThresholdExplorer.Exploration exploration = explore();

        assertThat(exploration.rows()).hasSize(Threshold.values().length * (ThresholdExplorer.GRID_STEPS + 1));
        assertThat(exploration.rows().getFirst().metrics().routing().wrongAutoRouteRate()).isEqualTo(0.4);
        assertThat(exploration.maxWrongAutoRouteRate()).isEqualTo(TARGET);
    }

    @Test
    void candidateIsLeastRestrictiveValueWithMaximalSafeCoverage() {
        List<Candidate> candidates = explore().candidates();

        assertThat(candidate(candidates, Threshold.CHOICE_CONFIDENCE_MIN).value()).contains(0.45);
        assertThat(candidate(candidates, Threshold.TAXONOMY_FIT_MIN).value()).contains(0.35);
        assertThat(candidate(candidates, Threshold.TAXONOMY_FIT_MIN).row().metrics().routing().autoRouteCoverage())
                .isEqualTo(0.6);
    }

    @Test
    void unattainableTargetGivesNoCandidate() {
        List<Candidate> candidates = explore().candidates();

        assertThat(candidate(candidates, Threshold.MULTI_INTENT_MAX).value()).isEmpty();
        assertThat(candidate(candidates, Threshold.CHOICE_MARGIN_MIN).value()).isEmpty();
    }

    @Test
    void candidatePolicyIsAProvisionalCopyAndBasePolicyIsUnchanged() {
        ClassificationPolicy candidate = explore().candidatePolicy();

        assertThat(candidate.version()).isEqualTo("v1-candidate");
        assertThat(candidate.calibrated()).isFalse();
        assertThat(candidate.thresholds().taxonomyFitMin()).isEqualTo(0.35);
        assertThat(candidate.thresholds().choiceConfidenceMin()).isEqualTo(0.45);
        assertThat(candidate.thresholds().choiceMarginMin()).isZero();
        assertThat(candidate.thresholds().multiIntentMax()).isEqualTo(1.0);
        assertThat(policy.thresholds().taxonomyFitMin()).isZero();
        assertThat(candidate.rules()).isEqualTo(policy.rules());
    }

    @Test
    void heldOutCheckRedecidesWithoutTouchingProviderData() {
        List<CaseOutcome> test = outcomes(GoldSplit.TEST);

        List<CaseOutcome> checked = explorer.redecide(test, explore().candidatePolicy());

        assertThat(BenchmarkMetrics.compute(checked).routing().wrongAutoRoutes()).isZero();
        assertThat(checked.getFirst().assessment()).isSameAs(test.getFirst().assessment());
        assertThatThrownBy(() -> new ThresholdExplorer(runner, policy, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ThresholdExplorer.Exploration explore() {
        return explorer.explore(new CalibrationSet(BaselineMode.JEV_ONLY, outcomes(GoldSplit.DEV)));
    }

    private static Candidate candidate(List<Candidate> candidates, Threshold threshold) {
        return candidates.stream().filter(c -> c.threshold() == threshold).findFirst().orElseThrow();
    }

    private List<CaseOutcome> outcomes(GoldSplit split) {
        List<CaseOutcome> outcomes = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            outcomes.add(outcome(split, BenchmarkFixtures.information(i), 0.9, 0.9));
        }
        for (int i = 4; i <= 5; i++) {
            outcomes.add(outcome(split, BenchmarkFixtures.outOfTaxonomy(i), 0.4, 0.3));
        }
        return outcomes;
    }

    private CaseOutcome outcome(GoldSplit split, GoldCase gold, double confidence, double fit) {
        SemanticAssessment assessment = BenchmarkFixtures.assessment(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, 0.9, confidence, fit);
        return new CaseOutcome(gold, split, BaselineMode.JEV_ONLY, assessment, runner.decide(assessment, policy), null,
                null, null, 0);
    }
}
