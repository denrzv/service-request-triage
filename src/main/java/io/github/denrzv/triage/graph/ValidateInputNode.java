package io.github.denrzv.triage.graph;

import java.util.Optional;

/** {@code ValidateInput}: accepts a bounded, normalized request ({@code valid}) or rejects it ({@code invalid}). */
final class ValidateInputNode implements TriageStep {

    private final InputValidator validator;

    ValidateInputNode(InputValidator validator) {
        this.validator = validator;
    }

    @Override
    public TriageNode node() {
        return TriageNode.VALIDATE_INPUT;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        Optional<String> violation = validator.violation(state.input());
        if (violation.isPresent()) {
            TriageFailure failure = new TriageFailure(FailureCode.INPUT_REJECTED, node().id(), violation.get());
            return route(state.withFailure(failure), TriageRoutes.INVALID);
        }
        return route(state.withInput(validator.normalize(state.input())), TriageRoutes.VALID);
    }
}
