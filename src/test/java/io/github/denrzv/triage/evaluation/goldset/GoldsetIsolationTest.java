package io.github.denrzv.triage.evaluation.goldset;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.denrzv.triage.TriageApplication;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves goldset labels are not reachable from the runtime artifact or from resources that prompts, tools or fake
 * providers could read (AC5, ADR FEATURE-004 decision 1). The scans cover every current and future file under
 * {@code src/main}, so new investigator tools (FEATURE-008) are guarded automatically.
 */
class GoldsetIsolationTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main");
    private static final Path TEST_RESOURCES = Path.of("src", "test", "resources");
    private static final Pattern CASE_ID = Pattern.compile("GS-\\d{4}");
    /** Path and schema markers unique to the goldset (plain words such as "goldset" may appear in docs text). */
    private static final List<String> FORBIDDEN_MARKERS =
            List.of("english-goldset", "labelRationale", "paraphraseGroup", "GoldCase", "GoldsetLoader");

    private static List<GoldCase> gold;

    @BeforeAll
    static void loadGold() {
        GoldsetLoader loader = new GoldsetLoader(ShippedDefinitions.taxonomyV1());
        gold = new ArrayList<>();
        for (GoldSplit split : GoldSplit.values()) {
            gold.addAll(loader.load(GoldsetContractTest.DATASET, split));
        }
        assertThat(gold).isNotEmpty();
    }

    @Test
    void mainSourcesAndResourcesNeverReferenceGoldset() throws IOException {
        assertThat(scanSources(MAIN_SOURCES)).as("goldset leakage into src/main").isEmpty();
    }

    @Test
    void scannerDetectsPlantedLeaks(@TempDir Path root) throws IOException {
        GoldCase sample = gold.getFirst();
        Files.writeString(root.resolve("prompt.md"), "Example: " + sample.message(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Tool.java"), "Path.of(\"samples/english-goldset/v1\")", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ids.txt"), "see " + sample.caseId(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("clean.txt"), "Synthetic policy text about the goldset.", StandardCharsets.UTF_8);

        assertThat(scanSources(root))
                .anySatisfy(v -> assertThat(v).contains("prompt.md contains text of " + sample.caseId()))
                .anySatisfy(v -> assertThat(v).contains("Tool.java references 'english-goldset'"))
                .anySatisfy(v -> assertThat(v).contains("ids.txt contains a goldset-style case ID"))
                .noneSatisfy(v -> assertThat(v).contains("clean.txt"));
    }

    @Test
    void compiledRuntimeArtifactContainsNoGoldset() throws IOException, URISyntaxException {
        Path mainOutput = Path.of(TriageApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(mainOutput).as("main output directory").isDirectory();
        List<String> violations = new ArrayList<>();
        for (Path file : files(mainOutput)) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("goldset") || name.endsWith(".jsonl")) {
                violations.add(file + " looks like a goldset file");
            }
            String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            if (bytes.contains("labelRationale") || bytes.contains("english-goldset")) {
                violations.add(file + " contains goldset markers");
            }
        }
        assertThat(violations).as("goldset leakage into the runtime artifact").isEmpty();
    }

    @Test
    void goldsetIsNotOnTheClasspath() {
        ClassLoader loader = TriageApplication.class.getClassLoader();
        for (String resource : List.of(
                "samples/english-goldset/v1/test.jsonl", "english-goldset/v1/test.jsonl", "v1/test.jsonl",
                "test.jsonl", "dev.jsonl", "manifest.json")) {
            assertThat(loader.getResource(resource)).as(resource).isNull();
        }
    }

    @Test
    void testResourcesDoNotCopyGoldsetRecords() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(TEST_RESOURCES)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("labelRationale") || CASE_ID.matcher(text).find()) {
                violations.add(file + " contains goldset records or IDs");
            }
            violations.addAll(goldTextIn(file, text));
        }
        assertThat(violations).as("goldset copied into test fixtures").isEmpty();
    }

    @Test
    void buildDoesNotPackageSamples() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).doesNotContain("samples").doesNotContain("english-goldset");
    }

    @Test
    void runtimeClassesDoNotDependOnEvaluationCode() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..evaluation..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..evaluation..")
                .allowEmptyShould(true)
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.github.denrzv.triage"));
    }

    private static List<String> scanSources(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files(root)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            FORBIDDEN_MARKERS.stream()
                    .filter(marker -> lower.contains(marker.toLowerCase(Locale.ROOT)))
                    .forEach(marker -> violations.add(root.relativize(file) + " references '" + marker + "'"));
            if (CASE_ID.matcher(text).find()) {
                violations.add(root.relativize(file) + " contains a goldset-style case ID");
            }
            violations.addAll(goldTextIn(root.relativize(file), text));
        }
        return violations;
    }

    private static List<String> goldTextIn(Path file, String text) {
        List<String> violations = new ArrayList<>();
        for (GoldCase gold : gold) {
            if (text.contains(gold.message()) || text.contains(gold.labelRationale())) {
                violations.add(file + " contains text of " + gold.caseId());
            }
        }
        return violations;
    }

    private static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
