package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.Stratum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Quality, abstention and signal metrics of one baseline on one split (docs/evaluation.md section 3). Rates are
 * {@code null} when undefined. Semantic and signal metrics cover only cases with an assessment; formal-type and
 * routing metrics cover every case, a provider error being scored as the safe {@code UNCLASSIFIED}/review decision it
 * produced.
 *
 * <p>Wrong auto-route: the decision is {@code DRAFT_PROPOSAL} but the gold route is not, or the proposed formal type
 * differs from the gold type; it is also broken down by goldset stratum. Out-of-taxonomy cases are the
 * {@code OUT_OF_TAXONOMY} stratum.
 */
public record BenchmarkMetrics(
        int cases,
        int assessedCases,
        int providerErrors,
        FormalType formalType,
        Semantic semantic,
        Complaint complaint,
        Routing routing,
        OutOfTaxonomy outOfTaxonomy,
        Signals signals) {

    public record FormalType(
            Double macroF1,
            Double accuracy,
            Double lenientAccuracy,
            List<ClassMetrics> perClass,
            Map<String, Map<String, Integer>> confusion) {}

    public record Semantic(
            Double intentMacroF1, Double intentAccuracy, Double domainMacroF1, Double domainAccuracy,
            Double outcomeAccuracy) {}

    public record Complaint(int goldClaims, int detected, Double recall, Double falseNegativeRate, int autoRouted) {}

    public record Routing(
            Map<String, Integer> predictedRoutes,
            Double routeAccuracy,
            Double lenientRouteAccuracy,
            Double abstainRate,
            Double humanReviewRate,
            Double autoRouteCoverage,
            int autoRouted,
            int wrongAutoRoutes,
            Map<String, Integer> wrongAutoRoutesByStratum,
            Double wrongAutoRouteRate,
            Double selectiveAccuracy,
            int goldAbstain,
            Double abstainPrecision,
            Double abstainRecall) {}

    public record OutOfTaxonomy(int goldCases, int rejected, Double successRate, int autoRouted, Double safeRate) {}

    public record ChoiceSignal(
            String dimension,
            Double meanTopProbability,
            Double meanConfidence,
            Double meanMargin,
            Double accuracy,
            Double meanConfidenceWhenCorrect,
            Double meanConfidenceWhenWrong) {}

    public record Signals(
            List<ChoiceSignal> choices,
            Double meanTaxonomyFitInTaxonomy,
            Double meanTaxonomyFitOutOfTaxonomy,
            Double meanMultiIntentWithSecondaryIntent,
            Double meanMultiIntentSingleIntent) {}

    public static BenchmarkMetrics compute(List<CaseOutcome> outcomes) {
        List<CaseOutcome> assessed = outcomes.stream().filter(o -> !o.hasProviderError()).toList();
        return new BenchmarkMetrics(
                outcomes.size(),
                assessed.size(),
                outcomes.size() - assessed.size(),
                formalType(outcomes),
                semantic(assessed),
                complaint(outcomes),
                routing(outcomes),
                outOfTaxonomy(outcomes),
                signals(assessed));
    }

    /** True when the decision auto-proposes a draft that is wrong in type or should not have been auto-proposed. */
    public static boolean isWrongAutoRoute(CaseOutcome outcome) {
        GoldCase gold = outcome.gold();
        return outcome.decision().action() == RouteAction.DRAFT_PROPOSAL
                && (gold.recommendedRoute() != RouteAction.DRAFT_PROPOSAL || predictedType(outcome) != gold.formalSrType());
    }

    static ServiceRequestType predictedType(CaseOutcome outcome) {
        return outcome.decision().proposal().proposedType();
    }

    private static FormalType formalType(List<CaseOutcome> outcomes) {
        List<ClassMetrics> perClass = perClass(outcomes, ServiceRequestType.values(),
                o -> o.gold().formalSrType(), BenchmarkMetrics::predictedType);
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        for (ServiceRequestType gold : ServiceRequestType.values()) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (ServiceRequestType predicted : ServiceRequestType.values()) {
                row.put(predicted.name(), (int) outcomes.stream()
                        .filter(o -> o.gold().formalSrType() == gold && predictedType(o) == predicted)
                        .count());
            }
            confusion.put(gold.name(), row);
        }
        return new FormalType(
                macroF1(perClass),
                share(outcomes, o -> predictedType(o) == o.gold().formalSrType()),
                share(outcomes, o -> predictedType(o) == o.gold().formalSrType()
                        || o.gold().alternatives().formalSrType().contains(predictedType(o))),
                perClass,
                confusion);
    }

    private static Semantic semantic(List<CaseOutcome> assessed) {
        return new Semantic(
                macroF1(perClass(assessed, PrimaryIntent.values(),
                        o -> o.gold().intent(), o -> o.assessment().primaryIntent().selected())),
                share(assessed, o -> o.assessment().primaryIntent().selected() == o.gold().intent()),
                macroF1(perClass(assessed, ProblemDomain.values(),
                        o -> o.gold().domain(), o -> o.assessment().problemDomain().selected())),
                share(assessed, o -> o.assessment().problemDomain().selected() == o.gold().domain()),
                share(assessed, o -> o.assessment().requestedOutcome().selected() == o.gold().requestedOutcome()));
    }

    private static Complaint complaint(List<CaseOutcome> outcomes) {
        List<CaseOutcome> claims = outcomes.stream()
                .filter(o -> o.gold().formalSrType() == ServiceRequestType.CLAIM)
                .toList();
        int detected = count(claims, o -> predictedType(o) == ServiceRequestType.CLAIM);
        Double recall = Rates.ratio(detected, claims.size());
        return new Complaint(claims.size(), detected, recall, recall == null ? null : 1.0 - recall,
                count(claims, o -> o.decision().action() == RouteAction.DRAFT_PROPOSAL));
    }

    private static Routing routing(List<CaseOutcome> outcomes) {
        Map<String, Integer> routes = new LinkedHashMap<>();
        for (RouteAction action : RouteAction.values()) {
            routes.put(action.name(), count(outcomes, o -> o.decision().action() == action));
        }
        int autoRouted = routes.get(RouteAction.DRAFT_PROPOSAL.name());
        int wrong = count(outcomes, BenchmarkMetrics::isWrongAutoRoute);
        Map<String, Integer> wrongByStratum = new LinkedHashMap<>();
        for (Stratum stratum : Stratum.values()) {
            wrongByStratum.put(stratum.name(),
                    count(outcomes, o -> o.gold().stratum() == stratum && isWrongAutoRoute(o)));
        }
        int predictedAbstain = routes.get(RouteAction.ABSTAIN.name());
        int goldAbstain = count(outcomes, o -> o.gold().recommendedRoute() == RouteAction.ABSTAIN);
        int abstainHits = count(outcomes, o -> o.gold().recommendedRoute() == RouteAction.ABSTAIN
                && o.decision().action() == RouteAction.ABSTAIN);
        Double wrongRate = Rates.ratio(wrong, autoRouted);
        return new Routing(
                routes,
                share(outcomes, o -> o.decision().action() == o.gold().recommendedRoute()),
                share(outcomes, o -> o.decision().action() == o.gold().recommendedRoute()
                        || o.gold().alternatives().recommendedRoute().contains(o.decision().action())),
                Rates.ratio(predictedAbstain, outcomes.size()),
                Rates.ratio(routes.get(RouteAction.HUMAN_REVIEW.name()), outcomes.size()),
                Rates.ratio(autoRouted, outcomes.size()),
                autoRouted,
                wrong,
                wrongByStratum,
                wrongRate,
                wrongRate == null ? null : 1.0 - wrongRate,
                goldAbstain,
                Rates.ratio(abstainHits, predictedAbstain),
                Rates.ratio(abstainHits, goldAbstain));
    }

    private static OutOfTaxonomy outOfTaxonomy(List<CaseOutcome> outcomes) {
        List<CaseOutcome> oot = outcomes.stream().filter(o -> o.gold().stratum() == Stratum.OUT_OF_TAXONOMY).toList();
        int rejected = count(oot, o -> predictedType(o) == ServiceRequestType.UNCLASSIFIED);
        int autoRouted = count(oot, o -> o.decision().action() == RouteAction.DRAFT_PROPOSAL);
        return new OutOfTaxonomy(oot.size(), rejected, Rates.ratio(rejected, oot.size()), autoRouted,
                Rates.ratio(oot.size() - autoRouted, oot.size()));
    }

    private static Signals signals(List<CaseOutcome> assessed) {
        List<ChoiceSignal> choices = List.of(
                choiceSignal("primary_intent", assessed, SemanticAssessment::primaryIntent, GoldCase::intent),
                choiceSignal("problem_domain", assessed, SemanticAssessment::problemDomain, GoldCase::domain),
                choiceSignal("requested_outcome", assessed, SemanticAssessment::requestedOutcome,
                        GoldCase::requestedOutcome));
        Predicate<CaseOutcome> oot = o -> o.gold().stratum() == Stratum.OUT_OF_TAXONOMY;
        Predicate<CaseOutcome> multi = o -> !o.gold().secondaryIntents().isEmpty();
        return new Signals(
                choices,
                meanOf(assessed, oot.negate(), o -> o.assessment().taxonomyFitNoul()),
                meanOf(assessed, oot, o -> o.assessment().taxonomyFitNoul()),
                meanOf(assessed, multi, o -> o.assessment().multiIntentNoul()),
                meanOf(assessed, multi.negate(), o -> o.assessment().multiIntentNoul()));
    }

    private static <E extends Enum<E>> ChoiceSignal choiceSignal(
            String dimension,
            List<CaseOutcome> assessed,
            Function<SemanticAssessment, ChoiceJudgment<E>> judgment,
            Function<GoldCase, E> gold) {
        Predicate<CaseOutcome> correct = o -> judgment.apply(o.assessment()).selected() == gold.apply(o.gold());
        return new ChoiceSignal(
                dimension,
                meanOf(assessed, o -> true, o -> top(judgment.apply(o.assessment()))),
                meanOf(assessed, o -> true, o -> judgment.apply(o.assessment()).confidence()),
                meanOf(assessed, o -> true, o -> judgment.apply(o.assessment()).margin()),
                share(assessed, correct),
                meanOf(assessed, correct, o -> judgment.apply(o.assessment()).confidence()),
                meanOf(assessed, correct.negate(), o -> judgment.apply(o.assessment()).confidence()));
    }

    static double top(ChoiceJudgment<?> judgment) {
        return judgment.probabilities().get(judgment.selected());
    }

    private static <E extends Enum<E>> List<ClassMetrics> perClass(
            List<CaseOutcome> outcomes, E[] classes, Function<CaseOutcome, E> gold, Function<CaseOutcome, E> predicted) {
        List<ClassMetrics> metrics = new ArrayList<>();
        for (E label : classes) {
            metrics.add(ClassMetrics.of(
                    label.name(),
                    count(outcomes, o -> gold.apply(o) == label),
                    count(outcomes, o -> predicted.apply(o) == label),
                    count(outcomes, o -> gold.apply(o) == label && predicted.apply(o) == label)));
        }
        return List.copyOf(metrics);
    }

    private static Double macroF1(List<ClassMetrics> perClass) {
        return Rates.mean(perClass.stream().filter(ClassMetrics::inMacroAverage).map(ClassMetrics::f1).toList());
    }

    private static Double share(List<CaseOutcome> outcomes, Predicate<CaseOutcome> predicate) {
        return Rates.ratio(count(outcomes, predicate), outcomes.size());
    }

    private static Double meanOf(
            List<CaseOutcome> outcomes, Predicate<CaseOutcome> filter, Function<CaseOutcome, Double> value) {
        return Rates.mean(outcomes.stream().filter(filter).map(value).toList());
    }

    private static int count(List<CaseOutcome> outcomes, Predicate<CaseOutcome> predicate) {
        return (int) outcomes.stream().filter(predicate).count();
    }
}
