package io.github.denrzv.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TriageExecutionTypesTest {

    @Test
    void requestIsAnUnvalidatedCarrierThatHidesTheMessage() {
        IncomingCustomerRequest request =
                new IncomingCustomerRequest("t-1", "My synthetic transfer is missing.", Channel.WEB_FORM, null);

        assertThat(request.toString()).doesNotContain("synthetic transfer").contains("messageLength=33");
        assertThat(new IncomingCustomerRequest(null, null, null, null).toString()).contains("messageLength=null");
    }

    @Test
    void traceEventValidatesFields() {
        DecisionTraceEvent event = new DecisionTraceEvent(1, "ValidateInput", "valid", "LoadTaxonomy");

        assertThat(event.sequence()).isOne();
        assertThatThrownBy(() -> new DecisionTraceEvent(0, "n", "l", "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionTraceEvent(1, " ", "l", "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionTraceEvent(1, "n", null, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionTraceEvent(1, "n", "l", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusesFollowTheContract() {
        assertThat(TriageStatus.values())
                .containsExactly(
                        TriageStatus.RECEIVED,
                        TriageStatus.CLASSIFIED,
                        TriageStatus.INVESTIGATING,
                        TriageStatus.AWAITING_HUMAN,
                        TriageStatus.COMPLETED,
                        TriageStatus.PARTIAL,
                        TriageStatus.FAILED);
    }
}
