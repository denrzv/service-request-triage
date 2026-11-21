package io.github.denrzv.triage.evaluation.benchmark;

import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.JevClassificationResult;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.RoutingDecision;
import io.github.denrzv.triage.domain.model.SemanticAssessment;
import io.github.denrzv.triage.domain.model.Taxonomy;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevException;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a baseline over goldset cases. Only {@code message} and {@code channel} leave this class towards an assessor
 * or provider. Every decision comes from the Policy Engine with the investigation budget already exhausted, so the
 * baseline has no LLM fallback: ambiguity concerns become {@code HUMAN_REVIEW} and {@code LLM_INVESTIGATION} cannot
 * occur. A provider failure is decided by {@code modelUnavailable} and recorded, never defaulted.
 */
public final class BaselineRunner {

    private final Taxonomy taxonomy;
    private final ClassificationPolicyEngine engine;

    public BaselineRunner(Taxonomy taxonomy, ClassificationPolicyEngine engine) {
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public List<CaseOutcome> runRules(
            GoldSplit split, List<GoldCase> cases, KeywordRulesAssessor assessor, ClassificationPolicy policy) {
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        for (GoldCase gold : cases) {
            SemanticAssessment assessment = assessor.assess(gold.message());
            outcomes.add(new CaseOutcome(gold, split, BaselineMode.RULES_ONLY, assessment, decide(assessment, policy),
                    null, null, null, 0));
        }
        return outcomes;
    }

    public List<CaseOutcome> runJev(
            GoldSplit split,
            List<GoldCase> cases,
            JevClassificationPort port,
            JevQuestionSet questionSet,
            JevAssessmentMapper mapper,
            ClassificationPolicy policy) {
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        for (GoldCase gold : cases) {
            outcomes.add(runJevCase(split, gold, port, questionSet, mapper, policy));
        }
        return outcomes;
    }

    /** Policy decision without investigation budget (no LLM fallback in a baseline). */
    public RoutingDecision decide(SemanticAssessment assessment, ClassificationPolicy policy) {
        return engine.decide(assessment, taxonomy, policy, policy.maxInvestigations());
    }

    /** Re-decides an outcome under another policy; provider errors stay on the model-unavailable path. */
    public CaseOutcome redecide(CaseOutcome outcome, ClassificationPolicy policy) {
        RoutingDecision decision = outcome.hasProviderError()
                ? engine.modelUnavailable(taxonomy, policy)
                : decide(outcome.assessment(), policy);
        return outcome.withDecision(decision);
    }

    private CaseOutcome runJevCase(
            GoldSplit split,
            GoldCase gold,
            JevClassificationPort port,
            JevQuestionSet questionSet,
            JevAssessmentMapper mapper,
            ClassificationPolicy policy) {
        JevClassificationResult result = null;
        try {
            result = port.classify(new JevClassificationRequest(gold.message(), gold.channel().name(), questionSet));
            SemanticAssessment assessment = mapper.toAssessment(result);
            return new CaseOutcome(gold, split, BaselineMode.JEV_ONLY, assessment, decide(assessment, policy), null,
                    result.usage(), result.latency(), result.attempts());
        } catch (JevException e) {
            RoutingDecision unavailable = engine.modelUnavailable(taxonomy, policy);
            return new CaseOutcome(gold, split, BaselineMode.JEV_ONLY, null, unavailable,
                    e.getClass().getSimpleName(),
                    result == null ? null : result.usage(),
                    result == null ? null : result.latency(),
                    result == null ? 0 : result.attempts());
        }
    }
}
