package io.github.denrzv.triage.infrastructure.typesafe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import org.junit.jupiter.api.Test;

class JevRequestMapperTest {

    private static final String MODEL = "jev-latest";
    private static final String CRITERIA = "criteria";

    private final JevRequestMapper mapper = new JevRequestMapper(new ObjectMapper());
    private final JsonNode body = mapper.toBody(MODEL, JevTestData.request());

    @Test
    void writesModelAndState() {
        assertThat(body.get("model").asText()).isEqualTo(MODEL);
        assertThat(body.at("/state/customerMessage").asText()).isEqualTo(JevTestData.MESSAGE);
        assertThat(body.at("/state/channel").asText()).isEqualTo("WEB_FORM");
    }

    @Test
    void choiceCriteriaAreAnObject() {
        JsonNode choice = body.at("/questions/" + JevTestData.INTENT);
        assertThat(choice.get("type").asText()).isEqualTo("choice");
        assertThat(choice.get(CRITERIA).isObject()).isTrue();
        assertThat(choice.at("/criteria/complaint").asText()).isEqualTo("Express dissatisfaction");
    }

    @Test
    void scoreCriteriaAreAnArray() {
        JsonNode score = body.at("/questions/" + JevTestData.URGENCY);
        assertThat(score.get("type").asText()).isEqualTo("score");
        assertThat(score.get(CRITERIA).isArray()).isTrue();
        assertThat(score.get(CRITERIA)).hasSize(3);
    }

    @Test
    void noulCarriesOnlyTypeAndInstructions() {
        JsonNode noul = body.at("/questions/" + JevTestData.REFUND);
        assertThat(noul.get("type").asText()).isEqualTo("noul");
        assertThat(noul.has(CRITERIA)).isFalse();
        assertThat(noul.has("instructions")).isTrue();
    }

    @Test
    void omitsChannelWhenAbsent() {
        var request = new JevClassificationRequest(JevTestData.MESSAGE, null, JevTestData.questionSet());
        assertThat(mapper.toBody(MODEL, request).get("state").has("channel")).isFalse();
    }
}
