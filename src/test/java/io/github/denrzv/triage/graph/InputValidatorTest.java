package io.github.denrzv.triage.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator(20);

    private static IncomingCustomerRequest request(String triageId, String message, String customerRef) {
        return new IncomingCustomerRequest(triageId, message, Channel.WEB_FORM, customerRef);
    }

    @Test
    void acceptsBoundedRequestAndTrimsMessage() {
        IncomingCustomerRequest request = request("demo-001", "  Card blocked.\n", null);

        assertThat(validator.violation(request)).isEmpty();
        assertThat(validator.normalize(request).message()).isEqualTo("Card blocked.");
        assertThat(validator.violation(request("a.b_c-1", "Tab\tand\r\nnewline ok", "SYN-1"))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "semi;colon", "x/../y"})
    void rejectsInvalidTriageId(String triageId) {
        assertThat(validator.violation(request(triageId, "Card blocked.", null)))
                .contains(InputValidator.TRIAGE_ID_INVALID);
    }

    @Test
    void rejectsTooLongIdentifier() {
        assertThat(validator.violation(request("a".repeat(65), "Card blocked.", null)))
                .contains(InputValidator.TRIAGE_ID_INVALID);
        assertThat(validator.violation(request("a".repeat(64), "Card blocked.", null))).isEmpty();
    }

    @Test
    void rejectsInvalidCustomerReference() {
        assertThat(validator.violation(request("t-1", "Card blocked.", "real customer 42")))
                .contains(InputValidator.CUSTOMER_REF_INVALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void rejectsMissingMessage(String message) {
        assertThat(validator.violation(request("t-1", message, null))).contains(InputValidator.MESSAGE_MISSING);
    }

    @Test
    void enforcesLengthInCodePointsAfterTrimming() {
        assertThat(validator.violation(request("t-1", "x".repeat(20), null))).isEmpty();
        assertThat(validator.violation(request("t-1", "  " + "x".repeat(20) + "  ", null))).isEmpty();
        assertThat(validator.violation(request("t-1", "x".repeat(21), null)))
                .contains(InputValidator.MESSAGE_TOO_LONG);
        assertThat(validator.violation(request("t-1", "😀".repeat(20), null))).isEmpty();
    }

    @Test
    void rejectsControlCharacters() {
        assertThat(validator.violation(request("t-1", "bad\u0000byte", null)))
                .contains(InputValidator.MESSAGE_CONTROL_CHARACTERS);
        assertThat(validator.violation(request("t-1", "esc\u001B[0m", null)))
                .contains(InputValidator.MESSAGE_CONTROL_CHARACTERS);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new InputValidator(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
