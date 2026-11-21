package io.github.denrzv.triage.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned, synthetic taxonomy: an English definition for every constant of each dimension and the reason codes
 * allowed per formal SR type. Illustrative only; not a real bank catalogue.
 */
public record Taxonomy(
        String version,
        Map<PrimaryIntent, String> primaryIntents,
        Map<ProblemDomain, String> problemDomains,
        Map<RequestedOutcome, String> requestedOutcomes,
        Map<ServiceRequestType, String> serviceRequestTypes,
        List<ReasonCode> reasonCodes) {

    private static final List<String> MANDATORY_UNCLASSIFIED_CODES =
            List.of(ReasonCode.NONE_FIT, ReasonCode.LOW_TAXONOMY_FIT, ReasonCode.MODEL_UNAVAILABLE);

    public Taxonomy {
        Checks.requireText(version, "version");
        primaryIntents = Checks.completeDefinitions(PrimaryIntent.class, primaryIntents, "primaryIntents");
        problemDomains = Checks.completeDefinitions(ProblemDomain.class, problemDomains, "problemDomains");
        requestedOutcomes =
                Checks.completeDefinitions(RequestedOutcome.class, requestedOutcomes, "requestedOutcomes");
        serviceRequestTypes =
                Checks.completeDefinitions(ServiceRequestType.class, serviceRequestTypes, "serviceRequestTypes");
        reasonCodes = List.copyOf(Checks.requireNonNull(reasonCodes, "reasonCodes"));
        requireUniqueCodes(reasonCodes);
        for (String mandatory : MANDATORY_UNCLASSIFIED_CODES) {
            requireCode(reasonCodes, mandatory, ServiceRequestType.UNCLASSIFIED);
        }
    }

    public Optional<ReasonCode> reasonCode(String code) {
        return reasonCodes.stream().filter(candidate -> candidate.code().equals(code)).findFirst();
    }

    /** Returns the reason code, requiring that it exists and belongs to {@code type}. */
    public ReasonCode requireReasonCode(String code, ServiceRequestType type) {
        return requireCode(reasonCodes, code, type);
    }

    private static ReasonCode requireCode(List<ReasonCode> codes, String code, ServiceRequestType type) {
        ReasonCode found = codes.stream()
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown reason code " + code));
        if (found.serviceRequestType() != type) {
            throw new IllegalArgumentException(
                    "reason code " + code + " belongs to " + found.serviceRequestType() + ", not " + type);
        }
        return found;
    }

    private static void requireUniqueCodes(List<ReasonCode> codes) {
        Set<String> seen = new HashSet<>();
        for (ReasonCode code : codes) {
            if (!seen.add(code.code())) {
                throw new IllegalArgumentException("duplicate reason code " + code.code());
            }
        }
    }
}
