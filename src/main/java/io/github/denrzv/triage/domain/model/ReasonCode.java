package io.github.denrzv.triage.domain.model;

/** Synthetic reason code bound to exactly one formal SR type. */
public record ReasonCode(String code, ServiceRequestType serviceRequestType, String description) {

    /** Mandatory {@link ServiceRequestType#UNCLASSIFIED} reason codes used by the Policy Engine. */
    public static final String NONE_FIT = "NONE_FIT";

    public static final String LOW_TAXONOMY_FIT = "LOW_TAXONOMY_FIT";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    public ReasonCode {
        Checks.requireText(code, "code");
        Checks.requireNonNull(serviceRequestType, "serviceRequestType");
        Checks.requireText(description, "description");
    }
}
