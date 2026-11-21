package io.github.denrzv.triage.evaluation.benchmark;

import java.util.List;

/**
 * Token, latency and cost estimate of provider calls (ADR FEATURE-005 decision 6). Only outcomes whose call returned
 * carry usage; failed calls without a response count as {@code failedCalls}. Prices are USD per million tokens and are
 * run parameters; when absent, cost fields are {@code null} ("not estimated"). Estimates can differ from invoices.
 */
public record CostSummary(
        int calls,
        int failedCalls,
        long inputTokens,
        long outputTokens,
        Double meanInputTokens,
        Double meanOutputTokens,
        Double meanAttempts,
        Long latencyP50Ms,
        Long latencyP95Ms,
        Long latencyMaxMs,
        Double priceInputUsdPerMillion,
        Double priceOutputUsdPerMillion,
        Double totalCostUsd,
        Double costPer1000RequestsUsd) {

    private static final double MILLION = 1_000_000.0;
    private static final double THOUSAND = 1_000.0;

    public static CostSummary of(List<CaseOutcome> outcomes, Double priceInput, Double priceOutput) {
        List<CaseOutcome> returned = outcomes.stream().filter(o -> o.usage() != null).toList();
        long input = returned.stream().mapToLong(o -> o.usage().inputTokens()).sum();
        long output = returned.stream().mapToLong(o -> o.usage().outputTokens()).sum();
        long attempts = returned.stream().mapToLong(CaseOutcome::attempts).sum();
        List<Long> latencies = returned.stream().map(o -> o.latency().toMillis()).sorted().toList();
        Double total = null;
        Double perThousand = null;
        if (priceInput != null && priceOutput != null && !returned.isEmpty()) {
            total = (input * priceInput + output * priceOutput) / MILLION;
            perThousand = total / returned.size() * THOUSAND;
        }
        return new CostSummary(
                returned.size(),
                outcomes.size() - returned.size(),
                input,
                output,
                Rates.ratio(input, returned.size()),
                Rates.ratio(output, returned.size()),
                Rates.ratio(attempts, returned.size()),
                Rates.percentile(latencies, 0.50),
                Rates.percentile(latencies, 0.95),
                latencies.isEmpty() ? null : latencies.getLast(),
                priceInput,
                priceOutput,
                total,
                perThousand);
    }
}
