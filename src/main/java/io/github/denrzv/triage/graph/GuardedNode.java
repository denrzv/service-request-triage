package io.github.denrzv.triage.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.github.denrzv.triage.domain.model.TriageStatus;
import java.util.Map;

/**
 * Adapts a {@link TriageStep} to the SAA {@link NodeAction}: decodes the single state key, runs the step and
 * re-encodes the result. An unexpected {@link RuntimeException} from the step becomes a {@code NODE_FAILED} failure
 * (node and exception class only) routed to {@code BuildTrace}, or, in {@code BuildTrace} itself, a terminal
 * {@code FAILED} state, so a node error never escapes as an unclassified exception.
 */
final class GuardedNode implements NodeAction {

    /** The only key in the graph's {@code OverAllState}. */
    static final String STATE_KEY = "triage";

    private final TriageStep step;
    private final TriageGraphStateCodec codec;

    GuardedNode(TriageStep step, TriageGraphStateCodec codec) {
        this.step = step;
        this.codec = codec;
    }

    @Override
    public Map<String, Object> apply(OverAllState overAllState) {
        String json = overAllState.value(STATE_KEY, String.class)
                .orElseThrow(() -> new IllegalStateException("graph state key missing"));
        TriageGraphState current = codec.decode(json);
        TriageGraphState next;
        try {
            next = step.apply(current);
        } catch (RuntimeException e) {
            next = failed(current, e);
        }
        return Map.of(STATE_KEY, codec.encode(next));
    }

    private TriageGraphState failed(TriageGraphState current, RuntimeException e) {
        TriageNode node = step.node();
        TriageFailure failure = new TriageFailure(FailureCode.NODE_FAILED, node.id(), e.getClass().getSimpleName());
        TriageGraphState withFailure = current.withFailure(failure)
                .routed(node, TriageRoutes.FAILED, TriageRoutes.target(node, TriageRoutes.FAILED));
        return node == TriageNode.BUILD_TRACE ? withFailure.withStatus(TriageStatus.FAILED) : withFailure;
    }
}
