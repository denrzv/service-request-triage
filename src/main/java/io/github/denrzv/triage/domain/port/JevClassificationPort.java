package io.github.denrzv.triage.domain.port;

import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;

/**
 * Lower-level port for typed Jev judgments. It makes no taxonomy, routing or task decisions; a taxonomy-aware
 * {@code ClassificationModelPort} may be layered on top later.
 *
 * @throws JevClientException terminal request/auth/validation failure (not retried)
 * @throws JevUnavailableException transient failures persisted after bounded retries
 * @throws JevProtocolException a successful response that is missing or has malformed answers
 */
public interface JevClassificationPort {

    JevClassificationResult classify(JevClassificationRequest request);
}
