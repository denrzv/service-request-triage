package io.github.denrzv.triage.infrastructure.taxonomy;

import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Reads definition files once at startup. Any unreadable or invalid file fails with an {@link IllegalStateException}
 * naming the location, so the application never starts with a partially loaded taxonomy or policy.
 */
final class DefinitionLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DefinitionLoader.class);

    private final ResourceLoader resourceLoader;
    private final DefinitionJsonMapper mapper;

    DefinitionLoader(ResourceLoader resourceLoader, DefinitionJsonMapper mapper) {
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
    }

    List<Taxonomy> loadTaxonomies(List<String> locations) {
        return loadAll(locations, "taxonomy", mapper::readTaxonomy, Taxonomy::version);
    }

    List<ClassificationPolicy> loadPolicies(List<String> locations) {
        return loadAll(locations, "policy", mapper::readPolicy, ClassificationPolicy::version);
    }

    private <T> List<T> loadAll(
            List<String> locations, String kind, Function<InputStream, T> reader, Function<T, String> version) {
        List<T> loaded = new ArrayList<>();
        for (String location : locations) {
            T definition = load(location, kind, reader);
            LOG.info("Loaded {} definition version={} location={}", kind, version.apply(definition), location);
            loaded.add(definition);
        }
        return loaded;
    }

    private <T> T load(String location, String kind, Function<InputStream, T> reader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Invalid " + kind + " definition at " + location + ": not found");
        }
        try (InputStream in = resource.getInputStream()) {
            return reader.apply(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid " + kind + " definition at " + location + ": " + e.getMessage(), e);
        }
    }
}
