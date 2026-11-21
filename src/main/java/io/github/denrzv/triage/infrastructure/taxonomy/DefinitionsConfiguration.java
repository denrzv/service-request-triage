package io.github.denrzv.triage.infrastructure.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.TaxonomyPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Loads and validates the configured taxonomy and policy versions at startup (fail fast) and exposes them through
 * the domain ports, together with the pure {@link ClassificationPolicyEngine}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DefinitionsProperties.class)
public class DefinitionsConfiguration {

    @Bean
    TaxonomyPort taxonomyPort(DefinitionsProperties properties, ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        return new InMemoryTaxonomyRepository(
                loader(resourceLoader, objectMapper).loadTaxonomies(properties.taxonomyLocations()));
    }

    @Bean
    ClassificationPolicyPort classificationPolicyPort(
            DefinitionsProperties properties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            TaxonomyPort taxonomyPort) {
        return new InMemoryPolicyRepository(
                loader(resourceLoader, objectMapper).loadPolicies(properties.policyLocations()),
                taxonomyPort,
                properties.activePolicyVersion());
    }

    @Bean
    ClassificationPolicyEngine classificationPolicyEngine() {
        return new ClassificationPolicyEngine();
    }

    private static DefinitionLoader loader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        return new DefinitionLoader(resourceLoader, new DefinitionJsonMapper(objectMapper));
    }
}
