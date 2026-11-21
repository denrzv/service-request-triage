package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.denrzv.triage.domain.model.ClassificationProposal;
import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import java.time.Duration;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Seven hand-scored outcomes; every expected number below is computed by hand in the comments. */
class BenchmarkMetricsTest {

    private static final Offset<Double> EPS = within(1e-9);

    private final List<CaseOutcome> outcomes = List.of(
            // o1 gold CLAIM/HR -> HR CLAIM (correct)
            outcome(BenchmarkFixtures.claim(1), RouteAction.HUMAN_REVIEW, ServiceRequestType.CLAIM,
                    PrimaryIntent.COMPLAINT, 0.8, 0.9),
            // o2 gold CLAIM/HR -> DRAFT INFORMATION (missed complaint, wrong auto-route); intent wrong
            outcome(BenchmarkFixtures.claim(2), RouteAction.DRAFT_PROPOSAL, ServiceRequestType.INFORMATION_REQUEST,
                    PrimaryIntent.INQUIRY, 0.6, 0.9),
            // o3 gold INFORMATION/DRAFT -> DRAFT INFORMATION (correct auto-route)
            outcome(BenchmarkFixtures.information(3), RouteAction.DRAFT_PROPOSAL,
                    ServiceRequestType.INFORMATION_REQUEST, PrimaryIntent.INQUIRY, 0.8, 0.9),
            // o4 gold TECHNICAL/DRAFT -> HR TECHNICAL (type right, route over-cautious)
            outcome(BenchmarkFixtures.technical(4), RouteAction.HUMAN_REVIEW, ServiceRequestType.TECHNICAL_REQUEST,
                    PrimaryIntent.INCIDENT_REPORT, 0.8, 0.9),
            // o5 gold OOT/ABSTAIN -> ABSTAIN (correct rejection)
            outcome(BenchmarkFixtures.outOfTaxonomy(5), RouteAction.ABSTAIN, ServiceRequestType.UNCLASSIFIED,
                    PrimaryIntent.INQUIRY, 0.8, 0.2),
            // o6 gold OOT/ABSTAIN -> DRAFT INFORMATION (forced into taxonomy, wrong auto-route)
            outcome(BenchmarkFixtures.outOfTaxonomy(6), RouteAction.DRAFT_PROPOSAL,
                    ServiceRequestType.INFORMATION_REQUEST, PrimaryIntent.INQUIRY, 0.8, 0.4),
            // o7 gold VAGUE UNCLASSIFIED/HR -> provider error, HR UNCLASSIFIED
            new CaseOutcome(BenchmarkFixtures.vagueReview(7), GoldSplit.TEST, BaselineMode.JEV_ONLY, null,
                    decision(RouteAction.HUMAN_REVIEW, ServiceRequestType.UNCLASSIFIED), "JevUnavailableException",
                    null, null, 0));

    private final BenchmarkMetrics metrics = BenchmarkMetrics.compute(outcomes);

    @Test
    void countsCasesAndProviderErrors() {
        assertThat(metrics.cases()).isEqualTo(7);
        assertThat(metrics.assessedCases()).isEqualTo(6);
        assertThat(metrics.providerErrors()).isEqualTo(1);
    }

    @Test
    void formalTypeMacroF1AndConfusionMatrix() {
        // F1: CLAIM 2*1/(2+1), INFORMATION 2*1/(1+3), TECHNICAL 2*1/(1+1), UNCLASSIFIED 2*2/(3+2); SERVICE excluded.
        double expectedMacro = (2.0 / 3 + 0.5 + 1.0 + 0.8) / 4;
        assertThat(metrics.formalType().macroF1()).isCloseTo(expectedMacro, EPS);
        assertThat(metrics.formalType().accuracy()).isCloseTo(5.0 / 7, EPS);
        assertThat(metrics.formalType().perClass())
                .filteredOn(c -> c.label().equals("SERVICE_REQUEST"))
                .singleElement()
                .satisfies(c -> assertThat(c.f1()).isNull());
        assertThat(metrics.formalType().confusion().get("CLAIM"))
                .containsEntry("CLAIM", 1)
                .containsEntry("INFORMATION_REQUEST", 1);
        assertThat(metrics.formalType().confusion().get("UNCLASSIFIED"))
                .containsEntry("UNCLASSIFIED", 2)
                .containsEntry("INFORMATION_REQUEST", 1);
        assertThat(metrics.formalType().confusion()).hasSize(5);
    }

    @Test
    void complaintRecallAndFalseNegatives() {
        assertThat(metrics.complaint().goldClaims()).isEqualTo(2);
        assertThat(metrics.complaint().detected()).isEqualTo(1);
        assertThat(metrics.complaint().recall()).isCloseTo(0.5, EPS);
        assertThat(metrics.complaint().falseNegativeRate()).isCloseTo(0.5, EPS);
        assertThat(metrics.complaint().autoRouted()).isEqualTo(1);
    }

