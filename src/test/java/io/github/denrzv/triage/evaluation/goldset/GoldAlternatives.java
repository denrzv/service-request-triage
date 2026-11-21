package io.github.denrzv.triage.evaluation.goldset;

import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Labels that a careful reviewer would also accept for an ambiguous case. Evaluation may report strict accuracy
 * (primary label only) and lenient accuracy (primary or alternative) separately.
 */
public record GoldAlternatives(
        List<PrimaryIntent> intent,
        List<ProblemDomain> domain,
        List<RequestedOutcome> requestedOutcome,
        List<ServiceRequestType> formalSrType,
        List<RouteAction> recommendedRoute) {

    public GoldAlternatives {
        intent = distinct(intent, "alternatives.intent");
        domain = distinct(domain, "alternatives.domain");
        requestedOutcome = distinct(requestedOutcome, "alternatives.requestedOutcome");
        formalSrType = distinct(formalSrType, "alternatives.formalSrType");
        recommendedRoute = distinct(recommendedRoute, "alternatives.recommendedRoute");
        if (recommendedRoute.contains(RouteAction.LLM_INVESTIGATION)) {
            throw new IllegalArgumentException("alternatives.recommendedRoute must contain terminal routes only");
        }
    }

    public static GoldAlternatives none() {
        return new GoldAlternatives(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return intent.isEmpty()
                && domain.isEmpty()
                && requestedOutcome.isEmpty()
                && formalSrType.isEmpty()
                && recommendedRoute.isEmpty();
    }

    static <T> List<T> distinct(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return List.copyOf(values);
    }
}
