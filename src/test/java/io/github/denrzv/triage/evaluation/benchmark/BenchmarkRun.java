package io.github.denrzv.triage.evaluation.benchmark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.assessment.TriageQuestionSets;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.evaluation.goldset.GoldsetLoader;
import io.github.denrzv.triage.evaluation.goldset.GoldsetManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Orchestrates one benchmark run over both goldset splits: dataset hashes checked against the manifest, both
 * baselines through the same Policy Engine and policy, metrics per mode and split, calibration-only threshold
 * exploration with one held-out check, and cost. The result is deterministic for equal inputs, so a run replayed
 * from recordings regenerates identical report files.
 */
public final class BenchmarkRun {

    /** Frozen goldset v1 directory (outside the classpath, read by test-scope code only). */
    public static final Path DATASET = Path.of("samples", "english-goldset", "v1");
    public static final String PARAMETERS_FILE = "run.json";
    public static final String RECORDINGS_FILE = "jev-responses.jsonl";
    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Run parameters persisted as {@value #PARAMETERS_FILE} next to the recordings, so a replay reproduces the same
     * metadata. Prices are USD per million tokens and may be {@code null} (cost not estimated).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Parameters(
            String runId,
            String requestedModel,
            String recordedOn,
            Double priceInputUsdPerMillion,
            Double priceOutputUsdPerMillion,
            String priceSource,
            double maxWrongAutoRouteRate) {

        public Parameters {
            if (runId == null || !RUN_ID.matcher(runId).matches()) {
                throw new IllegalArgumentException("runId must match " + RUN_ID.pattern());
            }
            requireText(requestedModel, "requestedModel");
            requireText(recordedOn, "recordedOn");
            if ((priceInputUsdPerMillion == null) != (priceOutputUsdPerMillion == null)
                    || (priceInputUsdPerMillion != null && (priceInputUsdPerMillion < 0 || priceOutputUsdPerMillion < 0))) {
                throw new IllegalArgumentException("prices must both be absent or both be non-negative");
            }
            if (priceInputUsdPerMillion != null) {
                requireText(priceSource, "priceSource");
            }
        }

        public static Parameters read(Path file) {
            try {
                return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Parameters.class);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read run parameters " + file, e);
            }
        }

        public String toJson() {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("cannot serialize run parameters", e);
            }
        }
    }

    /** Provenance of a run; contains no timestamps other than the recorded date. */
    public record Metadata(
            String runId,
            String datasetId,
            String datasetVersion,
            String taxonomyVersion,
            String devSha256,
            int devRecords,
            String testSha256,
            int testRecords,
            String policyVersion,
            boolean policyCalibrated,
            String questionSetVersion,
            String rulesVersion,
            String requestedModel,
            List<String> resolvedModels,
            String recordedOn,
            String priceSource) {}

    public record HeldOutCheck(
            ClassificationPolicy candidatePolicy, BenchmarkMetrics devWithCandidate, BenchmarkMetrics testWithCandidate) {}

    public record Result(
            Metadata metadata,
            Parameters parameters,
            Map<BaselineMode, List<CaseOutcome>> outcomes,
            Map<BaselineMode, Map<GoldSplit, BenchmarkMetrics>> metrics,
            ThresholdExplorer.Exploration exploration,
            HeldOutCheck heldOutCheck,
            CostSummary cost) {}

    private final Taxonomy taxonomy;
    private final ClassificationPolicy policy;

    public BenchmarkRun(Taxonomy taxonomy, ClassificationPolicy policy) {
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Total number of cases (both splits) the run will send to the provider; for call-budget checks. */
    public static int totalCases(Path datasetDirectory, Taxonomy taxonomy) {
        return new GoldsetLoader(taxonomy).loadManifest(datasetDirectory).totalRecords();
    }

    public Result execute(Path datasetDirectory, JevClassificationPort jevPort, Parameters parameters) {
        Objects.requireNonNull(jevPort, "jevPort");
        Objects.requireNonNull(parameters, "parameters");
        GoldsetLoader loader = new GoldsetLoader(taxonomy);
        GoldsetManifest manifest = loader.loadManifest(datasetDirectory);
        Map<GoldSplit, List<GoldCase>> cases = new EnumMap<>(GoldSplit.class);
        for (GoldSplit split : GoldSplit.values()) {
            verifyHash(datasetDirectory, manifest, split);
            cases.put(split, loader.load(datasetDirectory, split));
        }

        BaselineRunner runner = new BaselineRunner(taxonomy, new ClassificationPolicyEngine());
        KeywordRulesAssessor rules = new KeywordRulesAssessor();
        JevAssessmentMapper mapper = new JevAssessmentMapper();
        Map<BaselineMode, Map<GoldSplit, List<CaseOutcome>>> bySplit = new EnumMap<>(BaselineMode.class);
        bySplit.put(BaselineMode.RULES_ONLY, new EnumMap<>(GoldSplit.class));
        bySplit.put(BaselineMode.JEV_ONLY, new EnumMap<>(GoldSplit.class));
        for (GoldSplit split : GoldSplit.values()) {
            bySplit.get(BaselineMode.RULES_ONLY).put(split, runner.runRules(split, cases.get(split), rules, policy));
            bySplit.get(BaselineMode.JEV_ONLY).put(split,
                    runner.runJev(split, cases.get(split), jevPort, TriageQuestionSets.v1(), mapper, policy));
        }

        Map<BaselineMode, List<CaseOutcome>> outcomes = new EnumMap<>(BaselineMode.class);
        Map<BaselineMode, Map<GoldSplit, BenchmarkMetrics>> metrics = new EnumMap<>(BaselineMode.class);
        bySplit.forEach((mode, splits) -> {
            List<CaseOutcome> all = new ArrayList<>();
            Map<GoldSplit, BenchmarkMetrics> perSplit = new EnumMap<>(GoldSplit.class);
            splits.forEach((split, list) -> {
                all.addAll(list);
                perSplit.put(split, BenchmarkMetrics.compute(list));
            });
            outcomes.put(mode, List.copyOf(all));
            metrics.put(mode, perSplit);
        });

        ThresholdExplorer explorer = new ThresholdExplorer(runner, policy, parameters.maxWrongAutoRouteRate());
        Map<GoldSplit, List<CaseOutcome>> jev = bySplit.get(BaselineMode.JEV_ONLY);
        ThresholdExplorer.Exploration exploration =
                explorer.explore(new CalibrationSet(BaselineMode.JEV_ONLY, jev.get(GoldSplit.DEV)));
        HeldOutCheck heldOut = new HeldOutCheck(
                exploration.candidatePolicy(),
                BenchmarkMetrics.compute(explorer.redecide(jev.get(GoldSplit.DEV), exploration.candidatePolicy())),
                BenchmarkMetrics.compute(explorer.redecide(jev.get(GoldSplit.TEST), exploration.candidatePolicy())));

        List<CaseOutcome> jevAll = outcomes.get(BaselineMode.JEV_ONLY);
        CostSummary cost = CostSummary.of(
                jevAll, parameters.priceInputUsdPerMillion(), parameters.priceOutputUsdPerMillion());
        Metadata metadata = metadata(manifest, parameters, jevAll);
        return new Result(metadata, parameters, outcomes, metrics, exploration, heldOut, cost);
    }

    private Metadata metadata(GoldsetManifest manifest, Parameters parameters, List<CaseOutcome> jevOutcomes) {
        List<String> resolved = List.copyOf(new TreeSet<>(jevOutcomes.stream()
                .filter(o -> !o.hasProviderError())
                .map(o -> o.assessment().modelUsed())
                .toList()));
        return new Metadata(
                parameters.runId(),
                manifest.datasetId(),
                manifest.version(),
                taxonomy.version(),
                manifest.dev().sha256(),
                manifest.dev().records(),
                manifest.test().sha256(),
                manifest.test().records(),
                policy.version(),
                policy.calibrated(),
                TriageQuestionSets.V1,
                KeywordRulesAssessor.VERSION,
                parameters.requestedModel(),
                resolved,
                parameters.recordedOn(),
                parameters.priceSource());
    }

    private static void verifyHash(Path datasetDirectory, GoldsetManifest manifest, GoldSplit split) {
        GoldsetManifest.FileEntry entry = manifest.file(split);
        String actual = GoldsetLoader.sha256(datasetDirectory.resolve(entry.path()));
        if (!actual.equals(entry.sha256())) {
            throw new IllegalStateException(split + " file hash " + actual + " does not match manifest " + entry.sha256());
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
