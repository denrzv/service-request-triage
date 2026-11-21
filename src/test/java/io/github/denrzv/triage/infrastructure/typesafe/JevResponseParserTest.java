package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.denrzv.triage.domain.model.ChoiceAnswer;
import io.github.denrzv.triage.domain.model.NoulAnswer;
import io.github.denrzv.triage.domain.model.ScoreAnswer;
import io.github.denrzv.triage.domain.port.JevProtocolException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JevResponseParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANSWERS = "answers";
    private static final String NOUL = "noul";
    private static final String CONFIDENCE = "confidence";
    private static final String PROBABILITIES = "probabilities";
    private static final String USAGE = "usage";
    private static final String LEAKY = "leaky-provider-text";

    private final JevResponseParser parser = new JevResponseParser(MAPPER);

    private static ObjectNode golden() throws Exception {
        return (ObjectNode) MAPPER.readTree(JevTestData.fixture("success-multi-type.json"));
    }

    private static ObjectNode answer(ObjectNode root, String name) {
        return (ObjectNode) root.get(ANSWERS).get(name);
    }

    @Test
    void parsesGoldenFixtureIntoTypedAnswers() {
        var parsed = parser.parse(JevTestData.fixture("success-multi-type.json"), JevTestData.questionSet());
        assertThat(parsed.model()).isEqualTo("jev-1.14.0");
        assertThat(parsed.usage().inputTokens()).isEqualTo(549);
        assertThat(parsed.usage().outputTokens()).isEqualTo(63);
        assertThat(parsed.answers().get(JevTestData.INTENT))
                .isEqualTo(new ChoiceAnswer(
                        "complaint",
                        0.82,
                        Map.of("complaint", 0.82, "inquiry", 0.1, "action_request", 0.05, "incident_report", 0.03)));
        assertThat(parsed.answers().get(JevTestData.REFUND)).isEqualTo(new NoulAnswer(0.97));
        assertThat(parsed.answers().get(JevTestData.URGENCY))
                .isEqualTo(new ScoreAnswer(1.7, 0.55, List.of(0.1, 0.1, 0.8)));
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                mutation("missing answer", root -> ((ObjectNode) root.get(ANSWERS)).remove(JevTestData.REFUND)),
                mutation("wrong type", root -> answer(root, JevTestData.REFUND).put("type", "choice")),
                mutation("choice without probabilities", root -> answer(root, JevTestData.INTENT).remove(PROBABILITIES)),
                mutation("choice without confidence", root -> answer(root, JevTestData.INTENT).remove(CONFIDENCE)),
                mutation("confidence out of range", root -> answer(root, JevTestData.INTENT).put(CONFIDENCE, 1.5)),
                mutation("unknown choice", root -> answer(root, JevTestData.INTENT).put("choice", "other")),
                mutation("noul missing value", root -> answer(root, JevTestData.REFUND).remove(NOUL)),
                mutation("noul probability above one", root -> answer(root, JevTestData.REFUND).put(NOUL, 1.4)),
                mutation("noul not numeric", root -> answer(root, JevTestData.REFUND).put(NOUL, LEAKY)),
                mutation("score without legend", root -> answer(root, JevTestData.URGENCY).remove("legend")),
                mutation("score missing level probability", root ->
                        ((ObjectNode) answer(root, JevTestData.URGENCY).get(PROBABILITIES)).remove("2")),
                mutation("score above level range", root -> answer(root, JevTestData.URGENCY).put("score", 7.0)),
                mutation("missing usage", root -> root.remove(USAGE)),
                mutation("negative tokens", root -> ((ObjectNode) root.get(USAGE)).put("input_tokens", -1)),
                mutation("missing model", root -> root.remove("model")),
                mutation("missing answers", root -> root.remove(ANSWERS)));
    }

    private static Arguments mutation(String name, Consumer<ObjectNode> change) {
        return Arguments.of(name, change);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void rejectsMalformedResponsesWithoutEchoingText(String name, Consumer<ObjectNode> change) throws Exception {
        ObjectNode root = golden();
        change.accept(root);
        String body = MAPPER.writeValueAsString(root);
        assertThatThrownBy(() -> parser.parse(body, JevTestData.questionSet()))
                .isInstanceOf(JevProtocolException.class)
                .hasMessageNotContaining(LEAKY)
                .hasMessageNotContaining(JevTestData.MESSAGE);
    }

    @Test
    void rejectsEmptyNonJsonAndNonObjectBodies() {
        for (String body : new String[] {"", "   ", "not json", "[]", "\"text\""}) {
            assertThatThrownBy(() -> parser.parse(body, JevTestData.questionSet()))
                    .isInstanceOf(JevProtocolException.class);
        }
        assertThatThrownBy(() -> parser.parse(null, JevTestData.questionSet()))
                .isInstanceOf(JevProtocolException.class);
    }
}
