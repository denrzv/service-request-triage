package io.github.denrzv.triage.infrastructure.taxonomy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Locations of the versioned taxonomy and policy files (Spring resource syntax, e.g. {@code classpath:} or
 * {@code file:}) and the policy version used for new triage runs, bound from {@code triage.definitions.*}.
 */
@ConfigurationProperties("triage.definitions")
public record DefinitionsProperties(
        @DefaultValue("classpath:taxonomy/v1.json") List<String> taxonomyLocations,
        @DefaultValue("classpath:policy/v1.json") List<String> policyLocations,
        @DefaultValue("v1") String activePolicyVersion) {

    public DefinitionsProperties {
        taxonomyLocations = requireLocations(taxonomyLocations, "taxonomyLocations");
        policyLocations = requireLocations(policyLocations, "policyLocations");
        if (activePolicyVersion == null || activePolicyVersion.isBlank()) {
            throw new IllegalArgumentException("activePolicyVersion must not be blank");
        }
    }

    private static List<String> requireLocations(List<String> locations, String name) {
        if (locations == null || locations.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (locations.stream().anyMatch(location -> location == null || location.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank locations");
        }
        return List.copyOf(locations);
    }
}
