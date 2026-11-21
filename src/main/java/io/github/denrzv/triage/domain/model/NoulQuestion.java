package io.github.denrzv.triage.domain.model;

/** Yes/no statement answered with a single probability of "true". */
public record NoulQuestion(String instructions) implements JevQuestion {

    public NoulQuestion {
        Checks.requireText(instructions, "instructions");
    }
}
