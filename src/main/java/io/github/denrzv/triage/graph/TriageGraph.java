package io.github.denrzv.triage.graph;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.IncomingCustomerRequest;
import io.github.denrzv.triage.domain.model.TriageStatus;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Conditional triage {@code StateGraph} (Spring AI Alibaba Graph) compiled once and reusable for concurrent runs:
 * nodes are stateless and every run has its own state and checkpoint thread.
 *
 * <pre>
 * START -> ValidateInput -valid-> LoadTaxonomy -loaded-> JevClassify -classified|error-> ConfidenceGate
 * ConfidenceGate -clear|ambiguous|abstain|error-> FinalPolicyGate -decided-> BuildTrace -> END
 * invalid / failed (any node) -> BuildTrace
 * </pre>
 *
 * <p>{@link #run} never throws for request, definition or provider problems. A run that ends before
 * {@code BuildTrace} (the iteration limit is the only such cause in v1: no interrupts are configured) is returned as
 * {@code PARTIAL}/{@code ITERATION_LIMIT_REACHED}; a framework failure as {@code FAILED}/{@code GRAPH_RUNTIME_ERROR}.
 *
 * <p>Known limitation (SAA 1.1.2.0): a JVM {@link Error} thrown inside a node is not propagated and can block
 * {@link #run}; node guards intentionally catch only {@link RuntimeException}. Callers that need a hard deadline must
 * bound the call themselves (FEATURE-012).
 *
 * <p><b>Not durable.</b> State lives in memory for the duration of {@link #run}; the framework's in-memory checkpoints
 * are released when a run ends, and a process restart loses any run in progress. Durable checkpoints are FEATURE-011.
 */
public final class TriageGraph {

    /** Default iteration bound: the longest v1 path has 6 nodes; the margin leaves room without allowing loops. */
    public static final int DEFAULT_RECURSION_LIMIT = 12;

    private final TriageGraphStateCodec codec = new TriageGraphStateCodec();
    private final MemorySaver checkpoints = new MemorySaver();
    private final CompiledGraph compiled;
    private final int recursionLimit;
    private final Supplier<String> threadIds;

    public TriageGraph(JevClassificationPort jev, TaxonomyPort taxonomies, ClassificationPolicyPort policies) {
        this(jev, taxonomies, policies, InputValidator.DEFAULT_MAX_MESSAGE_LENGTH, DEFAULT_RECURSION_LIMIT);
    }

    public TriageGraph(
            JevClassificationPort jev,
            TaxonomyPort taxonomies,
            ClassificationPolicyPort policies,
            int maxMessageLength,
            int recursionLimit) {
        this(jev, taxonomies, policies, maxMessageLength, recursionLimit, () -> "triage-" + UUID.randomUUID());
    }

    TriageGraph(
            JevClassificationPort jev,
            TaxonomyPort taxonomies,
            ClassificationPolicyPort policies,
            int maxMessageLength,
            int recursionLimit,
            Supplier<String> threadIds) {
        if (jev == null) {
            throw new IllegalArgumentException("jev must not be null");
        }
        if (recursionLimit < 1) {
            throw new IllegalArgumentException("recursionLimit must be positive");
        }
        this.recursionLimit = recursionLimit;
        this.threadIds = threadIds;
        DefinitionSource definitions = new DefinitionSource(taxonomies, policies);
        ClassificationPolicyEngine engine = new ClassificationPolicyEngine();
        List<TriageStep> steps = List.of(
                new ValidateInputNode(new InputValidator(maxMessageLength)),
                new LoadTaxonomyNode(definitions),
                new JevClassifyNode(jev, new JevAssessmentMapper()),
                new ConfidenceGateNode(definitions, engine),
                new FinalPolicyGateNode(definitions, engine),
                new BuildTraceNode());
        try {
            this.compiled = build(steps).compile(CompileConfig.builder()
                    .recursionLimit(recursionLimit)
                    .saverConfig(SaverConfig.builder().register(checkpoints).build())
                    .releaseThread(true)
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("triage graph definition is invalid", e);
        }
    }

    /** Runs the graph with the active policy. */
    public TriageGraphState run(IncomingCustomerRequest request) {
        return run(request, null);
    }

    /**
     * Runs the graph with an explicit policy version ({@code null} = active policy).
     *
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    public TriageGraphState run(IncomingCustomerRequest request, String policyVersion) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        TriageGraphState initial = TriageGraphState.received(request, policyVersion);
        Optional<OverAllState> result;
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadIds.get()).build();
            result = compiled.invoke(Map.of(GuardedNode.STATE_KEY, codec.encode(initial)), config);
        } catch (RuntimeException e) {
            return runtimeFailure(initial, e.getClass().getSimpleName());
        }
        Optional<String> json = result.flatMap(state -> state.value(GuardedNode.STATE_KEY, String.class));
        if (json.isEmpty()) {
            return runtimeFailure(initial, "NO_STATE");
        }
        TriageGraphState last = codec.decode(json.get());
        if (!last.finished()) {
            TriageFailure partial = new TriageFailure(
                    FailureCode.ITERATION_LIMIT_REACHED, null, "recursionLimit=" + recursionLimit);
            return last.terminated(TriageStatus.PARTIAL, partial);
        }
        return last;
    }

    /** The framework's in-memory checkpoint store; exposed to tests to prove runs release it. */
    MemorySaver checkpoints() {
        return checkpoints;
    }

    /** The compiled SAA graph; exposed to tests to compare framework-observed nodes with the trace. */
    CompiledGraph compiled() {
        return compiled;
    }

    private static TriageGraphState runtimeFailure(TriageGraphState initial, String detail) {
        return initial.terminated(
                TriageStatus.FAILED, new TriageFailure(FailureCode.GRAPH_RUNTIME_ERROR, null, detail));
    }

    private StateGraph build(List<TriageStep> steps) throws GraphStateException {
        StateGraph graph = new StateGraph("triage", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(GuardedNode.STATE_KEY, KeyStrategy.REPLACE);
            return strategies;
        });
        for (TriageStep step : steps) {
            graph.addNode(step.node().id(), node_async(new GuardedNode(step, codec)));
        }
        graph.addEdge(StateGraph.START, TriageNode.VALIDATE_INPUT.id());
        for (TriageStep step : steps) {
            graph.addConditionalEdges(step.node().id(), edge_async(this::selectedRoute), edges(step.node()));
        }
        return graph;
    }

    /** Edge action: returns the label the node selected; it never decides by itself. */
    private String selectedRoute(OverAllState state) {
        String json = state.value(GuardedNode.STATE_KEY, String.class)
                .orElseThrow(() -> new IllegalStateException("graph state key missing"));
        String route = codec.decode(json).route();
        if (route == null) {
            throw new IllegalStateException("node did not select a route");
        }
        return route;
    }

    private static Map<String, String> edges(TriageNode node) {
        Map<String, String> edges = new LinkedHashMap<>();
        TriageRoutes.of(node).forEach((label, target) ->
                edges.put(label, TriageRoutes.END.equals(target) ? StateGraph.END : target));
        return edges;
    }
}
