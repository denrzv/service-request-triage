package io.github.denrzv.triage.infrastructure.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DefinitionJsonMapperTest {

    private static final String POLICY_V1 = "/policy/v1.json";
    private static final String TAXONOMY_V1 = "/taxonomy/v1.json";

    private final DefinitionJsonMapper mapper = new DefinitionJsonMapper(new ObjectMapper());

    @Test
    void shippedV1DefinitionsAreValidAndCompatible() {
        Taxonomy taxonomy = mapper.readTaxonomy(resource(TAXONOMY_V1));
        ClassificationPolicy policy = mapper.readPolicy(resource(POLICY_V1));

        policy.requireCompatibleWith(taxonomy);
        assertThat(taxonomy.version()).isEqualTo("v1");
        assertThat(policy.version()).isEqualTo("v1");
        assertThat(policy.calibrated()).isFalse();
        assertThat(policy.humanApprovalTypes()).containsExactly(ServiceRequestType.CLAIM);
        assertThat(policy.rules()).hasSize(10);
        assertThat(policy.thresholds().taxonomyFitMin()).isZero();
        assertThat(policy.thresholds().multiIntentMax()).isEqualTo(1.0);
    }

    @Test
    void nonSyntheticDefinitionIsRejected() {
        String json = text(POLICY_V1).replace("\"synthetic\": true", "\"synthetic\": false");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("synthetic");
    }

    @Test
    void unknownPropertyIsRejected() {
        String json = text(POLICY_V1).replace("\"calibrated\": false,", "\"calibrated\": false, \"slaHours\": 4,");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("slaHours");
    }

    @Test
    void unknownEnumNameIsRejected() {
        String json = text(TAXONOMY_V1).replace("\"OTHER\": \"Anything", "\"MORTGAGES\": \"Anything");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readTaxonomy(stream(json)))
                .withMessageContaining("MORTGAGES");
    }

    @Test
    void missingPropertyIsRejected() {
        String json = text(POLICY_V1).replace("\"maxInvestigations\": 1,", "");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("maxInvestigations");
    }

    @Test
    void fractionalIntegerIsRejected() {
        String json = text(POLICY_V1).replace("\"maxInvestigations\": 1,", "\"maxInvestigations\": 1.5,");

        assertThatIllegalArgumentException().isThrownBy(() -> mapper.readPolicy(stream(json)));
    }

    @Test
    void duplicateKeyIsRejected() {
        String json = text(POLICY_V1).replace("\"calibrated\": false,", "\"calibrated\": false, \"calibrated\": true,");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("calibrated");
    }

    @Test
    void nullListElementIsRejectedByDomainValidation() {
        String json = text(POLICY_V1).replace("\"humanApprovalTypes\": [\"CLAIM\"]", "\"humanApprovalTypes\": [null]");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("humanApprovalTypes");
    }

    @Test
    void thresholdOutOfRangeIsRejectedByDomainValidation() {
        String json = text(POLICY_V1).replace("\"signalMin\": 0.5", "\"signalMin\": 5");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readPolicy(stream(json)))
                .withMessageContaining("signalMin");
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.readTaxonomy(stream("{ not json")))
                .withMessageContaining("malformed definition JSON");
    }

    static InputStream resource(String path) {
        InputStream in = DefinitionJsonMapperTest.class.getResourceAsStream(path);
        assertThat(in).as(path).isNotNull();
        return in;
    }

    private static String text(String path) {
        try (InputStream in = resource(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
