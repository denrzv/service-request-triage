package io.github.denrzv.triage.domain.model;

import java.util.List;

/** Rubric question; the level index (starting at 0) is the score. */
public record ScoreQuestion(String instructions, List<String> levels) implements JevQuestion {

    public static final int MAX_LEVELS = 10;

    public ScoreQuestion {
        Checks.requireText(instructions, "instructions");
        levels = Checks.textList(levels, "levels");
        Checks.requireSize(levels.size(), 1, MAX_LEVELS, "levels");
    }
}
