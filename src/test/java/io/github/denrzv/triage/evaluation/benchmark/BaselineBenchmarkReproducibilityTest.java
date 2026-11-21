package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.evaluation.goldset.GoldsetLoader;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Default-build proof that the committed baseline is reproducible offline (AC2, AC7): the committed Jev recordings
 * and run parameters are replayed without network or key, the rules-only baseline is recomputed, and every rendered
 * file must equal the committed one byte for byte. A goldset, question-set, policy, metric or format change therefore
 * fails here until the run is regenerated.
 *
 * <p>After an intentional format change, regenerate offline with {@code JEV_BENCHMARK_REGENERATE=true}; a question-set
 * or goldset change needs a new live run instead (see {@link JevBaselineLiveBenchmarkTest}).
 */
class BaselineBenchmarkReproducibilityTest {

    static final Path RUN = JevBaselineLiveBenchmarkTest.REPORTS.resolve(JevBaselineLiveBenchmarkTest.DEFAULT_RUN_ID);

    private static BenchmarkRun.Result result;
    private static Map<String, String> rendered;

    @BeforeAll
    static void replay() throws IOException {
        BenchmarkRun.Parameters parameters = BenchmarkRun.Parameters.read(RUN.resolve(BenchmarkRun.PARAMETERS_FILE));
        JevRecording.ReplayPort replay =
                new JevRecording.ReplayPort(JevRecording.read(RUN.resolve(BenchmarkRun.RECORDINGS_FILE)));
        result = new BenchmarkRun(ShippedDefinitions.taxonomyV1(), ShippedDefinitions.policyV1())
                .execute(BenchmarkRun.DATASET, replay, parameters);
        rendered = ReportWriter.render(result);
        if ("true".equals(System.getenv("JEV_BENCHMARK_REGENERATE"))) {
            ReportWriter.write(result, RUN);
        }
    }

    @Test
    void committedReportFilesAreRegeneratedByteForByte() throws IOException {
        for (Map.Entry<String, String> file : rendered.entrySet()) {
            Path committed = RUN.resolve(file.getKey());
            assertThat(committed).as(file.getKey()).exists();
            assertThat(file.getValue())
                    .as("%s differs from the regenerated content", committed)
                    .isEqualTo(Files.readString(committed, StandardCharsets.UTF_8));
        }
    }

    @Test
    void committedRecordingsHoldNoMessageTextLabelsOrCaseIds() throws IOException {
        String recordings = Files.readString(RUN.resolve(BenchmarkRun.RECORDINGS_FILE), StandardCharsets.UTF_8);
        GoldsetLoader loader = new GoldsetLoader(ShippedDefinitions.taxonomyV1());
        for (GoldSplit split : GoldSplit.values()) {
            for (GoldCase gold : loader.load(BenchmarkRun.DATASET, split)) {
                assertThat(recordings)
                        .as(gold.caseId())
                        .doesNotContain(gold.message())
                        .doesNotContain(gold.labelRationale())
                        .contains(JevRecording.sha256(gold.message()));
            }
        }
        assertThat(recordings).doesNotContainPattern("GS-\\d{4}").doesNotContain("formalSrType", "stratum");
    }

    @Test
    void committedRunCoversBothSplitsWithoutProviderErrorsOrLlmRoutes() {
        assertThat(result.metadata().resolvedModels()).isNotEmpty();
        for (BaselineMode mode : BaselineMode.values()) {
            for (GoldSplit split : GoldSplit.values()) {
                BenchmarkMetrics metrics = result.metrics().get(mode).get(split);
                assertThat(metrics.cases()).isPositive();
                assertThat(metrics.routing().predictedRoutes()).containsEntry(RouteAction.LLM_INVESTIGATION.name(), 0);
            }
        }
        assertThat(result.metrics().get(BaselineMode.JEV_ONLY).values())
                .allSatisfy(m -> assertThat(m.providerErrors()).isZero());
        assertThat(result.exploration().rows())
                .allSatisfy(row -> assertThat(row.metrics().cases())
                        .isEqualTo(result.metadata().devRecords()));
    }
}
