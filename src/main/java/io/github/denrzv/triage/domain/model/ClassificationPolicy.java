package io.github.denrzv.triage.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned, synthetic classification and routing policy evaluated by the pure Java Policy Engine. Rule order is
 * significant: the first matching rule determines the proposal. {@code calibrated=false} marks thresholds that have
 * not been derived from evaluation data.
 */
public record ClassificationPolicy(
        String version,
        String taxonomyVersion,
        boolean calibrated,
        PolicyThresholds thresholds,
        RouteAction lowTaxonomyFitAction,
        RouteAction noRuleMatchedAction,
        int maxInvestigations,
        Set<ServiceRequestType> humanApprovalTypes,
        List<ClassificationRule> rules) {

    /** Hard upper bound on LLM investigations per triage, whatever a policy file says. */
    public static final int MAX_INVESTIGATIONS_LIMIT = 3;

    public ClassificationPolicy {
        Checks.requireText(version, "version");
        Checks.requireText(taxonomyVersion, "taxonomyVersion");
        Checks.requireNonNull(thresholds, "thresholds");
        requireAbstainOrReview(lowTaxonomyFitAction, "lowTaxonomyFitAction");
        requireAbstainOrReview(noRuleMatchedAction, "noRuleMatchedAction");
        Checks.requireRange(maxInvestigations, 0, MAX_INVESTIGATIONS_LIMIT, "maxInvestigations");
        humanApprovalTypes = Checks.enumSet(ServiceRequestType.class, humanApprovalTypes, "humanApprovalTypes");
        rules = List.copyOf(Checks.requireNonNull(rules, "rules"));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
        Set<String> ids = new HashSet<>();
        for (ClassificationRule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id " + rule.id());
            }
        }
    }

    /** Validates that this policy targets {@code taxonomy} and only references its reason codes with matching types. */
    public void requireCompatibleWith(Taxonomy taxonomy) {
        Checks.requireNonNull(taxonomy, "taxonomy");
        if (!taxonomyVersion.equals(taxonomy.version())) {
            throw new IllegalArgumentException("policy " + version + " targets taxonomy " + taxonomyVersion
                    + " but taxonomy " + taxonomy.version() + " was supplied");
        }
        for (ClassificationRule rule : rules) {
            try {
                taxonomy.requireReasonCode(rule.reasonCode(), rule.serviceRequestType());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rule " + rule.id() + ": " + e.getMessage(), e);
            }
        }
    }

    private static void requireAbstainOrReview(RouteAction action, String name) {
        Checks.requireNonNull(action, name);
        if (action != RouteAction.ABSTAIN && action != RouteAction.HUMAN_REVIEW) {
            throw new IllegalArgumentException(name + " must be ABSTAIN or HUMAN_REVIEW");
        }
    }
}
