package io.github.denrzv.triage.domain.port;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;

/** Read-only access to versioned classification policies. Every served policy is compatible with its taxonomy. */
public interface ClassificationPolicyPort {

    /**
     * Returns the policy with exactly this version.
     *
     * @throws DefinitionNotFoundException if the version is not loaded
     */
    ClassificationPolicy get(String version);

    /** Returns the policy selected by configuration for new triage runs. */
    ClassificationPolicy active();
}
