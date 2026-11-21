package io.github.denrzv.triage.domain.model;

/** Explicit Policy Engine outcomes. {@link #ABSTAIN} and {@link #HUMAN_REVIEW} are normal, safe results. */
public enum RouteAction {
    DRAFT_PROPOSAL,
    LLM_INVESTIGATION,
    HUMAN_REVIEW,
    ABSTAIN
}
