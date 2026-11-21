package io.github.denrzv.triage.domain.model;

import java.util.Map;

/** Selected criterion with its confidence and the probability of every criterion. */
public record ChoiceAnswer(String selected, double confidence, Map<String, Double> probabilities)
        implements JevAnswer {

    public ChoiceAnswer {
        Checks.requireText(selected, "selected");
        Checks.requireUnit(confidence, "confidence");
        probabilities = Checks.orderedCopy(probabilities, "probabilities");
        probabilities.values().forEach(value -> Checks.requireUnit(value, "probability"));
        if (!probabilities.containsKey(selected)) {
            throw new IllegalArgumentException("probabilities must contain the selected choice");
        }
    }
}
