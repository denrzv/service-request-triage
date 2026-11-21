package io.github.denrzv.triage.evaluation.goldset;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import java.util.ArrayList;
import java.util.List;

/** Factory of valid synthetic {@link GoldCase} instances per stratum for validator and splitter tests. */
final class GoldCases {

    private static final String RATIONALE = "Synthetic test record with labels valid for its stratum.";

    private GoldCases() {}

    static GoldCase of(Stratum stratum, int number, String message, String group) {
        String id = String.format("GS-%04d", number);
        return switch (stratum) {
            case OUT_OF_TAXONOMY -> build(id, message, stratum, PrimaryIntent.INQUIRY, List.of(), ProblemDomain.OTHER,
                    RequestedOutcome.EXPLAIN, ServiceRequestType.UNCLASSIFIED, "LOW_TAXONOMY_FIT", RouteAction.ABSTAIN,
                    group);
            case NO_COMPLAINT_CONTROL -> build(id, message, stratum, PrimaryIntent.INQUIRY, List.of(),
                    ProblemDomain.PAYMENTS, RequestedOutcome.EXPLAIN, ServiceRequestType.INFORMATION_REQUEST,
                    "GENERAL_INFORMATION", RouteAction.DRAFT_PROPOSAL, group);
            case MULTI_INTENT -> build(id, message, stratum, PrimaryIntent.COMPLAINT, List.of(PrimaryIntent.INQUIRY),
                    ProblemDomain.PAYMENTS, RequestedOutcome.REFUND, ServiceRequestType.CLAIM, "PAYMENT_DISPUTE",
                    RouteAction.HUMAN_REVIEW, group);
            default -> build(id, message, stratum, PrimaryIntent.COMPLAINT, List.of(), ProblemDomain.PAYMENTS,
                    RequestedOutcome.REFUND, ServiceRequestType.CLAIM, "PAYMENT_DISPUTE", RouteAction.HUMAN_REVIEW,
                    group);
        };
    }

    /** {@code perStratum} distinct, valid cases for every stratum, numbered from 1. */
    static List<GoldCase> balanced(int perStratum) {
        List<GoldCase> cases = new ArrayList<>();
        int number = 1;
        for (Stratum stratum : Stratum.values()) {
            for (int i = 0; i < perStratum; i++, number++) {
                cases.add(of(stratum, number, "Synthetic case text " + number, null));
            }
        }
        return cases;
    }

    @SuppressWarnings("java:S107") // mirrors the record's label dimensions
    private static GoldCase build(
            String id,
            String message,
            Stratum stratum,
            PrimaryIntent intent,
            List<PrimaryIntent> secondary,
            ProblemDomain domain,
            RequestedOutcome outcome,
            ServiceRequestType type,
            String reasonCode,
            RouteAction route,
            String group) {
        return new GoldCase(id, message, Channel.INTERNET_BANK, LabelSource.SYNTHETIC_AUTHOR, "v1", stratum, intent,
                secondary, domain, outcome, type, reasonCode, route, route != RouteAction.DRAFT_PROPOSAL,
                GoldAlternatives.none(), group, RATIONALE);
    }
}
