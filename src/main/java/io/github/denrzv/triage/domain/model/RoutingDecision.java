package io.github.denrzv.triage.domain.model;

import java.util.List;

/**
 * Versioned Policy Engine decision (ADR-003/ADR-004). {@code humanApprovalRequired} is true exactly for
 * {@link RouteAction#HUMAN_REVIEW}; {@link RouteAction#ABSTAIN} always proposes {@link ServiceRequestType#UNCLASSIFIED}
 * and draft/investigation routes never do.
 */
public record RoutingDecision(
        RouteAction action,
        ClassificationProposal proposal,
        List<String> ruleIds,
        List<RationaleCode> rationaleCodes,
        String policyVersion,
        boolean policyCalibrated,
        boolean humanApprovalRequired) {

    public RoutingDecision {
        Checks.requireNonNull(action, "action");
        Checks.requireNonNull(proposal, "proposal");
        ruleIds = Checks.textList(ruleIds, "ruleIds");
        rationaleCodes = List.copyOf(Checks.requireNonNull(rationaleCodes, "rationaleCodes"));
        if (rationaleCodes.isEmpty()) {
            throw new IllegalArgumentException("rationaleCodes must not be empty");
        }
        Checks.requireText(policyVersion, "policyVersion");
        if (humanApprovalRequired != (action == RouteAction.HUMAN_REVIEW)) {
            throw new IllegalArgumentException("humanApprovalRequired must be true exactly for HUMAN_REVIEW");
        }
        boolean unclassified = proposal.proposedType() == ServiceRequestType.UNCLASSIFIED;
        if (action == RouteAction.ABSTAIN && !unclassified) {
            throw new IllegalArgumentException("ABSTAIN requires an UNCLASSIFIED proposal");
        }
        if (unclassified && (action == RouteAction.DRAFT_PROPOSAL || action == RouteAction.LLM_INVESTIGATION)) {
            throw new IllegalArgumentException(action + " requires a classified proposal");
        }
    }
}
