package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.evaluation.goldset.GoldAlternatives;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.LabelSource;
import io.github.denrzv.triage.evaluation.goldset.Stratum;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Synthetic gold cases and assessments for benchmark unit tests; none of the text comes from the goldset. */
final class BenchmarkFixtures {

    private BenchmarkFixtures() {}

    static GoldCase claim(int number) {
        return gold(number, Stratum.SIMPLE_COMPLAINT, PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS,
                ServiceRequestType.CLAIM, "PAYMENT_DISPUTE", RouteAction.HUMAN_REVIEW);
    }

    static GoldCase information(int number) {
        return gold(number, Stratum.SIMPLE_INQUIRY, PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS,
                ServiceRequestType.INFORMATION_REQUEST, "GENERAL_INFORMATION", RouteAction.DRAFT_PROPOSAL);
    }

    static GoldCase technical(int number) {
        return gold(number, Stratum.SIMPLE_INCIDENT, PrimaryIntent.INCIDENT_REPORT, ProblemDomain.DIGITAL_CHANNELS,
                ServiceRequestType.TECHNICAL_REQUEST, "DIGITAL_CHANNEL_MALFUNCTION", RouteAction.DRAFT_PROPOSAL);
    }

    static GoldCase outOfTaxonomy(int number) {
        return gold(number, Stratum.OUT_OF_TAXONOMY, PrimaryIntent.INQUIRY, ProblemDomain.OTHER,
                ServiceRequestType.UNCLASSIFIED, "LOW_TAXONOMY_FIT", RouteAction.ABSTAIN);
    }

    static GoldCase vagueReview(int number) {
        return gold(number, Stratum.VAGUE, PrimaryIntent.INQUIRY, ProblemDomain.OTHER,
                ServiceRequestType.UNCLASSIFIED, "NONE_FIT", RouteAction.HUMAN_REVIEW);
    }

    @SuppressWarnings("java:S107") // mirrors the record's label dimensions
    static GoldCase gold(
            int number,
            Stratum stratum,
            PrimaryIntent intent,
            ProblemDomain domain,
            ServiceRequestType type,
            String reasonCode,
            RouteAction route) {
        return new GoldCase(String.format("GS-%04d", number), "Synthetic benchmark fixture message " + number,
                Channel.WEB_FORM, LabelSource.SYNTHETIC_AUTHOR, "v1", stratum, intent, List.of(), domain,
                RequestedOutcome.NOT_SPECIFIED, type, reasonCode, route, route != RouteAction.DRAFT_PROPOSAL,
                GoldAlternatives.none(), null, "Synthetic fixture for benchmark unit tests.");
    }

    /** Assessment with the given selections, top probability and confidence, and Noul values. */
    static SemanticAssessment assessment(
            PrimaryIntent intent, ProblemDomain domain, double top, double confidence, double taxonomyFit) {
        return new SemanticAssessment(
                judgment(intent, PrimaryIntent.class, top, confidence),
                judgment(domain, ProblemDomain.class, top, confidence),
                judgment(RequestedOutcome.NOT_SPECIFIED, RequestedOutcome.class, top, confidence),
                0.0, 0.0, 0.1, taxonomyFit, "fixture-model", "fixture-qs");
    }

    static <E extends Enum<E>> ChoiceJudgment<E> judgment(E selected, Class<E> type, double top, double confidence) {
        E[] constants = type.getEnumConstants();
        double rest = (1.0 - top) / (constants.length - 1);
        Map<E, Double> probabilities = new EnumMap<>(type);
        for (E constant : constants) {
            probabilities.put(constant, constant == selected ? top : rest);
        }
        return new ChoiceJudgment<>(selected, confidence, probabilities);
    }
}
