package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.domain.model.JevUsage;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import java.time.Duration;
import java.util.Objects;

/**
 * Result of one baseline for one goldset case. {@code gold} is kept for scoring only; it is never passed to an
 * assessor or provider. {@code assessment} is {@code null} exactly when {@code providerError} is set; {@code usage}
 * and {@code latency} are present whenever a provider call returned (even if its answers were then rejected).
 */
public record CaseOutcome(
        GoldCase gold,
        GoldSplit split,
        BaselineMode mode,
        SemanticAssessment assessment,
        RoutingDecision decision,
        String providerError,
        JevUsage usage,
        Duration latency,
        int attempts) {

    public CaseOutcome {
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(decision, "decision");
        if ((assessment == null) == (providerError == null)) {
            throw new IllegalArgumentException("exactly one of assessment and providerError must be present");
        }
        if ((usage == null) != (latency == null)) {
            throw new IllegalArgumentException("usage and latency must be present together");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public String caseId() {
        return gold.caseId();
    }

    public boolean hasProviderError() {
        return providerError != null;
    }

    /** Same case re-decided under another policy (threshold exploration); provider data is unchanged. */
    public CaseOutcome withDecision(RoutingDecision newDecision) {
        return new CaseOutcome(gold, split, mode, assessment, newDecision, providerError, usage, latency, attempts);
    }
}
