package io.github.denrzv.triage.evaluation.goldset;

import java.util.List;

/** Aggregated goldset validation failure. Messages carry file, line, case ID and field, never full message text. */
public final class GoldsetValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> violations;

    public GoldsetValidationException(List<String> violations) {
        super(violations.size() + " goldset violation(s):\n  " + String.join("\n  ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
