package io.github.denrzv.triage.infrastructure.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DefinitionsConfigurationTest {

    private static final String POLICIES = "triage.definitions.policy-locations=";
    private static final String TAXONOMIES = "triage.definitions.taxonomy-locations=";
    private static final String ACTIVE = "triage.definitions.active-policy-version=";
    private static final String V1_POLICY = "classpath:policy/v1.json";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(DefinitionsConfiguration.class);

    @Test
    void defaultsLoadShippedV1Definitions() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClassificationPolicyEngine.class);
            ClassificationPolicyPort policies = context.getBean(ClassificationPolicyPort.class);
            TaxonomyPort taxonomies = context.getBean(TaxonomyPort.class);

            assertThat(policies.active().version()).isEqualTo("v1");
            assertThat(policies.get("v1")).isSameAs(policies.active());
            assertThat(taxonomies.get("v1").version()).isEqualTo("v1");
            assertThatThrownBy(() -> policies.get("v9"))
                    .isInstanceOf(DefinitionNotFoundException.class)
                    .hasMessage("policy version v9 is not loaded");
            assertThatThrownBy(() -> taxonomies.get("v9")).isInstanceOf(DefinitionNotFoundException.class);
            assertThatThrownBy(() -> taxonomies.get(null)).isInstanceOf(DefinitionNotFoundException.class);
        });
    }

    @Test
    void newPolicyVersionChangesDecisionWithoutSourceChange() {
        runner.withPropertyValues(POLICIES + V1_POLICY + ",classpath:policy/alternate-v2.json", ACTIVE + "v2-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ClassificationPolicyPort policies = context.getBean(ClassificationPolicyPort.class);

                    assertThat(decide(context, policies.get("v1")).action()).isEqualTo(RouteAction.DRAFT_PROPOSAL);
                    RoutingDecision v2 = decide(context, policies.active());
                    assertThat(v2.action()).isEqualTo(RouteAction.ABSTAIN);
                    assertThat(v2.policyVersion()).isEqualTo("v2-test");
                    assertThat(v2.policyCalibrated()).isTrue();
                });
    }

    @Test
    void fileLocationIsSupported(@TempDir Path dir) throws IOException {
        Path copy = dir.resolve("policy-v2.json");
        try (InputStream in = DefinitionJsonMapperTest.resource("/policy/alternate-v2.json")) {
            Files.copy(in, copy);
        }

        runner.withPropertyValues(POLICIES + "file:" + copy, ACTIVE + "v2-test")
                .run(context -> assertThat(context.getBean(ClassificationPolicyPort.class).active().version())
                        .isEqualTo("v2-test"));
    }

    @Test
    void invalidReasonCodeFailsStartupWithLocation() {
        runner.withPropertyValues(POLICIES + V1_POLICY + ",classpath:policy/invalid-reason-code.json")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("unknown reason code NOT_IN_TAXONOMY"));
        runner.withPropertyValues(POLICIES + V1_POLICY + ",classpath:policy/invalid-reason-code.json")
                .run(context -> assertThat(failureMessages(context))
                        .contains("policy bad-test is invalid")
                        .contains("rule R01-COMPLAINT-PAYMENTS"));
    }

    @Test
    void missingFileFailsStartupWithLocation() {
        runner.withPropertyValues(TAXONOMIES + "classpath:taxonomy/missing.json")
                .run(context -> assertThat(failureMessages(context))
                        .contains("Invalid taxonomy definition at classpath:taxonomy/missing.json: not found"));
    }

    @Test
    void activeVersionMustBeLoaded() {
        runner.withPropertyValues(ACTIVE + "v7")
                .run(context -> assertThat(failureMessages(context)).contains("active policy version v7 is not loaded"));
    }

    @Test
    void duplicateVersionsFailStartup() {
        runner.withPropertyValues(POLICIES + V1_POLICY + "," + V1_POLICY)
                .run(context -> assertThat(failureMessages(context)).contains("duplicate policy version v1"));
        runner.withPropertyValues(TAXONOMIES + "classpath:taxonomy/v1.json,classpath:taxonomy/v1.json")
                .run(context -> assertThat(failureMessages(context)).contains("duplicate taxonomy version v1"));
    }

    @Test
    void blankActiveVersionIsRejected() {
        runner.withPropertyValues(ACTIVE + " ")
                .run(context -> assertThat(failureMessages(context)).contains("activePolicyVersion must not be blank"));
    }

    private static RoutingDecision decide(
            AssertableApplicationContext context, ClassificationPolicy policy) {
        SemanticAssessment lowFitInquiry = new SemanticAssessment(
                new ChoiceJudgment<>(PrimaryIntent.INQUIRY, 0.9, Map.of(PrimaryIntent.INQUIRY, 0.9)),
                new ChoiceJudgment<>(ProblemDomain.ACCOUNTS, 0.9, Map.of(ProblemDomain.ACCOUNTS, 0.9)),
                new ChoiceJudgment<>(RequestedOutcome.EXPLAIN, 0.9, Map.of(RequestedOutcome.EXPLAIN, 0.9)),
                0.0,
                0.0,
                0.0,
                0.2,
                "jev-test",
                "qs-test");
        return context.getBean(ClassificationPolicyEngine.class)
                .decide(lowFitInquiry, context.getBean(TaxonomyPort.class).get(policy.taxonomyVersion()), policy, 0);
    }

    private static String failureMessages(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        StringBuilder messages = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
