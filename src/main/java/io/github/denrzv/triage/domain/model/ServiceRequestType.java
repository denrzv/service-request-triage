package io.github.denrzv.triage.domain.model;

/**
 * Formal, synthetic Service Request type proposed by the Policy Engine. {@link #UNCLASSIFIED} marks abstention or a
 * case that needs manual triage; it is never produced by a classification rule.
 */
public enum ServiceRequestType {
    CLAIM,
    TECHNICAL_REQUEST,
    INFORMATION_REQUEST,
    SERVICE_REQUEST,
    UNCLASSIFIED
}
