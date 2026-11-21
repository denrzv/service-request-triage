package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import org.junit.jupiter.api.Test;

class KeywordRulesAssessorTest {

    private final KeywordRulesAssessor assessor = new KeywordRulesAssessor();

    @Test
    void complaintAboutPaymentWithRefund() {
        SemanticAssessment a = assessor.assess("I am really annoyed that the grocery charge was taken twice, refund it.");

        assertThat(a.primaryIntent().selected()).isEqualTo(PrimaryIntent.COMPLAINT);
        assertThat(a.problemDomain().selected()).isEqualTo(ProblemDomain.PAYMENTS);
        assertThat(a.requestedOutcome().selected()).isEqualTo(RequestedOutcome.REFUND);
        assertThat(a.refundRequestedNoul()).isEqualTo(1.0);
        assertThat(a.taxonomyFitNoul()).isEqualTo(1.0);
    }

    @Test
    void incidentInDigitalChannelSetsTechnicalSignal() {
        SemanticAssessment a = assessor.assess("The app crashes as soon as I open the loans screen.");

        assertThat(a.primaryIntent().selected()).isEqualTo(PrimaryIntent.INCIDENT_REPORT);
        assertThat(a.problemDomain().selected()).isEqualTo(ProblemDomain.DIGITAL_CHANNELS);
        assertThat(a.technicalProblemNoul()).isEqualTo(1.0);
        assertThat(a.refundRequestedNoul()).isZero();
    }

    @Test
    void questionWithoutKeywordsFallsBackToInquiryWithLowFit() {
        SemanticAssessment a = assessor.assess("Is it going to snow on the mountain pass?");

        assertThat(a.primaryIntent().selected()).isEqualTo(PrimaryIntent.INQUIRY);
        assertThat(a.problemDomain().selected()).isEqualTo(ProblemDomain.OTHER);
        assertThat(a.taxonomyFitNoul()).isZero();
    }

    @Test
    void statementWithoutKeywordsFallsBackToActionRequest() {
        SemanticAssessment a = assessor.assess("Something happened with the thing again.");

        assertThat(a.primaryIntent().selected()).isEqualTo(PrimaryIntent.ACTION_REQUEST);
        assertThat(a.requestedOutcome().selected()).isEqualTo(RequestedOutcome.NOT_SPECIFIED);
    }

    @Test
    void outputIsOneHotWithFullConfidenceAndMultiIntentMarker() {
        SemanticAssessment a = assessor.assess("Please update my postal address and also tell me the savings rate.");

        assertThat(a.primaryIntent().confidence()).isEqualTo(1.0);
        assertThat(a.primaryIntent().probabilities().values()).containsOnly(0.0, 1.0);
        assertThat(a.primaryIntent().margin()).isEqualTo(1.0);
        assertThat(a.multiIntentNoul()).isEqualTo(1.0);
        assertThat(a.modelUsed()).isEqualTo(KeywordRulesAssessor.MODEL);
        assertThat(a.questionSetVersion()).isEqualTo(KeywordRulesAssessor.VERSION);
    }

    @Test
    void isDeterministicAndRejectsBlankInput() {
        String message = "What is the fee for an international transfer?";

        assertThat(assessor.assess(message)).isEqualTo(assessor.assess(message));
        assertThatThrownBy(() -> assessor.assess(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
