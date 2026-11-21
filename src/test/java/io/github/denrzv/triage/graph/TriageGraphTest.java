package io.github.denrzv.triage.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import io.github.denrzv.triage.domain.model.Channel;
import io.github.denrzv.triage.domain.model.DecisionTraceEvent;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.ReasonCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.TriageStatus;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** End-to-end runs of the real SAA graph with a fake Jev port and shipped definitions; no network, no credentials. */
class TriageGraphTest {

    private static final String STRICT = GraphTestDefinitions.STRICT;

    private static TriageGraph graph(FakeJevPort jev) {
        return new TriageGraph(jev, GraphTestDefinitions.taxonomies(), GraphTestDefinitions.policies());
    }

    private static IncomingCustomerRequest request(String message) {
        return new IncomingCustomerRequest("demo-001", message, Channel.CONTACT_CENTER, null);
    }

    private static List<String> nodes(TriageGraphState state) {
        return state.trace().stream().map(DecisionTraceEvent::node).toList();
    }

    private static List<String> labels(TriageGraphState state) {
        return state.trace().stream().map(DecisionTraceEvent::routeLabel).toList();
    }

    private static final List<String> FULL_PATH = List.of(
            "ValidateInput", "LoadTaxonomy", "JevClassify", "ConfidenceGate", "FinalPolicyGate", "BuildTrace");

