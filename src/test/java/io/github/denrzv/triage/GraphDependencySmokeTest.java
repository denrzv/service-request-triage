package io.github.denrzv.triage;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves the pinned Spring AI Alibaba Graph API resolves and runs offline (test-only, no triage logic). */
class GraphDependencySmokeTest {

    private static final String INPUT_KEY = "input";
    private static final String OUTPUT_KEY = "output";
    private static final String NODE = "echo";

    @Test
    void trivialGraphCompilesAndRuns() throws GraphStateException {
        KeyStrategyFactory strategies = () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            map.put(INPUT_KEY, KeyStrategy.REPLACE);
            map.put(OUTPUT_KEY, KeyStrategy.REPLACE);
            return map;
        };
        StateGraph graph = new StateGraph(strategies)
                .addNode(NODE, node_async(state -> Map.of(OUTPUT_KEY, "echo:" + state.value(INPUT_KEY, ""))))
                .addEdge(START, NODE)
                .addEdge(NODE, END);

        CompiledGraph compiled = graph.compile();
        Optional<OverAllState> result = compiled.invoke(Map.of(INPUT_KEY, "synthetic request"));

        assertThat(result).isPresent();
        assertThat(result.get().value(OUTPUT_KEY, String.class)).contains("echo:synthetic request");
    }
}
