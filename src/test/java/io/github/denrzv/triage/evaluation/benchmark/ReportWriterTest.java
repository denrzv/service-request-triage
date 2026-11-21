package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.denrzv.triage.application.assessment.JevResults;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.evaluation.goldset.GoldsetLoader;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders a run over the real goldset with a label-blind scripted fake (same answers for every message, every 50th
 * call failing) and checks structure, provenance and that no message text or label rationale leaks into reports.
 */
class ReportWriterTest {

    private static final BenchmarkRun.Parameters PARAMETERS = new BenchmarkRun.Parameters(
            "unit-test-run", "jev-latest", "2026-01-01", 0.042, 0.0, "unit test price", 0.10);

    private static BenchmarkRun.Result result;
    private static Map<String, String> files;
    private static List<GoldCase> gold;

    @BeforeAll
    static void run() {
        AtomicInteger calls = new AtomicInteger();
        JevClassificationPort fake = request -> {
            if (calls.incrementAndGet() % 50 == 0) {
                throw new JevUnavailableException(3, 503, null);
            }
            return JevResults.result(JevResults.answers(
                    PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.7, 0.8));
        };
        result = new BenchmarkRun(ShippedDefinitions.taxonomyV1(), ShippedDefinitions.policyV1())
                .execute(BenchmarkRun.DATASET, fake, PARAMETERS);
        files = ReportWriter.render(result);
        GoldsetLoader loader = new GoldsetLoader(ShippedDefinitions.taxonomyV1());
        gold = new ArrayList<>();
        for (GoldSplit split : GoldSplit.values()) {
            gold.addAll(loader.load(BenchmarkRun.DATASET, split));
        }
    }

    @Test
    void rendersEveryExpectedFile() {
        assertThat(files).containsOnlyKeys(
                "report.md", "metrics.json", "threshold-sweep.csv", "cases-rules-only.csv", "cases-jev-only.csv",
                "confusion-rules-only-dev.csv", "confusion-rules-only-test.csv", "confusion-jev-only-dev.csv",
                "confusion-jev-only-test.csv");
        assertThat(files.values()).allSatisfy(content -> assertThat(content).endsWith("\n").doesNotContain("\r"));
    }

    @Test
    void reportCarriesProvenanceLimitationsAndCost() {
        String report = files.get("report.md");
        BenchmarkRun.Metadata meta = result.metadata();

        assertThat(meta.devRecords() + meta.testRecords()).isEqualTo(gold.size());
        assertThat(report)
                .contains(meta.devSha256(), meta.testSha256())
                .contains("triage-jev-v1", "jev-latest", JevResults.MODEL, "rules-v1")
                .contains("## Limitations", "## Threshold exploration (calibration split only)")
                .contains("Estimated cost per 1,000 requests (USD)")
                .contains("unit test price");
        assertThat(meta.resolvedModels()).containsExactly(JevResults.MODEL);
    }

    @Test
    void noMessageTextOrRationaleLeaksIntoAnyFile() {
        for (Map.Entry<String, String> file : files.entrySet()) {
            for (GoldCase c : gold) {
                assertThat(file.getValue())
                        .as(file.getKey() + " / " + c.caseId())
                        .doesNotContain(c.message())
                        .doesNotContain(c.labelRationale());
            }
        }
        String header = files.get("cases-jev-only.csv").lines().findFirst().orElseThrow();
        assertThat(header).doesNotContain("gold").doesNotContain("formalSrType").doesNotContain("recommendedRoute");
    }

    @Test
    void casesCsvHasOneRowPerCaseAndRecordsProviderErrors() {
        List<String> lines = files.get("cases-jev-only.csv").lines().toList();

        assertThat(lines).hasSize(gold.size() + 1);
        assertThat(lines).filteredOn(line -> line.contains("JevUnavailableException")).hasSize(gold.size() / 50);
        assertThat(lines.get(1)).startsWith("GS-").contains(",DEV,");
        assertThat(lines.getLast()).contains(",TEST,");
    }

    @Test
    void metricsJsonIsStructuredAndSweepCoversTheGrid() throws IOException {
        JsonNode json = new ObjectMapper().readTree(files.get("metrics.json"));

        assertThat(json.at("/metadata/runId").asText()).isEqualTo("unit-test-run");
        assertThat(json.at("/metrics/JEV_ONLY/TEST/providerErrors").asInt()).isPositive();
        assertThat(json.at("/thresholdExploration/split").asText()).isEqualTo("DEV");
        assertThat(json.at("/thresholdExploration/candidates")).hasSize(4);
        assertThat(json.at("/cost/inputTokens").asLong()).isPositive();
        assertThat(files.get("threshold-sweep.csv").lines()).hasSize(4 * 21 + 1);
        assertThat(files.get("confusion-jev-only-test.csv").lines()).hasSize(6);
    }

    @Test
    void renderingIsDeterministicAndWritesFiles(@TempDir Path dir) throws IOException {
        assertThat(ReportWriter.render(result)).isEqualTo(files);

        ReportWriter.write(result, dir.resolve("run"));

        assertThat(Files.readString(dir.resolve("run").resolve("report.md"))).isEqualTo(files.get("report.md"));
    }

    @Test
    void parametersAreValidatedAndRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(BenchmarkRun.PARAMETERS_FILE);
        Files.writeString(file, PARAMETERS.toJson());

        assertThat(BenchmarkRun.Parameters.read(file)).isEqualTo(PARAMETERS);
        assertThatThrownBy(() -> new BenchmarkRun.Parameters("../escape", "m", "d", null, null, null, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkRun.Parameters("ok", "m", "d", 0.042, null, "src", 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkRun.Parameters("ok", "m", "d", 0.042, 0.0, null, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
