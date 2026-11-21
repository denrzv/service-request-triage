package io.github.denrzv.triage.evaluation.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.denrzv.triage.domain.model.ChoiceJudgment;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.evaluation.goldset.Stratum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a {@link BenchmarkRun.Result} into English report files (docs/evaluation.md section 8). Output is
 * deterministic: fixed ordering, {@link Locale#ROOT} formatting, LF line endings and no wall-clock time. Per-case CSV
 * files carry case IDs, predictions and provider signals only, never message text or per-case gold labels.
 */
public final class ReportWriter {

    public static final String REPORT = "report.md";
    public static final String METRICS = "metrics.json";
    public static final String SWEEP = "threshold-sweep.csv";

    private static final String NA = "n/a";
    private static final String SEP = ",";
    private static final String NL = "\n";
    private static final String CELL = " | ";
    private static final int JSON_SCALE = 6;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final List<String> LIMITATIONS = List.of(
            "All 240 messages are synthetic English, written and labelled by one author (an AI assistant under "
                    + "maintainer direction) without a second independent reviewer; real traffic is noisier.",
            "The taxonomy and policy are illustrative and synthetic; results say nothing about any real bank's "
                    + "routing, legal or SLA obligations, or about non-English performance.",
            "75 held-out cases give wide confidence intervals; per-class scores for rare classes are noisy.",
            "Policy v1 thresholds are uncalibrated no-ops; the JEV_ONLY v1 result is argmax plus categorical rules.",
            "Threshold candidates come from 165 calibration cases with an illustrative wrong-auto-route target; they "
                    + "are provisional and not applied to policy v1 (FEATURE-007 owns calibration).",
            "The keyword rules were authored by the same author as the labels, from the development split; they may "
                    + "be optimistic on that split.",
            "Baselines have no LLM fallback: every ambiguity concern becomes HUMAN_REVIEW.",
            "jev-latest moves between releases; the resolved model is recorded and replay pins the recorded answers.",
            "Cost is an estimate from the published per-token price and recorded usage; invoices can differ.");

    private ReportWriter() {}

    /** Writes every rendered file into {@code directory} (created if missing). */
    public static void write(BenchmarkRun.Result result, Path directory) {
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, String> file : render(result).entrySet()) {
                Files.writeString(directory.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write report to " + directory, e);
        }
    }

    /** File name to content, in a stable order. */
    public static Map<String, String> render(BenchmarkRun.Result result) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(REPORT, markdown(result));
        files.put(METRICS, metricsJson(result));
        result.metrics().forEach((mode, splits) -> splits.forEach((split, metrics) ->
                files.put(confusionFile(mode, split), confusionCsv(metrics))));
        result.outcomes().forEach((mode, outcomes) -> files.put(casesFile(mode), casesCsv(outcomes)));
        files.put(SWEEP, sweepCsv(result.exploration()));
        return files;
    }

    static String confusionFile(BaselineMode mode, GoldSplit split) {
        return "confusion-" + slug(mode.name()) + "-" + slug(split.name()) + ".csv";
    }

    static String casesFile(BaselineMode mode) {
        return "cases-" + slug(mode.name()) + ".csv";
    }

    // ---------------------------------------------------------------- JSON

    private static String metricsJson(BenchmarkRun.Result result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("metadata", MAPPER.valueToTree(result.metadata()));
        ObjectNode metrics = root.putObject("metrics");
        result.metrics().forEach((mode, splits) -> {
            ObjectNode byMode = metrics.putObject(mode.name());
            splits.forEach((split, value) -> byMode.set(split.name(), MAPPER.valueToTree(value)));
        });
        ObjectNode exploration = root.putObject("thresholdExploration");
        exploration.put("split", GoldSplit.DEV.name());
        exploration.put("mode", result.exploration().mode().name());
        exploration.put("maxWrongAutoRouteRate", result.exploration().maxWrongAutoRouteRate());
        ArrayNode candidates = exploration.putArray("candidates");
        for (ThresholdExplorer.Candidate candidate : result.exploration().candidates()) {
            ObjectNode node = candidates.addObject();
            node.put("threshold", candidate.threshold().name());
            if (candidate.value().isPresent()) {
                node.put("value", candidate.value().get());
                node.set("calibrationMetrics", MAPPER.valueToTree(candidate.row().metrics().routing()));
            } else {
                node.putNull("value");
            }
        }
        BenchmarkRun.HeldOutCheck check = result.heldOutCheck();
        ObjectNode held = exploration.putObject("heldOutCheck");
        held.put("candidatePolicyVersion", check.candidatePolicy().version());
        held.set("candidateThresholds", MAPPER.valueToTree(check.candidatePolicy().thresholds()));
        held.set("DEV", MAPPER.valueToTree(check.devWithCandidate()));
        held.set("TEST", MAPPER.valueToTree(check.testWithCandidate()));
        root.set("cost", MAPPER.valueToTree(result.cost()));
        round(root);
        try {
            return MAPPER.writeValueAsString(root).replace("\r\n", NL) + NL;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize metrics", e);
        }
    }

    private static void round(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isDouble()) {
                    field.setValue(DoubleNode.valueOf(scaled(field.getValue().doubleValue())));
                } else {
                    round(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isDouble()) {
                    array.set(i, DoubleNode.valueOf(scaled(array.get(i).doubleValue())));
                } else {
                    round(array.get(i));
                }
            }
        }
    }

    private static double scaled(double value) {
        return BigDecimal.valueOf(value).setScale(JSON_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------- CSV

    private static String confusionCsv(BenchmarkMetrics metrics) {
        StringBuilder out = new StringBuilder("gold\\predicted");
        for (ServiceRequestType type : ServiceRequestType.values()) {
            out.append(SEP).append(type.name());
        }
        out.append(NL);
        metrics.formalType().confusion().forEach((gold, row) -> {
            out.append(gold);
            row.values().forEach(count -> out.append(SEP).append(count));
            out.append(NL);
        });
        return out.toString();
    }

    private static String casesCsv(List<CaseOutcome> outcomes) {
        StringBuilder out = new StringBuilder(String.join(SEP, List.of(
                "caseId", "split", "action", "proposedType", "reasonCode", "ruleIds", "rationaleCodes",
                "providerError", "intent", "intentTopProbability", "intentConfidence", "intentMargin", "domain",
                "domainTopProbability", "domainConfidence", "domainMargin", "outcome", "outcomeTopProbability",
                "outcomeConfidence", "outcomeMargin", "refundRequestedNoul", "technicalProblemNoul",
                "multiIntentNoul", "taxonomyFitNoul", "inputTokens", "outputTokens", "latencyMs", "attempts")))
                .append(NL);
        outcomes.stream()
                .sorted(Comparator.comparing(CaseOutcome::split).thenComparing(CaseOutcome::caseId))
                .forEach(outcome -> out.append(caseRow(outcome)).append(NL));
        return out.toString();
    }

    private static String caseRow(CaseOutcome outcome) {
        RoutingDecision decision = outcome.decision();
        StringBuilder row = new StringBuilder();
        row.append(outcome.caseId()).append(SEP).append(outcome.split()).append(SEP)
                .append(decision.action()).append(SEP)
                .append(decision.proposal().proposedType()).append(SEP)
                .append(decision.proposal().reasonCode()).append(SEP)
                .append(String.join(";", decision.ruleIds())).append(SEP)
                .append(decision.rationaleCodes().stream().map(Enum::name).collect(Collectors.joining(";")))
                .append(SEP)
                .append(outcome.providerError() == null ? "" : outcome.providerError());
        SemanticAssessment a = outcome.assessment();
        if (a == null) {
            row.append(",,,,,,,,,,,,,,,,");
        } else {
            appendChoice(row, a.primaryIntent());
            appendChoice(row, a.problemDomain());
            appendChoice(row, a.requestedOutcome());
            row.append(SEP).append(num4(a.refundRequestedNoul()))
                    .append(SEP).append(num4(a.technicalProblemNoul()))
                    .append(SEP).append(num4(a.multiIntentNoul()))
                    .append(SEP).append(num4(a.taxonomyFitNoul()));
        }
        if (outcome.usage() == null) {
            row.append(",,,");
        } else {
            row.append(SEP).append(outcome.usage().inputTokens())
                    .append(SEP).append(outcome.usage().outputTokens())
                    .append(SEP).append(outcome.latency().toMillis());
        }
        row.append(SEP).append(outcome.attempts());
        return row.toString();
    }

    private static void appendChoice(StringBuilder row, ChoiceJudgment<?> judgment) {
        row.append(SEP).append(judgment.selected())
                .append(SEP).append(num4(BenchmarkMetrics.top(judgment)))
                .append(SEP).append(num4(judgment.confidence()))
                .append(SEP).append(num4(judgment.margin()));
    }

    private static String sweepCsv(ThresholdExplorer.Exploration exploration) {
        StringBuilder out = new StringBuilder(
                "threshold,value,autoRouteCoverage,autoRouted,wrongAutoRoutes,wrongAutoRouteRate,humanReviewRate,"
                        + "abstainRate,complaintRecall,outOfTaxonomySuccessRate,formalTypeMacroF1" + NL);
        for (ThresholdExplorer.SweepRow row : exploration.rows()) {
            BenchmarkMetrics m = row.metrics();
            out.append(String.join(SEP, List.of(
                            row.threshold().name(), num2(row.value()),
                            ratio(m.routing().autoRouteCoverage()), Integer.toString(m.routing().autoRouted()),
                            Integer.toString(m.routing().wrongAutoRoutes()), ratio(m.routing().wrongAutoRouteRate()),
                            ratio(m.routing().humanReviewRate()), ratio(m.routing().abstainRate()),
                            ratio(m.complaint().recall()), ratio(m.outOfTaxonomy().successRate()),
                            ratio(m.formalType().macroF1()))))
                    .append(NL);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- Markdown

    private static String markdown(BenchmarkRun.Result result) {
        StringBuilder md = new StringBuilder();
        BenchmarkRun.Metadata meta = result.metadata();
        md.append("# Jev baseline benchmark: ").append(meta.runId()).append(NL).append(NL)
                .append("> Synthetic English data and an illustrative taxonomy/policy only. A successful baseline is "
                        + "not evidence of production suitability. Generated by `BenchmarkRun`/`ReportWriter`; do not "
                        + "edit by hand.")
                .append(NL).append(NL);
        metadataSection(md, result);
        summarySection(md, result);
        perClassSection(md, result);
        confusionSection(md, result);
        semanticSection(md, result);
        signalSection(md, result);
        explorationSection(md, result);
        costSection(md, result);
        md.append("## Limitations").append(NL).append(NL);
        LIMITATIONS.forEach(item -> md.append("- ").append(item).append(NL));
        md.append(NL);
        filesSection(md, result);
        return md.toString();
    }

    private static void metadataSection(StringBuilder md, BenchmarkRun.Result result) {
        BenchmarkRun.Metadata m = result.metadata();
        md.append("## Run metadata").append(NL).append(NL)
                .append("| Field | Value |").append(NL).append("|---|---|").append(NL);
        row(md, "Run ID", m.runId());
        row(md, "Dataset", m.datasetId() + " " + m.datasetVersion() + " (taxonomy " + m.taxonomyVersion() + ")");
        row(md, "Development/calibration split", m.devRecords() + " cases, sha256 `" + m.devSha256() + "`");
        row(md, "Held-out test split", m.testRecords() + " cases, sha256 `" + m.testSha256() + "`");
        row(md, "Policy", m.policyVersion() + " (calibrated: " + m.policyCalibrated() + "), no investigation budget");
        row(md, "Jev question set", m.questionSetVersion());
        row(md, "Keyword rules", m.rulesVersion());
        row(md, "Requested Jev model alias", m.requestedModel());
        row(md, "Resolved Jev model(s)", m.resolvedModels().isEmpty() ? NA : String.join(", ", m.resolvedModels()));
        row(md, "Jev responses", "live run recorded on " + m.recordedOn() + ", replayed from `"
                + BenchmarkRun.RECORDINGS_FILE + "`");
        row(md, "Price source", m.priceSource() == null ? "not configured" : m.priceSource());
        md.append(NL);
    }

    private static void summarySection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Summary").append(NL).append(NL)
                .append("Wrong auto-route: a `DRAFT_PROPOSAL` whose formal type is wrong or whose gold route is not "
                        + "`DRAFT_PROPOSAL`. OOT success: out-of-taxonomy cases proposed as `UNCLASSIFIED`.")
                .append(NL).append(NL)
                .append("| Mode | Split | Cases | Formal macro-F1 | Formal accuracy | Complaint recall | Auto-route "
                        + "coverage | Wrong auto-route rate | Abstain rate | Review rate | OOT success | Provider "
                        + "errors |")
                .append(NL).append("|---|---|---|---|---|---|---|---|---|---|---|---|").append(NL);
        result.metrics().forEach((mode, splits) -> splits.forEach((split, m) -> md.append("| ")
                .append(String.join(CELL, List.of(mode.name(), split.name(), Integer.toString(m.cases()),
                        ratio(m.formalType().macroF1()), ratio(m.formalType().accuracy()),
                        ratio(m.complaint().recall()), ratio(m.routing().autoRouteCoverage()),
                        ratio(m.routing().wrongAutoRouteRate()), ratio(m.routing().abstainRate()),
                        ratio(m.routing().humanReviewRate()), ratio(m.outOfTaxonomy().successRate()),
                        Integer.toString(m.providerErrors()))))
                .append(" |").append(NL)));
        md.append(NL).append("Wrong auto-routes by goldset stratum (strata with none in every column omitted):")
                .append(NL).append(NL).append("| Stratum");
        result.metrics().forEach((mode, splits) -> splits.keySet().forEach(split ->
                md.append(CELL).append(mode.name()).append(' ').append(split.name())));
        md.append(" |").append(NL).append("|---");
        result.metrics().values().forEach(splits -> splits.keySet().forEach(split -> md.append("|---")));
        md.append("|").append(NL);
        for (Stratum stratum : Stratum.values()) {
            if (anyWrong(result, stratum.name())) {
                md.append("| ").append(stratum.name());
                result.metrics().values().forEach(splits -> splits.values().forEach(m -> md.append(CELL)
                        .append(m.routing().wrongAutoRoutesByStratum().get(stratum.name()))));
                md.append(" |").append(NL);
            }
        }
        md.append(NL);
    }

    private static boolean anyWrong(BenchmarkRun.Result result, String stratum) {
        return result.metrics().values().stream()
                .flatMap(splits -> splits.values().stream())
                .anyMatch(m -> m.routing().wrongAutoRoutesByStratum().get(stratum) > 0);
    }

    private static void perClassSection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Formal SR type per class").append(NL).append(NL)
                .append("| Mode | Split | Class | Support | Predicted | Precision | Recall | F1 |").append(NL)
                .append("|---|---|---|---|---|---|---|---|").append(NL);
        result.metrics().forEach((mode, splits) -> splits.forEach((split, m) -> {
            for (ClassMetrics c : m.formalType().perClass()) {
                md.append("| ").append(String.join(CELL, List.of(mode.name(), split.name(), c.label(),
                                Integer.toString(c.support()), Integer.toString(c.predicted()), ratio(c.precision()),
                                ratio(c.recall()), ratio(c.f1()))))
                        .append(" |").append(NL);
            }
        }));
        md.append(NL);
    }

    private static void confusionSection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Formal SR type confusion matrices (rows: gold, columns: predicted)").append(NL).append(NL);
        result.metrics().forEach((mode, splits) -> splits.forEach((split, m) -> {
            md.append("### ").append(mode.name()).append(", ").append(split.name()).append(NL).append(NL)
                    .append("| gold \\ predicted | ");
            md.append(java.util.Arrays.stream(ServiceRequestType.values()).map(Enum::name)
                    .collect(Collectors.joining(CELL))).append(" |").append(NL);
            md.append("|---|---|---|---|---|---|").append(NL);
            m.formalType().confusion().forEach((gold, row) -> md.append("| ").append(gold).append(CELL)
                    .append(row.values().stream().map(String::valueOf).collect(Collectors.joining(CELL)))
                    .append(" |").append(NL));
            md.append(NL);
        }));
    }

    private static void semanticSection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Semantic dimensions (assessed cases only)").append(NL).append(NL)
                .append("| Mode | Split | Assessed | Intent macro-F1 | Intent accuracy | Domain macro-F1 | Domain "
                        + "accuracy | Outcome accuracy | Lenient formal accuracy | Lenient route accuracy |")
                .append(NL).append("|---|---|---|---|---|---|---|---|---|---|").append(NL);
        result.metrics().forEach((mode, splits) -> splits.forEach((split, m) -> md.append("| ")
                .append(String.join(CELL, List.of(mode.name(), split.name(), Integer.toString(m.assessedCases()),
                        ratio(m.semantic().intentMacroF1()), ratio(m.semantic().intentAccuracy()),
                        ratio(m.semantic().domainMacroF1()), ratio(m.semantic().domainAccuracy()),
                        ratio(m.semantic().outcomeAccuracy()), ratio(m.formalType().lenientAccuracy()),
                        ratio(m.routing().lenientRouteAccuracy()))))
                .append(" |").append(NL)));
        md.append(NL);
    }

    private static void signalSection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Jev signals (JEV_ONLY)").append(NL).append(NL)
                .append("Choice top probability and Choice confidence are reported separately; neither is verified "
                        + "correctness. The Companion Noul `taxonomy_fit` asks whether any offered category fits.")
                .append(NL).append(NL)
                .append("| Split | Dimension | Mean top probability | Mean confidence | Mean top-two margin | Accuracy "
                        + "| Mean confidence when correct | Mean confidence when wrong |")
                .append(NL).append("|---|---|---|---|---|---|---|---|").append(NL);
        Map<GoldSplit, BenchmarkMetrics> jev = result.metrics().get(BaselineMode.JEV_ONLY);
        jev.forEach((split, m) -> m.signals().choices().forEach(c -> md.append("| ")
                .append(String.join(CELL, List.of(split.name(), c.dimension(), ratio(c.meanTopProbability()),
                        ratio(c.meanConfidence()), ratio(c.meanMargin()), ratio(c.accuracy()),
                        ratio(c.meanConfidenceWhenCorrect()), ratio(c.meanConfidenceWhenWrong()))))
                .append(" |").append(NL)));
        md.append(NL).append("| Split | Mean taxonomy_fit (in-taxonomy cases) | Mean taxonomy_fit (out-of-taxonomy "
                        + "cases) | Mean multi_intent (gold multi-intent) | Mean multi_intent (single intent) |")
                .append(NL).append("|---|---|---|---|---|").append(NL);
        jev.forEach((split, m) -> md.append("| ").append(String.join(CELL, List.of(split.name(),
                        ratio(m.signals().meanTaxonomyFitInTaxonomy()), ratio(m.signals().meanTaxonomyFitOutOfTaxonomy()),
                        ratio(m.signals().meanMultiIntentWithSecondaryIntent()),
                        ratio(m.signals().meanMultiIntentSingleIntent()))))
                .append(" |").append(NL));
        md.append(NL).append("Per-case values: `").append(casesFile(BaselineMode.JEV_ONLY)).append("`.").append(NL)
                .append(NL);
    }

    private static void explorationSection(StringBuilder md, BenchmarkRun.Result result) {
        ThresholdExplorer.Exploration e = result.exploration();
        md.append("## Threshold exploration (calibration split only)").append(NL).append(NL)
                .append("One threshold at a time over a copy of policy v1, grid 0.00 to 1.00 in steps of 0.05, on ")
                .append(e.mode()).append(" development outcomes only (held-out cases are rejected by construction). "
                        + "Candidate: highest auto-route coverage with wrong auto-route rate at most ")
                .append(num2(e.maxWrongAutoRouteRate()))
                .append(" (illustrative target); ties go to the lower wrong rate, then the least restrictive value. "
                        + "Candidates are provisional and are not written into policy v1. Full sweep: `")
                .append(SWEEP).append("`.").append(NL).append(NL)
                .append("| Threshold | Candidate | Calibration coverage | Calibration wrong auto-route rate |")
                .append(NL).append("|---|---|---|---|").append(NL);
        for (ThresholdExplorer.Candidate c : e.candidates()) {
            md.append("| ").append(c.threshold().name()).append(CELL);
            if (c.value().isPresent()) {
                md.append(num2(c.value().get())).append(CELL)
                        .append(ratio(c.row().metrics().routing().autoRouteCoverage())).append(CELL)
                        .append(ratio(c.row().metrics().routing().wrongAutoRouteRate()));
            } else {
                md.append("none meets the target | n/a | n/a");
            }
            md.append(" |").append(NL);
        }
        BenchmarkRun.HeldOutCheck check = result.heldOutCheck();
        PolicyThresholds t = check.candidatePolicy().thresholds();
        md.append(NL).append("Joint candidate `").append(check.candidatePolicy().version()).append("`: taxonomyFitMin ")
                .append(num2(t.taxonomyFitMin())).append(", choiceConfidenceMin ").append(num2(t.choiceConfidenceMin()))
                .append(", choiceMarginMin ").append(num2(t.choiceMarginMin())).append(", multiIntentMax ")
                .append(num2(t.multiIntentMax())).append(". Single held-out check (no tuning on these results):")
                .append(NL).append(NL)
                .append("| Policy | Split | Formal macro-F1 | Complaint recall | Auto-route coverage | Wrong auto-route "
                        + "rate | Abstain rate | Review rate | OOT success |")
                .append(NL).append("|---|---|---|---|---|---|---|---|---|").append(NL);
        Map<GoldSplit, BenchmarkMetrics> base = result.metrics().get(BaselineMode.JEV_ONLY);
        checkRow(md, result.metadata().policyVersion(), GoldSplit.DEV, base.get(GoldSplit.DEV));
        checkRow(md, check.candidatePolicy().version(), GoldSplit.DEV, check.devWithCandidate());
        checkRow(md, result.metadata().policyVersion(), GoldSplit.TEST, base.get(GoldSplit.TEST));
        checkRow(md, check.candidatePolicy().version(), GoldSplit.TEST, check.testWithCandidate());
        md.append(NL);
    }

    private static void checkRow(StringBuilder md, String policy, GoldSplit split, BenchmarkMetrics m) {
        md.append("| ").append(String.join(CELL, List.of(policy, split.name(), ratio(m.formalType().macroF1()),
                        ratio(m.complaint().recall()), ratio(m.routing().autoRouteCoverage()),
                        ratio(m.routing().wrongAutoRouteRate()), ratio(m.routing().abstainRate()),
                        ratio(m.routing().humanReviewRate()), ratio(m.outOfTaxonomy().successRate()))))
                .append(" |").append(NL);
    }

    private static void costSection(StringBuilder md, BenchmarkRun.Result result) {
        CostSummary c = result.cost();
        BenchmarkRun.Metadata m = result.metadata();
        md.append("## Tokens, latency and cost (JEV_ONLY, both splits)").append(NL).append(NL)
                .append("| Field | Value |").append(NL).append("|---|---|").append(NL);
        row(md, "Model alias / resolved", m.requestedModel() + " / "
                + (m.resolvedModels().isEmpty() ? NA : String.join(", ", m.resolvedModels())));
        row(md, "Question set", m.questionSetVersion());
        row(md, "Calls with a response / failed without response", c.calls() + " / " + c.failedCalls());
        row(md, "Input tokens total / mean per call", c.inputTokens() + " / " + num1(c.meanInputTokens()));
        row(md, "Output tokens total / mean per call", c.outputTokens() + " / " + num1(c.meanOutputTokens()));
        row(md, "Mean HTTP attempts per call", num2(c.meanAttempts()));
        row(md, "Latency p50 / p95 / max (ms, all attempts)", millis(c.latencyP50Ms()) + " / "
                + millis(c.latencyP95Ms()) + " / " + millis(c.latencyMaxMs()));
        if (c.totalCostUsd() == null) {
            row(md, "Estimated cost", "not estimated (no price configured)");
        } else {
            row(md, "Price (USD per million input / output tokens)",
                    plain(c.priceInputUsdPerMillion()) + " / " + plain(c.priceOutputUsdPerMillion()));
            row(md, "Estimated cost of this run (USD)", usd(c.totalCostUsd()));
            row(md, "Estimated cost per 1,000 requests (USD)", usd(c.costPer1000RequestsUsd()));
        }
        md.append(NL).append("Latency is measured by the client through this environment's network and includes "
                        + "retries; it is indicative only.")
                .append(NL).append(NL);
    }

    private static void filesSection(StringBuilder md, BenchmarkRun.Result result) {
        md.append("## Files").append(NL).append(NL)
                .append("- `").append(METRICS).append("`: all metrics, metadata, candidates and cost.").append(NL);
        result.metrics().forEach((mode, splits) -> splits.keySet().forEach(split -> md.append("- `")
                .append(confusionFile(mode, split)).append("`: formal-type confusion matrix.").append(NL)));
        result.outcomes().keySet().forEach(mode -> md.append("- `").append(casesFile(mode))
                .append("`: per-case decision and signals (case ID join key; no message text, no gold labels).")
                .append(NL));
        md.append("- `").append(SWEEP).append("`: calibration-split threshold sweep.").append(NL)
                .append("- `").append(BenchmarkRun.RECORDINGS_FILE)
                .append("`: recorded Jev answers keyed by message SHA-256 (offline replay input).").append(NL)
                .append("- `").append(BenchmarkRun.PARAMETERS_FILE).append("`: run parameters.").append(NL);
    }

    private static void row(StringBuilder md, String field, String value) {
        md.append("| ").append(field).append(CELL).append(value).append(" |").append(NL);
    }

    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String ratio(Double value) {
        return value == null ? NA : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String num1(Double value) {
        return value == null ? NA : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String num2(Double value) {
        return value == null ? NA : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String num4(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String millis(Long value) {
        return value == null ? NA : Long.toString(value);
    }

    private static String usd(Double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String plain(Double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
