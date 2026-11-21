package io.github.denrzv.triage.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.assessment.JevResults;
import io.github.denrzv.triage.application.assessment.TriageQuestionSets;
import io.github.denrzv.triage.domain.model.DecisionTraceEvent;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.TriageStatus;
import io.github.denrzv.triage.domain.port.JevClientException;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntakeNodesTest {

    private static final String MODEL = JevResults.MODEL;

    private static TriageGraphState received() {
        return TriageGraphState.received(GraphStateFixtures.request(), null);
    }

    private static TriageGraphState loaded() {
        return received().withDefinitions("v1", "v1");
    }

    private static DecisionTraceEvent onlyEvent(TriageGraphState state) {
        assertThat(state.trace()).hasSize(1);
        return state.trace().getFirst();
    }

    // ---- ValidateInput -----------------------------------------------------------------------------------------

    @Test
    void validInputIsNormalizedAndRoutedToLoadTaxonomy() {
        TriageGraphState in = TriageGraphState.received(
                new IncomingCustomerRequest("demo-001", "  " + GraphStateFixtures.MESSAGE + "  ", null, null), null);

        TriageGraphState out = new ValidateInputNode(new InputValidator(4_000)).apply(in);

        assertThat(out.input().message()).isEqualTo(GraphStateFixtures.MESSAGE);
        assertThat(out.route()).isEqualTo(TriageRoutes.VALID);
        assertThat(out.failure()).isNull();
        assertThat(onlyEvent(out))
                .isEqualTo(new DecisionTraceEvent(1, "ValidateInput", "valid", "LoadTaxonomy"));
    }

    @Test
    void invalidInputIsRejectedWithCodeAndRoutedToBuildTrace() {
        TriageGraphState in = TriageGraphState.received(new IncomingCustomerRequest("bad id", "x", null, null), null);

        TriageGraphState out = new ValidateInputNode(new InputValidator(4_000)).apply(in);

        assertThat(out.failure())
                .isEqualTo(new TriageFailure(FailureCode.INPUT_REJECTED, "ValidateInput", "TRIAGE_ID_INVALID"));
        assertThat(onlyEvent(out).nextNode()).isEqualTo("BuildTrace");
        assertThat(out.route()).isEqualTo(TriageRoutes.INVALID);
    }

    // ---- LoadTaxonomy ------------------------------------------------------------------------------------------

    @Test
    void activePolicyAndItsTaxonomyAreRecorded() {
        TriageGraphState out = new LoadTaxonomyNode(GraphTestDefinitions.source()).apply(received());

        assertThat(out.policyVersion()).isEqualTo("v1");
        assertThat(out.taxonomyVersion()).isEqualTo("v1");
        assertThat(onlyEvent(out)).isEqualTo(new DecisionTraceEvent(1, "LoadTaxonomy", "loaded", "JevClassify"));
    }

    @Test
    void explicitPolicyVersionIsHonoured() {
        TriageGraphState in = TriageGraphState.received(GraphStateFixtures.request(), GraphTestDefinitions.STRICT);

        assertThat(new LoadTaxonomyNode(GraphTestDefinitions.source()).apply(in).policyVersion())
                .isEqualTo(GraphTestDefinitions.STRICT);
    }

    @Test
    void unknownPolicyVersionFailsWithoutFallback() {
        TriageGraphState in = TriageGraphState.received(GraphStateFixtures.request(), "v9");

        TriageGraphState out = new LoadTaxonomyNode(GraphTestDefinitions.source()).apply(in);

        assertThat(out.failure()).isEqualTo(new TriageFailure(
                FailureCode.DEFINITIONS_UNAVAILABLE, "LoadTaxonomy", "DefinitionNotFoundException"));
        assertThat(out.policyVersion()).isEqualTo("v9");
        assertThat(onlyEvent(out).nextNode()).isEqualTo("BuildTrace");
    }

    // ---- JevClassify -------------------------------------------------------------------------------------------

    @Test
    void oneJevCallWithMessageChannelAndQuestionSet() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, RequestedOutcome.REFUND, 0.8, 0.9);

        TriageGraphState out = new JevClassifyNode(jev, new JevAssessmentMapper()).apply(loaded());

        assertThat(jev.requests()).singleElement().satisfies(request -> {
            assertThat(request.message()).isEqualTo(GraphStateFixtures.MESSAGE);
            assertThat(request.channel()).isEqualTo("INTERNET_BANK");
            assertThat(request.questionSet().version()).isEqualTo(TriageQuestionSets.V1);
        });
        assertThat(out.semanticAssessment().primaryIntent().selected()).isEqualTo(PrimaryIntent.COMPLAINT);
        assertThat(out.resolvedModel()).isEqualTo(MODEL);
        assertThat(out.questionSetVersion()).isEqualTo(TriageQuestionSets.V1);
        assertThat(out.providerError()).isNull();
        assertThat(onlyEvent(out))
                .isEqualTo(new DecisionTraceEvent(1, "JevClassify", "classified", "ConfidenceGate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"client", "unavailable", "protocol"})
    void providerFailuresTakeTheErrorRouteWithoutDefaults(String kind) {
        Supplier<RuntimeException> failure = switch (kind) {
            case "client" -> () -> new JevClientException(401, "unauthorized");
            case "unavailable" -> () -> new JevUnavailableException(3, 503, null);
            default -> () -> new JevProtocolException("missing answers");
        };

        TriageGraphState out =
                new JevClassifyNode(FakeJevPort.throwing(failure), new JevAssessmentMapper()).apply(loaded());

        assertThat(out.semanticAssessment()).isNull();
        assertThat(out.resolvedModel()).isNull();
        assertThat(out.providerError()).endsWith("Exception").doesNotContain("unauthorized");
        assertThat(out.failure()).isNull();
        assertThat(onlyEvent(out)).isEqualTo(new DecisionTraceEvent(1, "JevClassify", "error", "ConfidenceGate"));
    }

    @Test
    void mapperRejectionOfMalformedAnswersTakesTheErrorRoute() {
        Map<String, JevAnswer> answers = new LinkedHashMap<>(
                JevResults.answers(PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9));
        answers.remove(TriageQuestionSets.TAXONOMY_FIT);

        TriageGraphState out =
                new JevClassifyNode(FakeJevPort.returning(answers), new JevAssessmentMapper()).apply(loaded());

        assertThat(out.providerError()).isEqualTo("JevProtocolException");
        assertThat(out.route()).isEqualTo(TriageRoutes.ERROR);
    }

    // ---- GuardedNode -------------------------------------------------------------------------------------------

    @Test
    void unexpectedNodeExceptionBecomesNodeFailedRoutedToBuildTrace() {
        TriageGraphStateCodec codec = new TriageGraphStateCodec();
        FakeJevPort jev = FakeJevPort.throwing(() -> new IllegalStateException("secret customer detail"));
        GuardedNode node = new GuardedNode(new JevClassifyNode(jev, new JevAssessmentMapper()), codec);

        Map<String, Object> update =
                node.apply(new OverAllState(Map.of(GuardedNode.STATE_KEY, codec.encode(loaded()))));

        TriageGraphState out = codec.decode((String) update.get(GuardedNode.STATE_KEY));
        assertThat(out.failure())
                .isEqualTo(new TriageFailure(FailureCode.NODE_FAILED, "JevClassify", "IllegalStateException"));
        assertThat(out.trace().getLast()).isEqualTo(new DecisionTraceEvent(1, "JevClassify", "failed", "BuildTrace"));
        assertThat(out.status()).isEqualTo(TriageStatus.RECEIVED);
        assertThat(update.get(GuardedNode.STATE_KEY).toString()).doesNotContain("secret customer detail");
    }
}
