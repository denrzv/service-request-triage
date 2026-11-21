package io.github.denrzv.triage.evaluation.goldset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Build-enforced contract of the frozen v1 goldset: schema, labels, balance, hashes and reproducible split. */
class GoldsetContractTest {

    static final Path DATASET = Path.of("samples", "english-goldset", "v1");

    private static final double MIN_TEST_SHARE = 0.20;
    private static final double MAX_TEST_SHARE = 0.40;

    private static final Taxonomy TAXONOMY = ShippedDefinitions.taxonomyV1();
    private static GoldsetManifest manifest;
    private static final Map<GoldSplit, List<GoldCase>> SPLITS = new EnumMap<>(GoldSplit.class);

    @BeforeAll
    static void load() {
        assertThat(DATASET.resolve("manifest.json"))
                .as("goldset must be present; tests run from the project base directory")
                .exists();
        GoldsetLoader loader = new GoldsetLoader(TAXONOMY);
        manifest = loader.loadManifest(DATASET);
        for (GoldSplit split : GoldSplit.values()) {
            SPLITS.put(split, loader.load(DATASET, split));
        }
    }

    @Test
    void manifestDescribesSyntheticV1DatasetForLoadedTaxonomy() {
        assertThat(manifest.datasetId()).isEqualTo("english-goldset");
        assertThat(manifest.version()).isEqualTo("v1");
        assertThat(manifest.taxonomyVersion()).isEqualTo(TAXONOMY.version());
        assertThat(manifest.synthetic()).isTrue();
        assertThat(manifest.labelSource()).isEqualTo(LabelSource.SYNTHETIC_AUTHOR);
        assertThat(manifest.split().algorithm()).isEqualTo(GoldsetSplitter.ALGORITHM);
        assertThat(manifest.split().seed()).isNotBlank();
        assertThat(manifest.split().testFraction()).isEqualTo(0.30);
    }

    @Test
    void fileHashesAndCountsMatchManifest() {
        for (GoldSplit split : GoldSplit.values()) {
            GoldsetManifest.FileEntry entry = manifest.file(split);
            assertThat(entry.path()).isEqualTo(split.fileName());
            assertThat(GoldsetLoader.sha256(DATASET.resolve(entry.path())))
                    .as("%s was edited after freezing; re-run tools/goldset.py freeze", entry.path())
                    .isEqualTo(entry.sha256());
            assertThat(SPLITS.get(split)).hasSize(entry.records());
        }
        assertThat(all()).hasSize(manifest.totalRecords());
        manifest.strata().forEach((stratum, count) -> {
            assertThat(count(GoldSplit.DEV, stratum)).as("dev %s", stratum).isEqualTo(count.dev());
            assertThat(count(GoldSplit.TEST, stratum)).as("test %s", stratum).isEqualTo(count.test());
        });
        assertThat(manifest.strata()).containsOnlyKeys(EnumSet.allOf(Stratum.class));
    }

    @Test
    void splitIsReproducibleFromFrozenRecipe() {
        Map<String, GoldSplit> expected = GoldsetSplitter.assign(
                all(), manifest.split().seed(), manifest.split().testFraction());

        SPLITS.forEach((split, cases) -> cases.forEach(gold -> assertThat(expected.get(gold.caseId()))
                .as("split of %s", gold.caseId())
                .isEqualTo(split)));
    }

    @Test
    void datasetPassesCrossRecordValidation() {
        assertThatCode(() -> GoldsetValidator.validate(SPLITS)).doesNotThrowAnyException();
    }

    @Test
    void sizeAndStrataAreBalanced() {
        assertThat(all()).hasSizeBetween(GoldsetValidator.MIN_TOTAL, GoldsetValidator.MAX_TOTAL);
        for (Stratum stratum : Stratum.values()) {
            int total = count(GoldSplit.DEV, stratum) + count(GoldSplit.TEST, stratum);
            double testShare = (double) count(GoldSplit.TEST, stratum) / total;
            assertThat(total).as("cases in %s", stratum).isGreaterThanOrEqualTo(GoldsetValidator.MIN_PER_STRATUM);
            assertThat(testShare).as("held-out share of %s", stratum).isBetween(MIN_TEST_SHARE, MAX_TEST_SHARE);
        }
    }

    @Test
    void everyFormalTypeAndTerminalRouteIsMeasurableInBothSplits() {
        for (GoldSplit split : GoldSplit.values()) {
            List<GoldCase> cases = SPLITS.get(split);
            assertThat(cases).extracting(GoldCase::formalSrType)
                    .as("formal types in %s", split)
                    .containsAll(EnumSet.allOf(ServiceRequestType.class));
            assertThat(cases).extracting(GoldCase::recommendedRoute)
                    .as("routes in %s", split)
                    .contains(RouteAction.DRAFT_PROPOSAL, RouteAction.HUMAN_REVIEW, RouteAction.ABSTAIN);
            assertThat(cases).as("multi-intent cases in %s", split).anyMatch(gold -> !gold.secondaryIntents().isEmpty());
            assertThat(cases).as("ambiguous cases in %s", split).anyMatch(gold -> !gold.alternatives().isEmpty());
        }
    }

    @Test
    void filesAreCanonicallySortedByCaseId() {
        SPLITS.forEach((split, cases) -> assertThat(cases)
                .extracting(GoldCase::caseId)
                .as("order of %s", split.fileName())
                .isSortedAccordingTo(Comparator.naturalOrder()));
    }

    @Test
    void caseIdsDoNotRevealStratum() {
        List<GoldCase> byId = new ArrayList<>(all());
        byId.sort(Comparator.comparing(GoldCase::caseId));
        int longestRun = 1;
        int run = 1;
        for (int i = 1; i < byId.size(); i++) {
            run = byId.get(i).stratum() == byId.get(i - 1).stratum() ? run + 1 : 1;
            longestRun = Math.max(longestRun, run);
        }
        assertThat(longestRun).as("longest run of one stratum in caseId order").isLessThanOrEqualTo(6);
    }

    @Test
    void datasetDirectoryHoldsOnlyFrozenFilesAndReadme() throws Exception {
        try (var files = Files.list(DATASET)) {
            assertThat(files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("dev.jsonl", "test.jsonl", "manifest.json");
        }
        assertThat(DATASET.getParent().resolve("README.md")).exists();
    }

    static List<GoldCase> all() {
        List<GoldCase> all = new ArrayList<>();
        SPLITS.values().forEach(all::addAll);
        return all;
    }

    private static int count(GoldSplit split, Stratum stratum) {
        return (int) SPLITS.get(split).stream().filter(gold -> gold.stratum() == stratum).count();
    }
}
