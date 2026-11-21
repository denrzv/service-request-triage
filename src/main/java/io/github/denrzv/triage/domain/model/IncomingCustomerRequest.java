package io.github.denrzv.triage.domain.model;

/**
 * A synthetic English customer request submitted for triage (docs/contracts.md section 1). This is an
 * <em>unvalidated</em> carrier: every field may be {@code null} or malformed, because the graph's {@code ValidateInput}
 * node owns the input rules and turns a bad request into a {@code FAILED} result instead of an exception.
 * {@code message} is untrusted text; {@link #toString()} omits it so it cannot leak into logs.
 *
 * @param triageId caller-supplied run identifier and future idempotency key
 * @param message untrusted synthetic English text
 * @param channel optional intake channel (context only)
 * @param syntheticCustomerId optional synthetic customer reference, never a real identifier
 */
public record IncomingCustomerRequest(String triageId, String message, Channel channel, String syntheticCustomerId) {

    @Override
    public String toString() {
        return "IncomingCustomerRequest[triageId=" + triageId + ", channel=" + channel + ", messageLength="
                + (message == null ? "null" : String.valueOf(message.length())) + "]";
    }
}
