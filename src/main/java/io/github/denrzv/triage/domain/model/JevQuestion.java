package io.github.denrzv.triage.domain.model;

/** One typed question asked of Jev: Choice, Noul (yes/no probability) or Score (rubric level). */
public sealed interface JevQuestion permits ChoiceQuestion, NoulQuestion, ScoreQuestion {

    String instructions();
}