    @Test
    void clearComplaintRunsAllSixNodesAndAwaitsHumanApproval() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, RequestedOutcome.REFUND, 0.8, 0.9);

        TriageGraphState result = graph(jev).run(request(GraphStateFixtures.MESSAGE));

        assertThat(result.status()).isEqualTo(TriageStatus.AWAITING_HUMAN);
        assertThat(result.gateOutcome()).isEqualTo(GateOutcome.CLEAR);
        assertThat(result.routingDecision().proposal().proposedType()).isEqualTo(ServiceRequestType.CLAIM);
        assertThat(result.routingDecision().rationaleCodes()).contains(RationaleCode.HUMAN_APPROVAL_REQUIRED);
        assertThat(nodes(result)).isEqualTo(FULL_PATH);
        assertThat(labels(result)).containsExactly("valid", "loaded", "classified", "clear", "decided", "finished");
        assertThat(result.trace().getLast().nextNode()).isEqualTo(TriageRoutes.END);
        assertThat(result.policyVersion()).isEqualTo("v1");
        assertThat(result.taxonomyVersion()).isEqualTo("v1");
        assertThat(result.resolvedModel()).isEqualTo("jev-test-1");
        assertThat(result.failure()).isNull();
        assertThat(jev.requests()).hasSize(1);
    }

    @Test
    void clearInquiryIsClassifiedAsDraftProposal() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);

        TriageGraphState result = graph(jev).run(request("How do I see the synthetic statement for last month?"));

        assertThat(result.status()).isEqualTo(TriageStatus.CLASSIFIED);
        assertThat(result.routingDecision().action()).isEqualTo(RouteAction.DRAFT_PROPOSAL);
    }

    @Test
    void ambiguousRequestEndsInHumanReviewWithExhaustedBudget() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.ACTION_REQUEST, ProblemDomain.ACCOUNTS, RequestedOutcome.PERFORM_ACTION, 0.5, 0.9);

        TriageGraphState result = graph(jev).run(request("Please change something on my synthetic account."), STRICT);

        assertThat(result.gateOutcome()).isEqualTo(GateOutcome.AMBIGUOUS);
        assertThat(labels(result)).contains("ambiguous");
        assertThat(result.status()).isEqualTo(TriageStatus.AWAITING_HUMAN);
        assertThat(result.routingDecision().rationaleCodes())
                .contains(RationaleCode.INVESTIGATION_BUDGET_EXHAUSTED);
        assertThat(result.investigationAttempts()).isZero();
    }

    @Test
    void lowTaxonomyFitAbstains() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, RequestedOutcome.NOT_SPECIFIED, 0.8, 0.2);

        TriageGraphState result = graph(jev).run(request("Something unrelated to any synthetic category."), STRICT);

        assertThat(result.gateOutcome()).isEqualTo(GateOutcome.ABSTAIN);
        assertThat(result.routingDecision().action()).isEqualTo(RouteAction.ABSTAIN);
        assertThat(result.routingDecision().proposal().reasonCode()).isEqualTo(ReasonCode.LOW_TAXONOMY_FIT);
        assertThat(result.status()).isEqualTo(TriageStatus.COMPLETED);
    }

    @Test
    void noMatchingRuleAbstainsWithShippedPolicy() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.OTHER, RequestedOutcome.EXPLAIN, 0.8, 0.9);

        TriageGraphState result = graph(jev).run(request("What is the weather like near the synthetic branch?"));

        assertThat(result.gateOutcome()).isEqualTo(GateOutcome.ABSTAIN);
        assertThat(result.routingDecision().proposal().reasonCode()).isEqualTo(ReasonCode.NONE_FIT);
        assertThat(result.status()).isEqualTo(TriageStatus.COMPLETED);
    }

    @Test
    void providerFailureTakesTheErrorPathToModelUnavailable() {
        FakeJevPort jev = FakeJevPort.throwing(() -> new JevUnavailableException(3, 503, null));

        TriageGraphState result = graph(jev).run(request(GraphStateFixtures.MESSAGE));

        assertThat(labels(result)).containsExactly("valid", "loaded", "error", "error", "decided", "finished");
        assertThat(result.providerError()).isEqualTo("JevUnavailableException");
        assertThat(result.semanticAssessment()).isNull();
        assertThat(result.routingDecision().proposal().reasonCode()).isEqualTo(ReasonCode.MODEL_UNAVAILABLE);
        assertThat(result.status()).isEqualTo(TriageStatus.AWAITING_HUMAN);
    }

    @Test
    void invalidInputFailsWithoutCallingJev() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);

        TriageGraphState result = graph(jev).run(request("   "));

        assertThat(result.status()).isEqualTo(TriageStatus.FAILED);
        assertThat(result.failure().code()).isEqualTo(FailureCode.INPUT_REJECTED);
        assertThat(result.failure().detail()).isEqualTo(InputValidator.MESSAGE_MISSING);
        assertThat(nodes(result)).containsExactly("ValidateInput", "BuildTrace");
        assertThat(result.routingDecision()).isNull();
        assertThat(jev.requests()).isEmpty();
    }

    @Test
    void unknownPolicyVersionFailsWithoutCallingJev() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);

        TriageGraphState result = graph(jev).run(request(GraphStateFixtures.MESSAGE), "v404");

        assertThat(result.status()).isEqualTo(TriageStatus.FAILED);
        assertThat(result.failure().code()).isEqualTo(FailureCode.DEFINITIONS_UNAVAILABLE);
        assertThat(nodes(result)).containsExactly("ValidateInput", "LoadTaxonomy", "BuildTrace");
        assertThat(jev.requests()).isEmpty();
    }

    @Test
    void unexpectedNodeExceptionFailsTheRunWithNodeName() {
        FakeJevPort jev = FakeJevPort.throwing(() -> new IllegalStateException("synthetic bug"));

        TriageGraphState result = graph(jev).run(request(GraphStateFixtures.MESSAGE));

        assertThat(result.status()).isEqualTo(TriageStatus.FAILED);
        assertThat(result.failure())
                .isEqualTo(new TriageFailure(FailureCode.NODE_FAILED, "JevClassify", "IllegalStateException"));
        assertThat(nodes(result)).containsExactly("ValidateInput", "LoadTaxonomy", "JevClassify", "BuildTrace");
    }

    @Test
    void runtimeFailureOutsideNodesIsReturnedAsFailedNotThrown() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);
        TriageGraph broken = new TriageGraph(jev, GraphTestDefinitions.taxonomies(), GraphTestDefinitions.policies(),
                4_000, 12, () -> {
                    throw new IllegalStateException("synthetic runtime failure");
                });

        TriageGraphState result = broken.run(request(GraphStateFixtures.MESSAGE));

        assertThat(result.status()).isEqualTo(TriageStatus.FAILED);
        assertThat(result.failure())
                .isEqualTo(new TriageFailure(FailureCode.GRAPH_RUNTIME_ERROR, null, "IllegalStateException"));
        assertThat(result.trace()).isEmpty();
        assertThat(jev.requests()).isEmpty();
    }

    @Test
    void iterationLimitStopsTheRunAsPartialWithTraceSoFar() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);
        TriageGraph limited = new TriageGraph(
                jev, GraphTestDefinitions.taxonomies(), GraphTestDefinitions.policies(), 4_000, 3, () -> "limited");

        TriageGraphState result = limited.run(request(GraphStateFixtures.MESSAGE));

        assertThat(result.status()).isEqualTo(TriageStatus.PARTIAL);
        assertThat(result.failure())
                .isEqualTo(new TriageFailure(FailureCode.ITERATION_LIMIT_REACHED, null, "recursionLimit=3"));
        assertThat(result.trace()).isNotEmpty().hasSizeLessThan(FULL_PATH.size());
        assertThat(nodes(result)).isEqualTo(FULL_PATH.subList(0, result.trace().size()));
        assertThat(result.routingDecision()).isNull();
        assertThat(limited.checkpoints().list(RunnableConfig.builder().threadId("limited").build())).isEmpty();
    }

    @Test
    void traceMatchesNodesObservedByTheFrameworkAndCheckpointsAreReleased() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.COMPLAINT, ProblemDomain.PAYMENTS, RequestedOutcome.REFUND, 0.8, 0.9);
        TriageGraph graph = new TriageGraph(
                jev, GraphTestDefinitions.taxonomies(), GraphTestDefinitions.policies(), 4_000, 12, () -> "fixed");
        TriageGraphStateCodec codec = new TriageGraphStateCodec();
        String initial = codec.encode(TriageGraphState.received(request(GraphStateFixtures.MESSAGE), null));
        RunnableConfig streamConfig = RunnableConfig.builder().threadId("observed").build();

        List<String> observed = graph.compiled()
                .stream(Map.of(GuardedNode.STATE_KEY, initial), streamConfig)
                .map(NodeOutput::node)
                .filter(node -> !StateGraph.START.equals(node) && !StateGraph.END.equals(node))
                .collectList()
                .block();
        TriageGraphState result = graph.run(request(GraphStateFixtures.MESSAGE));

        assertThat(observed).isEqualTo(nodes(result));
        assertThat(graph.checkpoints().list(RunnableConfig.builder().threadId("fixed").build())).isEmpty();
    }

    @Test
    void compiledGraphIsReusableForConcurrentRuns() {
        FakeJevPort jev = FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9);
        TriageGraph graph = graph(jev);

        List<TriageGraphState> results = IntStream.range(0, 16)
                .parallel()
                .mapToObj(i -> graph.run(new IncomingCustomerRequest(
                        "run-" + i, "Synthetic question number " + i, Channel.WEB_FORM, null)))
                .toList();

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(TriageStatus.CLASSIFIED);
            assertThat(nodes(result)).isEqualTo(FULL_PATH);
        });
        assertThat(results).extracting(TriageGraphState::triageId).doesNotHaveDuplicates();
        assertThat(jev.requests()).hasSize(16);
    }

    @Test
    void rejectsNullRequestAndInvalidConfiguration() {
        TriageGraph graph = graph(FakeJevPort.returning(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.8, 0.9));

        assertThatThrownBy(() -> graph.run(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TriageGraph(
                        null, GraphTestDefinitions.taxonomies(), GraphTestDefinitions.policies()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TriageGraph(FakeJevPort.throwing(IllegalStateException::new), GraphTestDefinitions.taxonomies(),
                        GraphTestDefinitions.policies(), 4_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
