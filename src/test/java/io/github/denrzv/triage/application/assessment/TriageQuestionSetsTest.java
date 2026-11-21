package io.github.denrzv.triage.application.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.denrzv.triage.domain.model.ChoiceQuestion;
import io.github.denrzv.triage.domain.model.JevQuestionSet;
import io.github.denrzv.triage.domain.model.NoulQuestion;
import io.github.denrzv.triage.domain.model.PrimaryIntent;
import io.github.denrzv.triage.domain.model.ProblemDomain;
import io.github.denrzv.triage.domain.model.RequestedOutcome;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TriageQuestionSetsTest {

    private final JevQuestionSet set = TriageQuestionSets.v1();

    @Test
    void v1AsksThreeChoicesAndFourNoulsInStableOrder() {
        assertThat(set.version()).isEqualTo(TriageQuestionSets.V1);
        assertThat(set.questions()).containsOnlyKeys(
                TriageQuestionSets.PRIMARY_INTENT,
                TriageQuestionSets.PROBLEM_DOMAIN,
                TriageQuestionSets.REQUESTED_OUTCOME,
                TriageQuestionSets.REFUND_REQUESTED,
                TriageQuestionSets.TECHNICAL_PROBLEM,
                TriageQuestionSets.MULTI_INTENT,
                TriageQuestionSets.TAXONOMY_FIT);
        assertThat(set.questions().keySet()).first().isEqualTo(TriageQuestionSets.PRIMARY_INTENT);
        assertThat(set.questions().get(TriageQuestionSets.TAXONOMY_FIT)).isInstanceOf(NoulQuestion.class);
        assertThat(set.questions().get(TriageQuestionSets.MULTI_INTENT)).isInstanceOf(NoulQuestion.class);
    }

    @Test
    void choiceCriteriaAreExactlyTheLowerCaseTaxonomyEnumNames() {
        assertCriteria(TriageQuestionSets.PRIMARY_INTENT, PrimaryIntent.values());
        assertCriteria(TriageQuestionSets.PROBLEM_DOMAIN, ProblemDomain.values());
        assertCriteria(TriageQuestionSets.REQUESTED_OUTCOME, RequestedOutcome.values());
    }

    @Test
    void questionSetIsASingleImmutableInstance() {
        assertThat(TriageQuestionSets.v1()).isSameAs(set);
        assertThat(TriageQuestionSets.criterionKey(ProblemDomain.DIGITAL_CHANNELS)).isEqualTo("digital_channels");
    }

    private void assertCriteria(String question, Enum<?>[] constants) {
        ChoiceQuestion choice = (ChoiceQuestion) set.questions().get(question);
        List<String> expected = Arrays.stream(constants).map(TriageQuestionSets::criterionKey).toList();
        assertThat(choice.criteria().keySet()).containsExactlyElementsOf(expected);
        assertThat(choice.criteria().values()).allSatisfy(text -> assertThat(text).isNotBlank());
    }
}
