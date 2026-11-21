package io.github.denrzv.triage.graph;

import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.assessment.TriageQuestionSets;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevException;

/**
 * {@code JevClassify}: exactly one Jev call with question set {@value TriageQuestionSets#V1}, sending only the message
 * and channel. A mapped assessment selects {@code classified}; any {@link JevException} (client, unavailable, or a
 * protocol violation detected by the adapter or the strict mapper) selects {@code error} and records only the
 * exception class, never a defaulted confidence.
 */
final class JevClassifyNode implements TriageStep {

    private final JevClassificationPort jev;
    private final JevAssessmentMapper mapper;

    JevClassifyNode(JevClassificationPort jev, JevAssessmentMapper mapper) {
        this.jev = jev;
        this.mapper = mapper;
    }

    @Override
    public TriageNode node() {
        return TriageNode.JEV_CLASSIFY;
    }

    @Override
    public TriageGraphState apply(TriageGraphState state) {
        String channel = state.input().channel() == null ? null : state.input().channel().name();
        JevClassificationRequest request =
                new JevClassificationRequest(state.input().message(), channel, TriageQuestionSets.v1());
        try {
            JevClassificationResult result = jev.classify(request);
            SemanticAssessment assessment = mapper.toAssessment(result);
            return route(
                    state.withAssessment(assessment, result.questionSetVersion(), result.resolvedModel()),
                    TriageRoutes.CLASSIFIED);
        } catch (JevException e) {
            return route(
                    state.withProviderError(e.getClass().getSimpleName(), TriageQuestionSets.V1), TriageRoutes.ERROR);
        }
    }
}
