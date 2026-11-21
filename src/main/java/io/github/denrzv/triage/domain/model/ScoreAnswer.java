package io.github.denrzv.triage.domain.model;

import java.util.List;

/** Probability-weighted level index (may be fractional), confidence and per-level probabilities. */
public record ScoreAnswer(double score, double confidence, List<Double> levelProbabilities) implements JevAnswer {

    public ScoreAnswer {
        Checks.requireUnit(confidence, "confidence");
        if (levelProbabilities == null || levelProbabilities.isEmpty()) {
            throw new IllegalArgumentException("levelProbabilities must not be empty");
        }
        levelProbabilities = List.copyOf(levelProbabilities);
        levelProbabilities.forEach(value -> Checks.requireUnit(value, "probability"));
        if (Double.isNaN(score) || score < 0.0 || score > levelProbabilities.size() - 1) {
            throw new IllegalArgumentException("score must be within the level range");
        }
    }
}
