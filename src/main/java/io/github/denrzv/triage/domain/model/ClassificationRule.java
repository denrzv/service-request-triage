package io.github.denrzv.triage.domain.model;

/** One row of the ordered rule table: when the condition matches, propose this SR type and reason code. */
public record ClassificationRule(
        String id, RuleCondition when, ServiceRequestType serviceRequestType, String reasonCode) {

    public ClassificationRule {
        Checks.requireText(id, "id");
        Checks.requireNonNull(when, "when");
        Checks.requireNonNull(serviceRequestType, "serviceRequestType");
        Checks.requireText(reasonCode, "reasonCode");
        if (serviceRequestType == ServiceRequestType.UNCLASSIFIED) {
            throw new IllegalArgumentException("rule " + id + " must not propose UNCLASSIFIED");
        }
    }
}
