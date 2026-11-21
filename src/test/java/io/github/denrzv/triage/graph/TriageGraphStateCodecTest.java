package io.github.denrzv.triage.graph;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.TriageStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TriageGraphStateCodecTest {

    private final TriageGraphStateCodec codec = new TriageGraphStateCodec();

    @Test
    void fullStateRoundTripsIncludingEnumKeyedProbabilities() {
        TriageGraphState state = GraphStateFixtures.fullState();

        TriageGraphState decoded = codec.decode(codec.encode(state));

        assertThat(decoded).isEqualTo(state);
        assertThat(decoded.semanticAssessment().problemDomain().probabilities())
                .isEqualTo(state.semanticAssessment().problemDomain().probabilities());
        assertThat(decoded.trace()).hasSize(5);
    }

    @Test
    void minimalStateWithNullFieldsRoundTrips() {
        TriageGraphState state =
                TriageGraphState.received(new IncomingCustomerRequest(null, null, null, null), null);

        assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
    }

    @Test
    void failedStateWithProviderErrorRoundTrips() {
        TriageGraphState state = TriageGraphState.received(GraphStateFixtures.request(), "v1")
                .withProviderError("JevUnavailableException", "triage-jev-v1")
                .withFailure(new TriageFailure(FailureCode.NODE_FAILED, "ConfidenceGate", "IllegalStateException"))
                .routed(TriageNode.CONFIDENCE_GATE, "failed", TriageNode.BUILD_TRACE.id())
                .withStatus(TriageStatus.FAILED);

        assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
    }

    @Test
    void encodingIsDeterministicAndVersioned() {
        String json = codec.encode(GraphStateFixtures.fullState());

        assertThat(codec.encode(GraphStateFixtures.fullState())).isEqualTo(json);
        assertThat(json).startsWith("{\"schemaVersion\":\"" + TriageGraphStateCodec.SCHEMA_VERSION + "\"");
    }

    @Test
    void rejectsOtherSchemaVersion() {
        String json = codec.encode(GraphStateFixtures.fullState())
                .replace(TriageGraphStateCodec.SCHEMA_VERSION, "triage-graph-state-v0");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void rejectsUnknownFieldsWithoutEchoingContent() {
        String json = codec.encode(GraphStateFixtures.fullState()).replaceFirst("\\{", "{\"extra\":1,");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("charged twice");
    }

    @Test
    void rejectsUnknownEnumConstant() {
        String json = codec.encode(GraphStateFixtures.fullState()).replace("\"PAYMENTS\"", "\"MORTGAGES\"");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValuesViolatingDomainInvariants() {
        String json = codec.encode(GraphStateFixtures.fullState()).replace("\"taxonomyFitNoul\":0.95",
                "\"taxonomyFitNoul\":1.5");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("charged twice");
    }

    @Test
    void rejectsMissingProperty() {
        String json = codec.encode(GraphStateFixtures.fullState()).replace("\"route\":\"decided\",", "");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> codec.decode(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringOmitsCustomerMessage() {
        assertThat(GraphStateFixtures.fullState().toString()).doesNotContain("charged twice");
        assertThat(GraphStateFixtures.request().toString()).doesNotContain("charged twice");
    }

    /** Pins the SAA behaviour the design relies on: default compile config (MemorySaver, state cloning). */
    @Test
    void encodedStateSurvivesDefaultSaaRuntime() throws Exception {
        String json = codec.encode(GraphStateFixtures.fullState());
        StateGraph graph = new StateGraph(() -> {
                    Map<String, KeyStrategy> strategies = new HashMap<>();
                    strategies.put("triage", KeyStrategy.REPLACE);
                    return strategies;
                })
                .addNode("passThrough", node_async(state -> {
                    String current = state.value("triage", String.class).orElseThrow();
                    return Map.of("triage", codec.encode(codec.decode(current)));
                }))
                .addEdge(START, "passThrough")
                .addEdge("passThrough", END);

        Optional<OverAllState> result = graph.compile().invoke(Map.of("triage", json));

        assertThat(result).isPresent();
        assertThat(codec.decode(result.get().value("triage", String.class).orElseThrow()))
                .isEqualTo(GraphStateFixtures.fullState());
    }
}
