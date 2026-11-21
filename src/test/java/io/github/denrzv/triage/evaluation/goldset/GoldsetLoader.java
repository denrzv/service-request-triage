package io.github.denrzv.triage.evaluation.goldset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.denrzv.triage.domain.model.Taxonomy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Strict reader for goldset JSONL files and the manifest (test scope only, so neither the dataset nor this reader
 * is packaged into the runtime artifact). Unknown properties, unknown enum names, missing properties and duplicate
 * keys are rejected; every record is checked against the given taxonomy. All violations of a file are collected
 * and reported together with file, line and case ID.
 */
public final class GoldsetLoader {

    private static final String MANIFEST = "manifest.json";

    private final Taxonomy taxonomy;
    private final ObjectReader caseReader;
    private final ObjectReader manifestReader;

    public GoldsetLoader(Taxonomy taxonomy) {
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
        ObjectMapper mapper = JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build();
        ObjectReader strict = mapper
                .reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withFeatures(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.caseReader = strict.forType(GoldCase.class);
        this.manifestReader = strict.forType(GoldsetManifest.class);
    }

    /** Loads one split file of a dataset directory. */
    public List<GoldCase> load(Path datasetDirectory, GoldSplit split) {
        return load(datasetDirectory.resolve(split.fileName()));
    }

    /** Loads and validates every line of a JSONL file; throws {@link GoldsetValidationException} on any violation. */
    public List<GoldCase> load(Path file) {
        List<String> lines = readLines(file);
        List<GoldCase> cases = new ArrayList<>(lines.size());
        List<String> violations = new ArrayList<>();
        String name = file.getFileName().toString();
        for (int index = 0; index < lines.size(); index++) {
            String location = name + ":" + (index + 1);
            String line = lines.get(index);
            if (line.isBlank()) {
                violations.add(location + " blank line");
                continue;
            }
            try {
                GoldCase gold = caseReader.readValue(line);
                checkTaxonomy(gold);
                cases.add(gold);
            } catch (IllegalArgumentException e) {
                violations.add(location + " " + caseIdOf(line) + ": " + e.getMessage());
            } catch (JsonProcessingException e) {
                violations.add(location + " " + caseIdOf(line) + ": " + reason(e));
            }
        }
        if (!violations.isEmpty()) {
            throw new GoldsetValidationException(violations);
        }
        return List.copyOf(cases);
    }

    public GoldsetManifest loadManifest(Path datasetDirectory) {
        Path file = datasetDirectory.resolve(MANIFEST);
        try {
            return manifestReader.readValue(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new GoldsetValidationException(List.of(MANIFEST + ": " + reason(e)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Lower-case hex SHA-256 of the exact file bytes. */
    public static String sha256(Path file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void checkTaxonomy(GoldCase gold) {
        if (!taxonomy.version().equals(gold.taxonomyVersion())) {
            throw new IllegalArgumentException("taxonomyVersion " + gold.taxonomyVersion()
                    + " does not match loaded taxonomy " + taxonomy.version());
        }
        taxonomy.requireReasonCode(gold.reasonCode(), gold.formalSrType());
    }

    private static List<String> readLines(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.indexOf('\r') >= 0) {
                throw new GoldsetValidationException(List.of(file.getFileName() + ": CR line endings are not allowed"));
            }
            if (!content.isEmpty() && !content.endsWith("\n")) {
                throw new GoldsetValidationException(List.of(file.getFileName() + ": missing final newline"));
            }
            if (content.isEmpty()) {
                return List.of();
            }
            String[] lines = content.split("\n", -1);
            return List.of(lines).subList(0, lines.length - 1);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static String reason(JsonProcessingException e) {
        if (e instanceof ValueInstantiationException && e.getCause() != null) {
            return e.getCause().getMessage();
        }
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            return "field " + field + ": " + e.getOriginalMessage();
        }
        return e.getOriginalMessage();
    }

    /** Best-effort case ID for error messages; never echoes message text. */
    private static String caseIdOf(String line) {
        int start = line.indexOf("\"caseId\":\"");
        if (start < 0) {
            return "<no caseId>";
        }
        int from = start + "\"caseId\":\"".length();
        int end = line.indexOf('"', from);
        String id = end > from ? line.substring(from, end) : "";
        return id.matches("[A-Za-z0-9-]{1,16}") ? id : "<invalid caseId>";
    }
}
