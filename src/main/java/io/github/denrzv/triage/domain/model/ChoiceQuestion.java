package io.github.denrzv.triage.domain.model;

import java.util.Map;

/** Pick exactly one named criterion. {@code criteria} maps choice name to its description. */
public record ChoiceQuestion(String instructions, Map<String, String> criteria) implements JevQuestion {

    public static final int MAX_CRITERIA = 255;

    public ChoiceQuestion {
        Checks.requireText(instructions, "instructions");
        criteria = Checks.orderedCopy(criteria, "criteria");
        Checks.requireSize(criteria.size(), 1, MAX_CRITERIA, "criteria");
    }
}
