package io.github.denrzv.triage.evaluation.goldset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.denrzv.triage.domain.model.RequestedOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldsetValidatorTest {

    @Test
    void acceptsBalancedDistinctDataset() {
        List<GoldCase> cases = GoldCases.balanced(17);

        assertThatCode(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, cases))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateCaseIdAcrossSplits() {
        List<GoldCase> dev = GoldCases.balanced(17);
        GoldCase clash = GoldCases.of(Stratum.VAGUE, 1, "A completely different synthetic text", null);

        assertThatThrownBy(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, dev, GoldSplit.TEST, List.of(clash))))
                .hasMessageContaining("GS-0001: duplicate caseId");
    }

    @Test
    void rejectsNormalizedDuplicateAndNearDuplicateOutsideGroup() {
        List<GoldCase> cases = new ArrayList<>(GoldCases.balanced(17));
        cases.add(GoldCases.of(Stratum.VAGUE, 901, "SYNTHETIC case-text 1!", null));
        cases.add(GoldCases.of(Stratum.VAGUE, 902, "The mobile app crashes every time I open the card screen", null));
        cases.add(GoldCases.of(Stratum.VAGUE, 903, "The mobile app crashes every time I open the card screen now",
                null));

        assertThatThrownBy(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, cases)))
                .hasMessageContaining("GS-0901: message duplicates GS-0001 after normalization")
                .hasMessageContaining("GS-0903: near-duplicate of GS-0902");
    }

    @Test
    void allowsNearDuplicatesInsideParaphraseGroup() {
        List<GoldCase> cases = new ArrayList<>(GoldCases.balanced(17));
        cases.add(GoldCases.of(Stratum.VAGUE, 902, "The mobile app crashes every time I open the card screen", "PG-01"));
        cases.add(GoldCases.of(Stratum.VAGUE, 903, "The mobile app crashes every time I open the card screen now",
                "PG-01"));

        assertThatCode(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, cases))).doesNotThrowAnyException();
    }

    @Test
    void rejectsIncoherentParaphraseGroups() {
        List<GoldCase> dev = new ArrayList<>(GoldCases.balanced(17));
        dev.add(GoldCases.of(Stratum.VAGUE, 901, "Synthetic singleton paraphrase text", "PG-01"));
        dev.add(GoldCases.of(Stratum.VAGUE, 902, "Synthetic straddling paraphrase alpha", "PG-02"));
        dev.add(GoldCases.of(Stratum.VAGUE, 904, "Synthetic strata paraphrase gamma", "PG-03"));
        dev.add(GoldCases.of(Stratum.NEAR_TIE, 905, "Synthetic strata paraphrase delta", "PG-03"));
        GoldCase base = GoldCases.of(Stratum.VAGUE, 906, "Synthetic label paraphrase epsilon", "PG-04");
        dev.add(base);
        dev.add(new GoldCase("GS-0907", "Synthetic label paraphrase zeta", base.channel(), base.labelSource(),
                base.taxonomyVersion(), base.stratum(), base.intent(), base.secondaryIntents(), base.domain(),
                RequestedOutcome.INVESTIGATE, base.formalSrType(), base.reasonCode(), base.recommendedRoute(),
                base.requiresHumanReview(), base.alternatives(), "PG-04", base.labelRationale()));
        List<GoldCase> test = List.of(GoldCases.of(Stratum.VAGUE, 903, "Synthetic straddling paraphrase beta", "PG-02"));

        assertThatThrownBy(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, dev, GoldSplit.TEST, test)))
                .hasMessageContaining("PG-01: paraphrase group needs at least 2 cases")
                .hasMessageContaining("PG-02: straddles dev/test")
                .hasMessageContaining("PG-03: spans strata")
                .hasMessageContaining("PG-04: labels differ between GS-0906 and GS-0907");
    }

    @Test
    void rejectsUnbalancedDataset() {
        List<GoldCase> cases = new ArrayList<>(GoldCases.balanced(12));
        cases.removeIf(gold -> gold.stratum() == Stratum.NEAR_TIE && !gold.caseId().equals("GS-0121"));

        assertThatThrownBy(() -> GoldsetValidator.validate(Map.of(GoldSplit.DEV, cases)))
                .hasMessageContaining("dataset size 133 outside [200, 300]")
                .hasMessageContaining("stratum NEAR_TIE has 1 cases, needs at least 12");
    }
}
