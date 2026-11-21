package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.Taxonomy;

/**
 * {@code FinalPolicyGate}: the only node that writes {@code routingDecision}, always from the pure Policy Engine.
 * Gate outcome {@code error} uses {@code modelUnavailable} ({@code HUMAN_REVIEW} / {@code MODEL_UNAVAILABLE}); every
 * other outcome uses {@code decide} with the investigation budget treated as exhausted, because no investigator is
 * wired in v1 (FEATURE-009 adds it). A returned {@code LLM_INVESTIGATION} would therefore be an invariant violation
 * and fails the run instead of being surfaced as a route.
 */
final class FinalPolicyGateNode implements TriageStep {

    /** Seam over {@link ClassificationPolicyEngine#decide} so the invariant guard can be tested. */
    @FunctionalInterface
    interface Decider {
        RoutingDecision decide(
                SemanticAssessment assessment, Taxonomy taxonomy, ClassificationPolicy policy, int attempts);
    }

    private final DefinitionSource definitions;
    private final ClassificationPolicyEngine engine;
    private final Decider decider;

    FinalPolicyGateNode(DefinitionSource definitions, ClassificationPolicyEngine engine) {
        this(definitions, engine, engine::decide);
    }

    FinalPolicyGateNode(DefinitionSource definitions, ClassificationPolicyEngine engine, Decider decider) {
        this.definitions = definitions;
        this.engine = engine;
        this.decider = decider;
    }

    @Override
    public TriageNode node() {
        return TriageNode.FINAL_POLICY_GATE;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        if (state.gateOutcome() == null) {
            throw new IllegalStateException("gate outcome missing");
        }
        DefinitionSource.Definitions loaded = definitions.reload(state);
        RoutingDecision decision;
        if (state.gateOutcome() == GateOutcome.ERROR || state.semanticAssessment() == null) {
            decision = engine.modelUnavailable(loaded.taxonomy(), loaded.policy());
        } else {
            int noInvestigatorAvailable = loaded.policy().maxInvestigations();
            decision = decider.decide(
                    state.semanticAssessment(), loaded.taxonomy(), loaded.policy(), noInvestigatorAvailable);
        }
        if (decision.action() == RouteAction.LLM_INVESTIGATION) {
            TriageFailure failure = new TriageFailure(
                    FailureCode.INVARIANT_VIOLATED, node().id(), "LLM_INVESTIGATION_WITHOUT_INVESTIGATOR");
            return route(state.withFailure(failure), TriageRoutes.FAILED);
        }
        return route(state.withRoutingDecision(decision), TriageRoutes.DECIDED);
    }
}
