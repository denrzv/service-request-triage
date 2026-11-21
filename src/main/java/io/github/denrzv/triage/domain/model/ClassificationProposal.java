package io.github.denrzv.triage.domain.model;

import java.util.HashSet;
import java.util.List;

/** Formal SR type proposal under an explicit taxonomy version (ADR-003); separate from the semantic assessment. */
public record ClassificationProposal(
        String taxonomyVersion,
        ServiceRequestType proposedType,
        String reasonCode,
        List<ServiceRequestType> alternatives,
        List<String> supportingSignals) {

    public ClassificationProposal {
        Checks.requireText(taxonomyVersion, "taxonomyVersion");
        Checks.requireNonNull(proposedType, "proposedType");
        Checks.requireText(reasonCode, "reasonCode");
        alternatives = List.copyOf(Checks.requireNonNull(alternatives, "alternatives"));
        if (alternatives.contains(proposedType) || new HashSet<>(alternatives).size() != alternatives.size()) {
            throw new IllegalArgumentException("alternatives must be distinct and exclude the proposed type");
        }
        supportingSignals = Checks.textList(supportingSignals, "supportingSignals");
    }
}
