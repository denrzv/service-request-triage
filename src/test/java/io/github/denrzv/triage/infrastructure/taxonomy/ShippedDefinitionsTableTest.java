package io.github.denrzv.triage.infrastructure.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Decision table for the shipped, uncalibrated v1 definitions loaded from their JSON files. */
class ShippedDefinitionsTableTest {

    private static final DefinitionJsonMapper MAPPER = new DefinitionJsonMapper(new ObjectMapper());
    private static final Taxonomy TAXONOMY = MAPPER.readTaxonomy(DefinitionJsonMapperTest.resource("/taxonomy/v1.json"));
    private static final ClassificationPolicy POLICY = MAPPER.readPolicy(DefinitionJsonMapperTest.resource("/policy/v1.json"));

    private final ClassificationPolicyEngine engine = new ClassificationPolicyEngine();

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            textBlock =
                    """
            # scenario,                 intent,          domain,           outcome,        refund, tech, multi, fit, action,         type,                reason,                    alternative
            complaint payments,         COMPLAINT,       PAYMENTS,         REFUND,         0.9,    0.0,  0.1,   0.9, HUMAN_REVIEW,   CLAIM,               PAYMENT_DISPUTE,           ''
            complaint support,          COMPLAINT,       SUPPORT_SERVICE,  INVESTIGATE,    0.0,    0.0,  0.1,   0.9, HUMAN_REVIEW,   CLAIM,               SERVICE_QUALITY_COMPLAINT, ''
            complaint other domain,     COMPLAINT,       OTHER,            NOT_SPECIFIED,  0.0,    0.0,  0.1,   0.9, HUMAN_REVIEW,   CLAIM,               GENERAL_COMPLAINT,         ''
            mixed complaint technical,  COMPLAINT,       DIGITAL_CHANNELS, FIX_PROBLEM,    0.0,    0.9,  0.9,   0.9, HUMAN_REVIEW,   CLAIM,               DIGITAL_CHANNEL_COMPLAINT, TECHNICAL_REQUEST
            technical issue,            INCIDENT_REPORT, DIGITAL_CHANNELS, FIX_PROBLEM,    0.0,    0.9,  0.1,   0.9, DRAFT_PROPOSAL, TECHNICAL_REQUEST,   TECHNICAL_MALFUNCTION,     ''
            refund request payments,    ACTION_REQUEST,  PAYMENTS,         REFUND,         0.9,    0.0,  0.1,   0.9, HUMAN_REVIEW,   CLAIM,               PAYMENT_DISPUTE,           SERVICE_REQUEST
            service action,             ACTION_REQUEST,  ACCOUNTS,         PERFORM_ACTION, 0.0,    0.0,  0.1,   0.9, DRAFT_PROPOSAL, SERVICE_REQUEST,     SERVICE_ACTION,            ''
            inquiry,                    INQUIRY,         ACCOUNTS,         EXPLAIN,        0.0,    0.0,  0.1,   0.9, DRAFT_PROPOSAL, INFORMATION_REQUEST, GENERAL_INFORMATION,       ''
            none fit inquiry other,     INQUIRY,         OTHER,            EXPLAIN,        0.0,    0.0,  0.1,   0.9, ABSTAIN,        UNCLASSIFIED,        NONE_FIT,                  ''
            none fit incident no signal, INCIDENT_REPORT, ACCOUNTS,        NOT_SPECIFIED,  0.0,    0.1,  0.1,   0.9, ABSTAIN,        UNCLASSIFIED,        NONE_FIT,                  ''
            """)
    void shippedV1Table(
            String scenario,
            PrimaryIntent intent,
            ProblemDomain domain,
            RequestedOutcome outcome,
            double refund,
            double technical,
            double multiIntent,
            double taxonomyFit,
            RouteAction expectedAction,
            ServiceRequestType expectedType,
            String expectedReason,
            String expectedAlternative) {
        SemanticAssessment assessment = new SemanticAssessment(
                new ChoiceJudgment<>(intent, 0.8, Map.of(intent, 0.8)),
                new ChoiceJudgment<>(domain, 0.8, Map.of(domain, 0.8)),
                new ChoiceJudgment<>(outcome, 0.8, Map.of(outcome, 0.8)),
                refund,
                technical,
                multiIntent,
                taxonomyFit,
                "jev-test",
                "qs-test");

        RoutingDecision decision = engine.decide(assessment, TAXONOMY, POLICY, 0);

        assertThat(decision.action()).as(scenario).isEqualTo(expectedAction);
        assertThat(decision.proposal().proposedType()).as(scenario).isEqualTo(expectedType);
        assertThat(decision.proposal().reasonCode()).as(scenario).isEqualTo(expectedReason);
        if (expectedAlternative.isEmpty()) {
            assertThat(decision.proposal().alternatives()).as(scenario).isEmpty();
        } else {
            assertThat(decision.proposal().alternatives())
                    .as(scenario)
                    .containsExactly(ServiceRequestType.valueOf(expectedAlternative));
        }
        assertThat(decision.policyVersion()).isEqualTo("v1");
        assertThat(decision.proposal().taxonomyVersion()).isEqualTo("v1");
        assertThat(decision.rationaleCodes()).contains(RationaleCode.THRESHOLDS_UNCALIBRATED);
    }
}