    @Test
    void routingCoverageWrongAutoRouteAndAbstention() {
        BenchmarkMetrics.Routing routing = metrics.routing();
        assertThat(routing.predictedRoutes())
                .containsEntry("DRAFT_PROPOSAL", 3)
                .containsEntry("HUMAN_REVIEW", 3)
                .containsEntry("ABSTAIN", 1)
                .containsEntry("LLM_INVESTIGATION", 0);
        assertThat(routing.autoRouteCoverage()).isCloseTo(3.0 / 7, EPS);
        assertThat(routing.wrongAutoRoutes()).isEqualTo(2);
        assertThat(routing.wrongAutoRouteRate()).isCloseTo(2.0 / 3, EPS);
        assertThat(routing.wrongAutoRoutesByStratum())
                .containsEntry("SIMPLE_COMPLAINT", 1)
                .containsEntry("OUT_OF_TAXONOMY", 1)
                .containsEntry("SIMPLE_INQUIRY", 0)
                .hasSize(12);
        assertThat(routing.selectiveAccuracy()).isCloseTo(1.0 / 3, EPS);
        assertThat(routing.routeAccuracy()).isCloseTo(4.0 / 7, EPS);
        assertThat(routing.abstainRate()).isCloseTo(1.0 / 7, EPS);
        assertThat(routing.humanReviewRate()).isCloseTo(3.0 / 7, EPS);
        assertThat(routing.goldAbstain()).isEqualTo(2);
        assertThat(routing.abstainPrecision()).isCloseTo(1.0, EPS);
        assertThat(routing.abstainRecall()).isCloseTo(0.5, EPS);
    }

    @Test
    void outOfTaxonomySuccessAndSafety() {
        assertThat(metrics.outOfTaxonomy().goldCases()).isEqualTo(2);
        assertThat(metrics.outOfTaxonomy().rejected()).isEqualTo(1);
        assertThat(metrics.outOfTaxonomy().successRate()).isCloseTo(0.5, EPS);
        assertThat(metrics.outOfTaxonomy().autoRouted()).isEqualTo(1);
        assertThat(metrics.outOfTaxonomy().safeRate()).isCloseTo(0.5, EPS);
    }

    @Test
    void signalsSeparateConfidenceFromCorrectnessAndCompanionNoul() {
        BenchmarkMetrics.ChoiceSignal intent = metrics.signals().choices().getFirst();
        assertThat(intent.dimension()).isEqualTo("primary_intent");
        assertThat(intent.accuracy()).isCloseTo(5.0 / 6, EPS);
        assertThat(intent.meanConfidenceWhenCorrect()).isCloseTo(0.8, EPS);
        assertThat(intent.meanConfidenceWhenWrong()).isCloseTo(0.6, EPS);
        assertThat(intent.meanTopProbability()).isCloseTo(0.9, EPS);
        assertThat(metrics.signals().meanTaxonomyFitInTaxonomy()).isCloseTo(0.9, EPS);
        assertThat(metrics.signals().meanTaxonomyFitOutOfTaxonomy()).isCloseTo(0.3, EPS);
        assertThat(metrics.semantic().intentAccuracy()).isCloseTo(5.0 / 6, EPS);
        assertThat(metrics.semantic().domainAccuracy()).isCloseTo(1.0, EPS);
    }

    @Test
    void emptyInputGivesUndefinedRatesInsteadOfZero() {
        BenchmarkMetrics empty = BenchmarkMetrics.compute(List.of());

        assertThat(empty.formalType().macroF1()).isNull();
        assertThat(empty.complaint().recall()).isNull();
        assertThat(empty.routing().wrongAutoRouteRate()).isNull();
    }

    private static CaseOutcome outcome(
            GoldCase gold, RouteAction action, ServiceRequestType type, PrimaryIntent intent, double confidence,
            double taxonomyFit) {
        SemanticAssessment assessment =
                BenchmarkFixtures.assessment(intent, gold.domain(), 0.9, confidence, taxonomyFit);
        return new CaseOutcome(gold, GoldSplit.TEST, BaselineMode.JEV_ONLY, assessment, decision(action, type), null,
                new JevUsage(100, 10), Duration.ofMillis(50), 1);
    }

    static RoutingDecision decision(RouteAction action, ServiceRequestType type) {
        String reason = type == ServiceRequestType.UNCLASSIFIED ? "NONE_FIT" : "GENERAL_INFORMATION";
        return new RoutingDecision(action, new ClassificationProposal("v1", type, reason, List.of(), List.of()),
                List.of(), List.of(RationaleCode.RULE_MATCHED), "v1", false, action == RouteAction.HUMAN_REVIEW);
    }
}
