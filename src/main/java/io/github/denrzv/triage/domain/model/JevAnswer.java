package io.github.denrzv.triage.domain.model;

/** Typed Jev answer. Probabilities and confidences are provider outputs, not verified correctness. */
public sealed interface JevAnswer permits ChoiceAnswer, NoulAnswer, ScoreAnswer {}
