package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.TriageStatus;

/**
 * {@code BuildTrace}: derives the execution status and appends the terminal transition to {@code END}.
 *
 * <ul>
 *   <li>any recorded failure &rarr; {@code FAILED} (route {@code failed});
 *   <li>{@code HUMAN_REVIEW} &rarr; {@code AWAITING_HUMAN};
 *   <li>{@code ABSTAIN} &rarr; {@code COMPLETED};
 *   <li>{@code DRAFT_PROPOSAL} &rarr; {@code CLASSIFIED} (no draft is created before FEATURE-010).
 * </ul>
 *
 * It changes only execution state; no Service Request status exists or is mutated here.
 */
final class BuildTraceNode implements TriageStep {

    @Override
    public TriageNode node() {
        return TriageNode.BUILD_TRACE;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        if (state.failure() != null) {
            return route(state.withStatus(TriageStatus.FAILED), TriageRoutes.FAILED);
        }
        if (state.routingDecision() == null) {
            throw new IllegalStateException("run reached BuildTrace without decision or failure");
        }
        TriageStatus status = switch (state.routingDecision().action()) {
            case HUMAN_REVIEW -> TriageStatus.AWAITING_HUMAN;
            case ABSTAIN -> TriageStatus.COMPLETED;
            case DRAFT_PROPOSAL -> TriageStatus.CLASSIFIED;
            case LLM_INVESTIGATION -> throw new IllegalStateException("investigation route reached BuildTrace");
        };
        return route(state.withStatus(status), TriageRoutes.FINISHED);
    }
}
