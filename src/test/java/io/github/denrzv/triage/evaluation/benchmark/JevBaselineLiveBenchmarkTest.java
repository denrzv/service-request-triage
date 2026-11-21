package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import io.github.denrzv.triage.infrastructure.typesafe.LiveJevPorts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in live Jev baseline run (AC2). Skipped unless {@code JEV_BENCHMARK_LIVE=true} and {@code JEV_API_KEY} is set,
 * because it sends every goldset message (both splits, message and channel only) to TypeSafe and is billed per input
 * token. It refuses to start when the case count exceeds {@code JEV_BENCHMARK_MAX_CALLS} (default 300).
 *
 * <p>Writes {@code reports/evaluation/<JEV_BENCHMARK_RUN_ID>/} (default {@code feature-005-jev-baseline}): the
 * recordings, run parameters and the rendered report. Optional: {@code JEV_MODEL} (default {@code jev-latest}),
 * {@code JEV_PRICE_INPUT_USD_PER_MTOK}, {@code JEV_PRICE_OUTPUT_USD_PER_MTOK}, {@code JEV_PRICE_SOURCE}.
 */
@EnabledIfEnvironmentVariable(named = "JEV_BENCHMARK_LIVE", matches = "true")
class JevBaselineLiveBenchmarkTest {

    static final Path REPORTS = Path.of("reports", "evaluation");
    static final String DEFAULT_RUN_ID = "feature-005-jev-baseline";
    private static final int DEFAULT_MAX_CALLS = 300;
    private static final double MAX_WRONG_AUTO_ROUTE_RATE = 0.10;

    @Test
    void liveRunRecordsResponsesAndWritesReport() throws IOException {
        String key = System.getenv("JEV_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "JEV_API_KEY is not set");
        Taxonomy taxonomy = ShippedDefinitions.taxonomyV1();
        int budget = Integer.parseInt(env("JEV_BENCHMARK_MAX_CALLS", Integer.toString(DEFAULT_MAX_CALLS)));
        int cases = BenchmarkRun.totalCases(BenchmarkRun.DATASET, taxonomy);
        if (cases > budget) {
            throw new IllegalStateException(cases + " cases exceed the call budget " + budget);
        }

        BenchmarkRun.Parameters parameters = new BenchmarkRun.Parameters(
                env("JEV_BENCHMARK_RUN_ID", DEFAULT_RUN_ID),
                env("JEV_MODEL", "jev-latest"),
                LocalDate.now(ZoneOffset.UTC).toString(),
                price("JEV_PRICE_INPUT_USD_PER_MTOK"),
                price("JEV_PRICE_OUTPUT_USD_PER_MTOK"),
                System.getenv("JEV_PRICE_SOURCE"),
                MAX_WRONG_AUTO_ROUTE_RATE);
        JevRecording.RecordingPort recorder =
                new JevRecording.RecordingPort(LiveJevPorts.create(key, parameters.requestedModel()));

        BenchmarkRun.Result result = new BenchmarkRun(taxonomy, ShippedDefinitions.policyV1())
                .execute(BenchmarkRun.DATASET, recorder, parameters);

        Path directory = REPORTS.resolve(parameters.runId());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(BenchmarkRun.RECORDINGS_FILE), JevRecording.toJsonl(recorder.lines()),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(BenchmarkRun.PARAMETERS_FILE), parameters.toJson(), StandardCharsets.UTF_8);
        ReportWriter.write(result, directory);

        assertThat(recorder.lines()).hasSize(cases);
        BenchmarkMetrics test = result.metrics().get(BaselineMode.JEV_ONLY).get(GoldSplit.TEST);
        System.out.printf("jev baseline written to %s: resolved=%s testMacroF1=%s providerErrors=%d inputTokens=%d%n",
                directory, result.metadata().resolvedModels(), test.formalType().macroF1(),
                result.metrics().get(BaselineMode.JEV_ONLY).values().stream()
                        .mapToInt(BenchmarkMetrics::providerErrors).sum(),
                result.cost().inputTokens());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Double price(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Double.valueOf(value);
    }
}
