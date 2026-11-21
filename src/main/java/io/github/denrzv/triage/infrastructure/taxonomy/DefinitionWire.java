package io.github.denrzv.triage.infrastructure.taxonomy;

import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.model.SignalRequirement;
import java.util.List;
import java.util.Map;

/** JSON shapes of the versioned definition files. All properties are required; unknown ones are rejected. */
final class DefinitionWire {

    private DefinitionWire() {}

    record TaxonomyFile(
            String version,
            Boolean synthetic,
            String description,
            Map<PrimaryIntent, String> primaryIntents,
            Map<ProblemDomain, String> problemDomains,
            Map<RequestedOutcome, String> requestedOutcomes,
            Map<ServiceRequestType, String> serviceRequestTypes,
            List<ReasonCodeEntry> reasonCodes) {}

    record ReasonCodeEntry(String code, ServiceRequestType serviceRequestType, String description) {}

    record PolicyFile(
            String version,
            String taxonomyVersion,
            Boolean synthetic,
            Boolean calibrated,
            String description,
            Thresholds thresholds,
            RouteAction lowTaxonomyFitAction,
            RouteAction noRuleMatchedAction,
            Integer maxInvestigations,
            List<ServiceRequestType> humanApprovalTypes,
            List<Rule> rules) {}

    record Thresholds(
            Double taxonomyFitMin,
            Double choiceConfidenceMin,
            Double choiceMarginMin,
            Double multiIntentMax,
            Double signalMin) {}

    record Rule(String id, String description, Condition when, Outcome then) {}

    record Condition(
            List<PrimaryIntent> primaryIntents,
            List<ProblemDomain> problemDomains,
            List<RequestedOutcome> requestedOutcomes,
            SignalRequirement refundRequested,
            SignalRequirement technicalProblem) {}

    record Outcome(ServiceRequestType serviceRequestType, String reasonCode) {}
}
