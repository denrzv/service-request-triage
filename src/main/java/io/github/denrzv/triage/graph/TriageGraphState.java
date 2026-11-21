package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.DecisionTraceEvent;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.TriageStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable execution state of one triage run (docs/contracts.md section 5). It is graph state only: it carries no
 * Service Request lifecycle and is never persisted in v1 (in-memory, not durable). Taxonomy and policy objects are
 * not stored; nodes reload them by version.
 *
 * @param input request as submitted (unvalidated carrier)
 * @param policyVersion requested or resolved policy version, {@code null} = active policy
 * @param taxonomyVersion taxonomy version of the loaded policy, {@code null} before {@code LoadTaxonomy}
 * @param questionSetVersion Jev question set used, {@code null} before {@code JevClassify}
 * @param resolvedModel model reported by Jev, {@code null} if Jev was not called or failed
 * @param semanticAssessment mapped assessment, {@code null} if unavailable
 * @param providerError simple class name of the provider failure, {@code null} if none
 * @param gateOutcome path chosen by {@code ConfidenceGate}
 * @param routingDecision Policy Engine decision, written only by {@code FinalPolicyGate}
 * @param investigationAttempts LLM investigations performed (always 0 in v1)
 * @param status execution status
 * @param failure failure, set exactly when the run cannot produce a decision
 * @param route route label selected by the last node; read by the conditional edge
 * @param trace node/edge transitions, sequences 1..n
 */
public record TriageGraphState(
        IncomingCustomerRequest input,
        String policyVersion,
        String taxonomyVersion,
        String questionSetVersion,
        String resolvedModel,
        SemanticAssessment semanticAssessment,
        String providerError,
        GateOutcome gateOutcome,
        RoutingDecision routingDecision,
        int investigationAttempts,
        TriageStatus status,
        TriageFailure failure,
        String route,
        List<DecisionTraceEvent> trace) {

    public TriageGraphState {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (investigationAttempts < 0) {
            throw new IllegalArgumentException("investigationAttempts must not be negative");
        }
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        trace = List.copyOf(trace);
        for (int i = 0; i < trace.size(); i++) {
            if (trace.get(i).sequence() != i + 1) {
                throw new IllegalArgumentException("trace sequences must be consecutive from 1");
            }
        }
        if (semanticAssessment != null && providerError != null) {
            throw new IllegalArgumentException("assessment and providerError are mutually exclusive");
        }
        if (status == TriageStatus.FAILED || status == TriageStatus.PARTIAL) {
            if (failure == null) {
                throw new IllegalArgumentException(status + " requires a failure");
            }
        } else if (failure != null && status != TriageStatus.RECEIVED) {
            throw new IllegalArgumentException("failure requires status FAILED or PARTIAL");
        }
    }

    /** Initial state of a run. */
    public static TriageGraphState received(IncomingCustomerRequest input, String policyVersion) {
        return new TriageGraphState(
                input, policyVersion, null, null, null, null, null, null, null, 0, TriageStatus.RECEIVED, null, null,
                List.of());
    }

    /** Caller-supplied run identifier (may be invalid until {@code ValidateInput} accepted it). */
    public String triageId() {
        return input.triageId();
    }

    /** True once {@code BuildTrace} has produced the terminal transition. */
    public boolean finished() {
        return !trace.isEmpty() && TriageNode.BUILD_TRACE.id().equals(trace.getLast().node());
    }

    /** Copy with the selected route and one appended trace event for {@code node}. */
    public TriageGraphState routed(TriageNode node, String routeLabel, String nextNode) {
        List<DecisionTraceEvent> next = new ArrayList<>(trace);
        next.add(new DecisionTraceEvent(trace.size() + 1, node.id(), routeLabel, nextNode));
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, routingDecision, investigationAttempts, status, failure, routeLabel, next);
    }

    /** Copy with the validated, normalized request. */
    public TriageGraphState withInput(IncomingCustomerRequest normalized) {
        return new TriageGraphState(
                normalized, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, routingDecision, investigationAttempts, status, failure, route, trace);
    }

    public TriageGraphState withDefinitions(String resolvedPolicyVersion, String resolvedTaxonomyVersion) {
        return new TriageGraphState(
                input, resolvedPolicyVersion, resolvedTaxonomyVersion, questionSetVersion, resolvedModel,
                semanticAssessment, providerError, gateOutcome, routingDecision, investigationAttempts, status,
                failure, route, trace);
    }

    public TriageGraphState withAssessment(SemanticAssessment assessment, String usedQuestionSet, String model) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, usedQuestionSet, model, assessment, null, gateOutcome,
                routingDecision, investigationAttempts, status, failure, route, trace);
    }

    public TriageGraphState withProviderError(String errorType, String usedQuestionSet) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, usedQuestionSet, null, null, errorType, gateOutcome,
                routingDecision, investigationAttempts, status, failure, route, trace);
    }

    public TriageGraphState withGateOutcome(GateOutcome outcome) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, outcome, routingDecision, investigationAttempts, status, failure, route, trace);
    }

    public TriageGraphState withRoutingDecision(RoutingDecision decision) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, decision, investigationAttempts, status, failure, route, trace);
    }

    /** Records a failure; the status becomes {@code FAILED} only when {@code BuildTrace} finishes the run. */
    public TriageGraphState withFailure(TriageFailure newFailure) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, routingDecision, investigationAttempts, status, newFailure, route, trace);
    }

    public TriageGraphState withStatus(TriageStatus newStatus) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, routingDecision, investigationAttempts, newStatus, failure, route, trace);
    }

    /** Terminal copy for a run that ended without {@code BuildTrace} or whose runtime failed. */
    public TriageGraphState terminated(TriageStatus terminalStatus, TriageFailure terminalFailure) {
        return new TriageGraphState(
                input, policyVersion, taxonomyVersion, questionSetVersion, resolvedModel, semanticAssessment,
                providerError, gateOutcome, routingDecision, investigationAttempts, terminalStatus, terminalFailure,
                route, trace);
    }

    @Override
    public String toString() {
        return "TriageGraphState[input=" + input + ", policyVersion=" + policyVersion + ", taxonomyVersion="
                + taxonomyVersion + ", status=" + status + ", gateOutcome=" + gateOutcome + ", route=" + route
                + ", failure=" + failure + ", traceSize=" + trace.size() + "]";
    }
}
