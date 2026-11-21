package io.github.denrzv.triage.domain.model;

/** Synthetic semantic intent of a customer message (taxonomy dimension, not a formal SR type). */
public enum PrimaryIntent {
    COMPLAINT,
    INQUIRY,
    ACTION_REQUEST,
    INCIDENT_REPORT
}
