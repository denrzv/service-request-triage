package io.github.denrzv.triage.evaluation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.denrzv.triage.application.assessment.JevAssessmentMapper;
import io.github.denrzv.triage.application.assessment.JevResults;
import io.github.denrzv.triage.application.assessment.TriageQuestionSets;
import io.github.denrzv.triage.application.policy.ClassificationPolicyEngine;
import io.github.denrzv.triage.domain.model.ClassificationPolicy;
import io.github.denrzv.triage.domain.model.JevAnswer;
import io.github.denrzv.triage.domain.model.JevClassificationRequest;
import io.github.denrzv.triage.domain.model.PolicyThresholds;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RationaleCode;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import io.github.denrzv.triage.domain.model.RouteAction;
import io.github.denrzv.triage.domain.model.ServiceRequestType;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevUnavailableException;
import io.github.denrzv.triage.evaluation.goldset.GoldCase;
import io.github.denrzv.triage.evaluation.goldset.GoldSplit;
import io.github.denrzv.triage.infrastructure.taxonomy.ShippedDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BaselineRunnerTest {

    private final ClassificationPolicy policy = ShippedDefinitions.policyV1();
    private final BaselineRunner runner =
            new BaselineRunner(ShippedDefinitions.taxonomyV1(), new ClassificationPolicyEngine());

    @Test
    void jevOnlySendsOnlyMessageAndChannelAndUsesThePolicyEngine() {
        List<JevClassificationRequest> sent = new ArrayList<>();
        JevClassificationPort port = request -> {
            sent.add(request);
            return JevResults.result(JevResults.answers(
                    PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.9, 0.95));
        };
        GoldCase gold = BenchmarkFixtures.information(1);

        CaseOutcome outcome = runJev(List.of(gold), port).getFirst();

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.message()).isEqualTo(gold.message());
            assertThat(request.channel()).isEqualTo("WEB_FORM");
            assertThat(request.questionSet().version()).isEqualTo(TriageQuestionSets.V1);
        });
        assertThat(outcome.mode()).isEqualTo(BaselineMode.JEV_ONLY);
        assertThat(outcome.split()).isEqualTo(GoldSplit.DEV);
        assertThat(outcome.decision().action()).isEqualTo(RouteAction.DRAFT_PROPOSAL);
        assertThat(outcome.decision().proposal().proposedType()).isEqualTo(ServiceRequestType.INFORMATION_REQUEST);
        assertThat(outcome.usage().inputTokens()).isEqualTo(500);
        assertThat(outcome.attempts()).isEqualTo(1);
    }

    @Test
    void providerFailureTakesTheModelUnavailableReviewPath() {
        JevClassificationPort port = request -> {
            throw new JevUnavailableException(3, 503, null);
        };

        CaseOutcome outcome = runJev(List.of(BenchmarkFixtures.claim(2)), port).getFirst();

        assertThat(outcome.hasProviderError()).isTrue();
        assertThat(outcome.providerError()).isEqualTo("JevUnavailableException");
        assertThat(outcome.assessment()).isNull();
        assertThat(outcome.usage()).isNull();
        assertThat(outcome.decision().action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(outcome.decision().rationaleCodes()).contains(RationaleCode.MODEL_UNAVAILABLE);
    }

    @Test
    void rejectedAnswersKeepUsageButTakeTheSafePath() {
        Map<String, JevAnswer> answers = JevResults.answers(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.9, 0.95);
        answers.remove(TriageQuestionSets.MULTI_INTENT);
        JevClassificationPort port = request -> JevResults.result(answers);

        CaseOutcome outcome = runJev(List.of(BenchmarkFixtures.information(3)), port).getFirst();

        assertThat(outcome.providerError()).isEqualTo("JevProtocolException");
        assertThat(outcome.usage()).isNotNull();
        assertThat(outcome.decision().proposal().reasonCode()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void ambiguityBecomesHumanReviewBecauseNoInvestigationBudgetRemains() {
        ClassificationPolicy strict = withThresholds(new PolicyThresholds(0.0, 0.99, 0.0, 1.0, 0.5));
        JevClassificationPort port = request -> JevResults.result(JevResults.answers(
                PrimaryIntent.INQUIRY, ProblemDomain.ACCOUNTS, RequestedOutcome.EXPLAIN, 0.6, 0.95));

        CaseOutcome outcome = runner.runJev(GoldSplit.DEV, List.of(BenchmarkFixtures.information(4)), port,
                TriageQuestionSets.v1(), new JevAssessmentMapper(), strict).getFirst();

        assertThat(outcome.decision().action()).isEqualTo(RouteAction.HUMAN_REVIEW);
        assertThat(outcome.decision().rationaleCodes())
                .contains(RationaleCode.LOW_CONFIDENCE, RationaleCode.INVESTIGATION_BUDGET_EXHAUSTED);
    }

    @Test
    void rulesOnlyNeverRoutesToLlmAndRedecideKeepsProviderData() {
        List<GoldCase> cases = List.of(BenchmarkFixtures.claim(5), BenchmarkFixtures.outOfTaxonomy(6));

        List<CaseOutcome> outcomes = runner.runRules(GoldSplit.TEST, cases, new KeywordRulesAssessor(), policy);

        assertThat(outcomes).hasSize(2).allSatisfy(o -> {
            assertThat(o.mode()).isEqualTo(BaselineMode.RULES_ONLY);
            assertThat(o.decision().action()).isNotEqualTo(RouteAction.LLM_INVESTIGATION);
            assertThat(o.usage()).isNull();
        });
        CaseOutcome redecided = runner.redecide(outcomes.getFirst(), policy);
        assertThat(redecided.assessment()).isSameAs(outcomes.getFirst().assessment());
        assertThat(redecided.decision()).isEqualTo(outcomes.getFirst().decision());
    }

    private List<CaseOutcome> runJev(List<GoldCase> cases, JevClassificationPort port) {
        return runner.runJev(GoldSplit.DEV, cases, port, TriageQuestionSets.v1(), new JevAssessmentMapper(), policy);
    }

    private ClassificationPolicy withThresholds(PolicyThresholds thresholds) {
        return new ClassificationPolicy(policy.version(), policy.taxonomyVersion(), policy.calibrated(), thresholds,
                policy.lowTaxonomyFitAction(), policy.noRuleMatchedAction(), policy.maxInvestigations(),
                policy.humanApprovalTypes(), policy.rules());
    }
}
