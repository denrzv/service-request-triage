package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationProposal;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Synthetic graph-state builders for tests; arbitrary values, never goldset data. */
final class GraphStateFixtures {

    static final String MESSAGE = "I was charged twice for the same synthetic card payment and want it reversed.";

    private GraphStateFixtures() {}

    static IncomingCustomerRequest request() {
        return new IncomingCustomerRequest("demo-001", MESSAGE, Channel.INTERNET_BANK, "SYN-CUST-1");
    }

    static SemanticAssessment assessment() {
        return new SemanticAssessment(
                choice(PrimaryIntent.COMPLAINT, PrimaryIntent.class, 0.7),
                choice(ProblemDomain.PAYMENTS, ProblemDomain.class, 0.8),
                choice(RequestedOutcome.REFUND, RequestedOutcome.class, 0.6),
                0.9,
                0.1,
                0.2,
                0.95,
                "jev-test-1",
                "triage-jev-v1");
    }

    static RoutingDecision decision() {
        return new RoutingDecision(
                RouteAction.HUMAN_REVIEW,
                new ClassificationProposal(
                        "v1",
                        ServiceRequestType.CLAIM,
                        "DUPLICATE_CHARGE",
                        List.of(ServiceRequestType.SERVICE_REQUEST),
                        List.of("primaryIntent=COMPLAINT", "problemDomain=PAYMENTS")),
                List.of("R-1", "R-2"),
                List.of(RationaleCode.RULE_MATCHED, RationaleCode.HUMAN_APPROVAL_REQUIRED),
                "v1",
                false,
                true);
    }

    /** A complete state as produced by a clear run up to BuildTrace. */
    static TriageGraphState fullState() {
        return TriageGraphState.received(request(), null)
                .routed(TriageNode.VALIDATE_INPUT, "valid", TriageNode.LOAD_TAXONOMY.id())
                .withDefinitions("v1", "v1")
                .routed(TriageNode.LOAD_TAXONOMY, "loaded", TriageNode.JEV_CLASSIFY.id())
                .withAssessment(assessment(), "triage-jev-v1", "jev-test-1")
                .routed(TriageNode.JEV_CLASSIFY, "classified", TriageNode.CONFIDENCE_GATE.id())
                .withGateOutcome(GateOutcome.CLEAR)
                .routed(TriageNode.CONFIDENCE_GATE, "clear", TriageNode.FINAL_POLICY_GATE.id())
                .withRoutingDecision(decision())
                .routed(TriageNode.FINAL_POLICY_GATE, "decided", TriageNode.BUILD_TRACE.id());
    }

    /** Distributes {@code 1 - top} evenly over the other constants. */
    static <E extends Enum<E>> ChoiceJudgment<E> choice(E selected, Class<E> type, double top) {
        E[] constants = type.getEnumConstants();
        double rest = (1.0 - top) / (constants.length - 1);
        Map<E, Double> probabilities = new EnumMap<>(type);
        for (E constant : constants) {
            probabilities.put(constant, constant == selected ? top : rest);
        }
        return new ChoiceJudgment<>(selected, top, probabilities);
    }
}
