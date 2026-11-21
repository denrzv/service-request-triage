package io.github.denrzv.triage.domain.model;

import java.util.Map;

/** Versioned set of named questions sent in one Jev call. The version is echoed in results, never interpreted. */
public record JevQuestionSet(String version, Map<String, JevQuestion> questions) {

    public JevQuestionSet {
        Checks.requireText(version, "version");
        questions = Checks.orderedCopy(questions, "questions");
        Checks.requireSize(questions.size(), 1, Integer.MAX_VALUE, "questions");
    }
}
