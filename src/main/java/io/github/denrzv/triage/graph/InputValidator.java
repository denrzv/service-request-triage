package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PoC input bounds for {@code ValidateInput}. These are safety limits, not business rules: identifiers must match
 * {@code [A-Za-z0-9._-]{1,64}}; the message must be non-blank after trimming, at most {@code maxMessageLength} code
 * points and free of control characters other than tab, CR and LF. Violations are returned as codes that never contain
 * the input.
 */
public final class InputValidator {

    /** Default maximum message length in Unicode code points. */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 4_000;

    public static final String TRIAGE_ID_INVALID = "TRIAGE_ID_INVALID";
    public static final String MESSAGE_MISSING = "MESSAGE_MISSING";
    public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    public static final String MESSAGE_CONTROL_CHARACTERS = "MESSAGE_CONTROL_CHARACTERS";
    public static final String CUSTOMER_REF_INVALID = "CUSTOMER_REF_INVALID";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final int maxMessageLength;

    public InputValidator(int maxMessageLength) {
        if (maxMessageLength < 1) {
            throw new IllegalArgumentException("maxMessageLength must be positive");
        }
        this.maxMessageLength = maxMessageLength;
    }

    /** Returns the first violated rule, or empty when the request is acceptable. */
    public Optional<String> violation(IncomingCustomerRequest request) {
        if (request.triageId() == null || !IDENTIFIER.matcher(request.triageId()).matches()) {
            return Optional.of(TRIAGE_ID_INVALID);
        }
        if (request.syntheticCustomerId() != null && !IDENTIFIER.matcher(request.syntheticCustomerId()).matches()) {
            return Optional.of(CUSTOMER_REF_INVALID);
        }
        String message = request.message() == null ? "" : request.message().strip();
        if (message.isEmpty()) {
            return Optional.of(MESSAGE_MISSING);
        }
        if (message.codePointCount(0, message.length()) > maxMessageLength) {
            return Optional.of(MESSAGE_TOO_LONG);
        }
        boolean forbiddenControl = message.codePoints()
                .anyMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r');
        if (forbiddenControl) {
            return Optional.of(MESSAGE_CONTROL_CHARACTERS);
        }
        return Optional.empty();
    }

    /** Request with the message trimmed; call only after {@link #violation} returned empty. */
    public IncomingCustomerRequest normalize(IncomingCustomerRequest request) {
        return new IncomingCustomerRequest(
                request.triageId(), request.message().strip(), request.channel(), request.syntheticCustomerId());
    }
}
