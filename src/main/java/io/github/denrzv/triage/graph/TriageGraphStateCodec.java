package io.github.denrzv.triage.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationProposal;
import io.github.denrzv.triage.domain.model.DecisionTraceEvent;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.TriageStatus;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, versioned JSON form ({@value #SCHEMA_VERSION}) of {@link TriageGraphState}. It is the only value stored in
 * the graph's {@code OverAllState}, so the state survives any graph serializer and checkpoint saver. Explicit wire
 * records with string enums keep the format independent of framework type metadata. Decoding rejects unknown fields,
 * unknown enum constants, a different schema version and values violating the domain invariants, always with an
 * {@link IllegalArgumentException} whose message never contains the JSON (which holds customer text).
 *
 * <p>Thread-safe; one instance can be shared.
 */
public final class TriageGraphStateCodec {

    public static final String SCHEMA_VERSION = "triage-graph-state-v1";

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public String encode(TriageGraphState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        try {
            return mapper.writeValueAsString(toWire(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode triage graph state: " + e.getClass().getSimpleName(), e);
        }
    }

    public TriageGraphState decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("state json must not be blank");
        }
        StateWire wire;
        try {
            wire = mapper.readValue(json, StateWire.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid triage graph state: " + e.getClass().getSimpleName());
        }
        if (!SCHEMA_VERSION.equals(wire.schemaVersion())) {
            throw new IllegalArgumentException("unsupported triage graph state schema");
        }
        try {
            return fromWire(wire);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("invalid triage graph state: " + e.getClass().getSimpleName());
        }
    }

    // ---- encode ----------------------------------------------------------------------------------------------

    private static StateWire toWire(TriageGraphState s) {
        IncomingCustomerRequest in = s.input();
        RequestWire request = new RequestWire(
                in.triageId(), in.message(), name(in.channel()), in.syntheticCustomerId());
        return new StateWire(
                SCHEMA_VERSION,
                request,
                s.policyVersion(),
                s.taxonomyVersion(),
                s.questionSetVersion(),
                s.resolvedModel(),
                s.semanticAssessment() == null ? null : assessmentWire(s.semanticAssessment()),
                s.providerError(),
                name(s.gateOutcome()),
                s.routingDecision() == null ? null : decisionWire(s.routingDecision()),
                s.investigationAttempts(),
                s.status().name(),
                s.failure() == null
                        ? null
                        : new FailureWire(s.failure().code().name(), s.failure().node(), s.failure().detail()),
                s.route(),
                s.trace().stream()
                        .map(e -> new TraceWire(e.sequence(), e.node(), e.routeLabel(), e.nextNode()))
                        .toList());
    }

    private static AssessmentWire assessmentWire(SemanticAssessment a) {
        return new AssessmentWire(
                choiceWire(a.primaryIntent(), PrimaryIntent.class),
                choiceWire(a.problemDomain(), ProblemDomain.class),
                choiceWire(a.requestedOutcome(), RequestedOutcome.class),
                a.refundRequestedNoul(),
                a.technicalProblemNoul(),
                a.multiIntentNoul(),
                a.taxonomyFitNoul(),
                a.modelUsed(),
                a.questionSetVersion());
    }

    /** Probabilities in enum declaration order, so the JSON is deterministic. */
    private static <E extends Enum<E>> ChoiceWire choiceWire(ChoiceJudgment<E> judgment, Class<E> type) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (E constant : type.getEnumConstants()) {
            Double value = judgment.probabilities().get(constant);
            if (value != null) {
                probabilities.put(constant.name(), value);
            }
        }
        return new ChoiceWire(judgment.selected().name(), judgment.confidence(), probabilities);
    }

    private static DecisionWire decisionWire(RoutingDecision d) {
        ClassificationProposal p = d.proposal();
        ProposalWire proposal = new ProposalWire(
                p.taxonomyVersion(),
                p.proposedType().name(),
                p.reasonCode(),
                p.alternatives().stream().map(Enum::name).toList(),
                p.supportingSignals());
        return new DecisionWire(
                d.action().name(),
                proposal,
                d.ruleIds(),
                d.rationaleCodes().stream().map(Enum::name).toList(),
                d.policyVersion(),
                d.policyCalibrated(),
                d.humanApprovalRequired());
    }

    // ---- decode ----------------------------------------------------------------------------------------------

    private static TriageGraphState fromWire(StateWire w) {
        if (w.input() == null || w.trace() == null) {
            throw new IllegalArgumentException("input and trace are required");
        }
        RequestWire r = w.input();
        IncomingCustomerRequest input = new IncomingCustomerRequest(
                r.triageId(), r.message(), r.channel() == null ? null : Channel.valueOf(r.channel()),
                r.syntheticCustomerId());
        return new TriageGraphState(
                input,
                w.policyVersion(),
                w.taxonomyVersion(),
                w.questionSetVersion(),
                w.resolvedModel(),
                w.semanticAssessment() == null ? null : assessment(w.semanticAssessment()),
                w.providerError(),
                w.gateOutcome() == null ? null : GateOutcome.valueOf(w.gateOutcome()),
                w.routingDecision() == null ? null : decision(w.routingDecision()),
                w.investigationAttempts(),
                TriageStatus.valueOf(w.status()),
                w.failure() == null
                        ? null
                        : new TriageFailure(FailureCode.valueOf(w.failure().code()), w.failure().node(),
                                w.failure().detail()),
                w.route(),
                w.trace().stream()
                        .map(t -> new DecisionTraceEvent(t.sequence(), t.node(), t.routeLabel(), t.nextNode()))
                        .toList());
    }

    private static SemanticAssessment assessment(AssessmentWire a) {
        return new SemanticAssessment(
                choice(a.primaryIntent(), PrimaryIntent.class),
                choice(a.problemDomain(), ProblemDomain.class),
                choice(a.requestedOutcome(), RequestedOutcome.class),
                a.refundRequestedNoul(),
                a.technicalProblemNoul(),
                a.multiIntentNoul(),
                a.taxonomyFitNoul(),
                a.modelUsed(),
                a.questionSetVersion());
    }

    private static <E extends Enum<E>> ChoiceJudgment<E> choice(ChoiceWire c, Class<E> type) {
        if (c == null || c.probabilities() == null) {
            throw new IllegalArgumentException("choice is incomplete");
        }
        Map<E, Double> probabilities = new EnumMap<>(type);
        c.probabilities().forEach((key, value) -> probabilities.put(Enum.valueOf(type, key), value));
        return new ChoiceJudgment<>(Enum.valueOf(type, c.selected()), c.confidence(), probabilities);
    }

    private static RoutingDecision decision(DecisionWire d) {
        ProposalWire p = d.proposal();
        if (p == null || p.alternatives() == null || d.rationaleCodes() == null) {
            throw new IllegalArgumentException("decision is incomplete");
        }
        ClassificationProposal proposal = new ClassificationProposal(
                p.taxonomyVersion(),
                ServiceRequestType.valueOf(p.proposedType()),
                p.reasonCode(),
                p.alternatives().stream().map(ServiceRequestType::valueOf).toList(),
                p.supportingSignals());
        return new RoutingDecision(
                RouteAction.valueOf(d.action()),
                proposal,
                d.ruleIds(),
                d.rationaleCodes().stream().map(RationaleCode::valueOf).toList(),
                d.policyVersion(),
                d.policyCalibrated(),
                d.humanApprovalRequired());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    // ---- wire records (field order = JSON order) -------------------------------------------------------------

    record StateWire(
            String schemaVersion,
            RequestWire input,
            String policyVersion,
            String taxonomyVersion,
            String questionSetVersion,
            String resolvedModel,
            AssessmentWire semanticAssessment,
            String providerError,
            String gateOutcome,
            DecisionWire routingDecision,
            int investigationAttempts,
            String status,
            FailureWire failure,
            String route,
            List<TraceWire> trace) {}

    record RequestWire(String triageId, String message, String channel, String syntheticCustomerId) {}

    record AssessmentWire(
            ChoiceWire primaryIntent,
            ChoiceWire problemDomain,
            ChoiceWire requestedOutcome,
            double refundRequestedNoul,
            double technicalProblemNoul,
            double multiIntentNoul,
            double taxonomyFitNoul,
            String modelUsed,
            String questionSetVersion) {}

    record ChoiceWire(String selected, double confidence, Map<String, Double> probabilities) {}

    record DecisionWire(
            String action,
            ProposalWire proposal,
            List<String> ruleIds,
            List<String> rationaleCodes,
            String policyVersion,
            boolean policyCalibrated,
            boolean humanApprovalRequired) {}

    record ProposalWire(
            String taxonomyVersion,
            String proposedType,
            String reasonCode,
            List<String> alternatives,
            List<String> supportingSignals) {}

    record FailureWire(String code, String node, String detail) {}

    record TraceWire(int sequence, String node, String routeLabel, String nextNode) {}
}
