package io.github.denrzv.triage.domain.model;

/** Probability that the statement is true. Noul has no confidence field by design. */
public record NoulAnswer(double probability) implements JevAnswer {

    public NoulAnswer {
        Checks.requireUnit(probability, "probability");
    }
}
