package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@code ConfidenceGate}: labels the path ({@code clear|ambiguous|abstain|error}) from deterministic state. It asks the
 * pure Policy Engine for a preliminary decision so thresholds are never duplicated here, and it never writes
 * {@code routingDecision}; the formal decision is made only by {@code FinalPolicyGate}.
 *
 * <ul>
 *   <li>no assessment (provider failure) &rarr; {@code error};
 *   <li>preliminary proposal {@code UNCLASSIFIED} (low taxonomy fit or no rule matched) &rarr; {@code abstain};
 *   <li>preliminary rationale contains an ambiguity concern (multi-intent, low confidence, near-tie) &rarr;
 *       {@code ambiguous}, independent of the remaining investigation budget;
 *   <li>otherwise &rarr; {@code clear}.
 * </ul>
 */
final class ConfidenceGateNode implements TriageStep {

    private static final Set<RationaleCode> AMBIGUITY =
            EnumSet.of(RationaleCode.MULTI_INTENT, RationaleCode.LOW_CONFIDENCE, RationaleCode.NEAR_TIE);

    private final DefinitionSource definitions;
    private final ClassificationPolicyEngine engine;

    ConfidenceGateNode(DefinitionSource definitions, ClassificationPolicyEngine engine) {
        this.definitions = definitions;
        this.engine = engine;
    }

    @Override
    public TriageNode node() {
        return TriageNode.CONFIDENCE_GATE;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        GateOutcome outcome = outcome(state);
        return route(state.withGateOutcome(outcome), outcome.label());
    }

    private GateOutcome outcome(TriageGraphState state) {
        if (state.semanticAssessment() == null) {
            return GateOutcome.ERROR;
        }
        DefinitionSource.Definitions loaded = definitions.reload(state);
        RoutingDecision preliminary = engine.decide(
                state.semanticAssessment(), loaded.taxonomy(), loaded.policy(), state.investigationAttempts());
        if (preliminary.proposal().proposedType() == ServiceRequestType.UNCLASSIFIED) {
            return GateOutcome.ABSTAIN;
        }
        if (preliminary.rationaleCodes().stream().anyMatch(AMBIGUITY::contains)) {
            return GateOutcome.AMBIGUOUS;
        }
        return GateOutcome.CLEAR;
    }
}
