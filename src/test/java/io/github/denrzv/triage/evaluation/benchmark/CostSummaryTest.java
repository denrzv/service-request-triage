package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostSummaryTest {

    private final List<CaseOutcome> outcomes = outcomes();

    @Test
    void sumsTokensAndComputesNearestRankLatencyPercentiles() {
        CostSummary cost = CostSummary.of(outcomes, null, null);

        assertThat(cost.calls()).isEqualTo(4);
        assertThat(cost.failedCalls()).isEqualTo(1);
        assertThat(cost.inputTokens()).isEqualTo(4000);
        assertThat(cost.outputTokens()).isEqualTo(400);
        assertThat(cost.meanInputTokens()).isEqualTo(1000.0);
        assertThat(cost.meanAttempts()).isEqualTo(1.25);
        assertThat(cost.latencyP50Ms()).isEqualTo(200L);
        assertThat(cost.latencyP95Ms()).isEqualTo(400L);
        assertThat(cost.latencyMaxMs()).isEqualTo(400L);
        assertThat(cost.totalCostUsd()).isNull();
        assertThat(cost.costPer1000RequestsUsd()).isNull();
    }

    @Test
    void estimatesCostOnlyWhenPricesAreGiven() {
        CostSummary cost = CostSummary.of(outcomes, 0.042, 0.0);

        // 4000 input tokens * 0.042 USD per million = 0.000168 USD; per 1000 requests: 0.000168 / 4 * 1000.
        assertThat(cost.totalCostUsd()).isCloseTo(0.000168, within(1e-12));
        assertThat(cost.costPer1000RequestsUsd()).isCloseTo(0.042, within(1e-12));
    }

    @Test
    void noCallsGiveUndefinedValues() {
        CostSummary cost = CostSummary.of(List.of(), 0.042, 0.0);

        assertThat(cost.calls()).isZero();
        assertThat(cost.latencyP50Ms()).isNull();
        assertThat(cost.totalCostUsd()).isNull();
    }

    private static List<CaseOutcome> outcomes() {
        List<CaseOutcome> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            list.add(new CaseOutcome(BenchmarkFixtures.information(i), GoldSplit.DEV, BaselineMode.JEV_ONLY,
                    BenchmarkFixtures.assessment(PrimaryIntent.INQUIRY,
                            ProblemDomain.ACCOUNTS, 0.9, 0.9, 0.9),
                    BenchmarkMetricsTest.decision(RouteAction.DRAFT_PROPOSAL, ServiceRequestType.INFORMATION_REQUEST),
                    null, new JevUsage(1000, 100), Duration.ofMillis(100L * (5 - i)), i == 4 ? 2 : 1));
        }
        list.add(new CaseOutcome(BenchmarkFixtures.information(5), GoldSplit.DEV, BaselineMode.JEV_ONLY, null,
                BenchmarkMetricsTest.decision(RouteAction.HUMAN_REVIEW, ServiceRequestType.UNCLASSIFIED),
                "JevUnavailableException", null, null, 0));
        return list;
    }
}
