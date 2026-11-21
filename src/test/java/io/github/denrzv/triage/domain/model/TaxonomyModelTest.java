package io.github.denrzv.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaxonomyModelTest {

    @Test
    void validTaxonomyExposesDefinitionsAndReasonCodes() {
        Taxonomy taxonomy = DomainFixtures.taxonomy();

        assertThat(taxonomy.primaryIntents()).containsOnlyKeys(PrimaryIntent.values());
        assertThat(taxonomy.serviceRequestTypes()).containsOnlyKeys(ServiceRequestType.values());
        assertThat(taxonomy.reasonCode("PAYMENT_DISPUTE"))
                .map(ReasonCode::serviceRequestType)
                .contains(ServiceRequestType.CLAIM);
        assertThat(taxonomy.reasonCode("UNKNOWN")).isEmpty();
        assertThat(taxonomy.requireReasonCode("PAYMENT_DISPUTE", ServiceRequestType.CLAIM).code())
                .isEqualTo("PAYMENT_DISPUTE");
    }

    @Test
    void missingDefinitionForAnyConstantIsRejected() {
        Map<ProblemDomain, String> domains = DomainFixtures.definitions(ProblemDomain.class);
        domains.remove(ProblemDomain.OTHER);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Taxonomy(
                        "v1",
                        DomainFixtures.definitions(PrimaryIntent.class),
                        domains,
                        DomainFixtures.definitions(RequestedOutcome.class),
                        DomainFixtures.definitions(ServiceRequestType.class),
                        DomainFixtures.reasonCodes()))
                .withMessageContaining("problemDomains.OTHER");
    }

    @Test
    void blankDefinitionIsRejected() {
        Map<PrimaryIntent, String> intents = DomainFixtures.definitions(PrimaryIntent.class);
        intents.put(PrimaryIntent.INQUIRY, " ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Taxonomy(
                        "v1",
                        intents,
                        DomainFixtures.definitions(ProblemDomain.class),
                        DomainFixtures.definitions(RequestedOutcome.class),
                        DomainFixtures.definitions(ServiceRequestType.class),
                        DomainFixtures.reasonCodes()))
                .withMessageContaining("primaryIntents.INQUIRY");
    }

    @Test
    void mandatoryUnclassifiedReasonCodesAreRequired() {
        List<ReasonCode> codes = new ArrayList<>(DomainFixtures.reasonCodes());
        codes.removeIf(code -> code.code().equals(ReasonCode.NONE_FIT));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> taxonomyWith(codes))
                .withMessageContaining(ReasonCode.NONE_FIT);
    }

    @Test
    void mandatoryCodeWithWrongTypeIsRejected() {
        List<ReasonCode> codes = new ArrayList<>(DomainFixtures.reasonCodes());
        codes.removeIf(code -> code.code().equals(ReasonCode.MODEL_UNAVAILABLE));
        codes.add(new ReasonCode(ReasonCode.MODEL_UNAVAILABLE, ServiceRequestType.CLAIM, "Wrong type"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> taxonomyWith(codes))
                .withMessageContaining("belongs to CLAIM");
    }

    @Test
    void duplicateReasonCodeIsRejected() {
        List<ReasonCode> codes = new ArrayList<>(DomainFixtures.reasonCodes());
        codes.add(new ReasonCode("PAYMENT_DISPUTE", ServiceRequestType.CLAIM, "Duplicate"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> taxonomyWith(codes))
                .withMessageContaining("duplicate reason code PAYMENT_DISPUTE");
    }

    @Test
    void reasonCodeRequiresTypeAndDescription() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReasonCode("X", null, "d"))
                .withMessageContaining("serviceRequestType");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReasonCode("X", ServiceRequestType.CLAIM, ""))
                .withMessageContaining("description");
    }

    @Test
    void requireReasonCodeRejectsUnknownAndMismatchedType() {
        Taxonomy taxonomy = DomainFixtures.taxonomy();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> taxonomy.requireReasonCode("NOPE", ServiceRequestType.CLAIM))
                .withMessageContaining("unknown reason code NOPE");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> taxonomy.requireReasonCode("PAYMENT_DISPUTE", ServiceRequestType.SERVICE_REQUEST))
                .withMessageContaining("belongs to CLAIM");
    }

    private static Taxonomy taxonomyWith(List<ReasonCode> codes) {
        return new Taxonomy(
                "v1",
                DomainFixtures.definitions(PrimaryIntent.class),
                DomainFixtures.definitions(ProblemDomain.class),
                DomainFixtures.definitions(RequestedOutcome.class),
                DomainFixtures.definitions(ServiceRequestType.class),
                codes);
    }
}
