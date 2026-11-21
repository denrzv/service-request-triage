package io.github.denrzv.triage.evaluation.goldset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GoldsetSplitterTest {

    private static final String SEED = "unit-test-seed";

    @ParameterizedTest(name = "n={0} -> {1}")
    @CsvSource({"20, 6", "24, 7", "16, 5", "12, 4", "5, 2", "1, 0"})
    void targetRoundsHalfUp(int size, int expected) {
        assertThat(GoldsetSplitter.target(size, 0.30)).isEqualTo(expected);
    }

    @Test
    void assignmentIsDeterministicAndOrderIndependent() {
        List<GoldCase> cases = GoldCases.balanced(12);
        List<GoldCase> shuffled = new ArrayList<>(cases);
        Collections.reverse(shuffled);

        Map<String, GoldSplit> first = GoldsetSplitter.assign(cases, SEED, 0.30);

        assertThat(GoldsetSplitter.assign(shuffled, SEED, 0.30)).isEqualTo(first);
        assertThat(first).hasSize(cases.size());
        assertThat(first.values().stream().filter(GoldSplit.TEST::equals).count())
                .isEqualTo(4L * Stratum.values().length);
    }

    @Test
    void differentSeedChangesAssignment() {
        List<GoldCase> cases = GoldCases.balanced(12);

        assertThat(GoldsetSplitter.assign(cases, "other-seed", 0.30))
                .isNotEqualTo(GoldsetSplitter.assign(cases, SEED, 0.30));
    }

    @Test
    void keepsParaphraseGroupTogether() {
        List<GoldCase> cases = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String group = i <= 3 ? "PG-01" : (i <= 6 ? "PG-02" : null);
            cases.add(GoldCases.of(Stratum.VAGUE, i, "Synthetic vague text " + i, group));
        }

        for (String seed : List.of("a", "b", "c", "d", "e", "f")) {
            Map<String, GoldSplit> split = GoldsetSplitter.assign(cases, seed, 0.30);
            assertThat(split.get("GS-0001")).isEqualTo(split.get("GS-0002")).isEqualTo(split.get("GS-0003"));
            assertThat(split.get("GS-0004")).isEqualTo(split.get("GS-0005")).isEqualTo(split.get("GS-0006"));
            long test = split.values().stream().filter(GoldSplit.TEST::equals).count();
            assertThat(test).isBetween(4L, 6L);
        }
    }

    @Test
    void rejectsGroupSpanningStrataAndInvalidRecipe() {
        List<GoldCase> cases = List.of(
                GoldCases.of(Stratum.VAGUE, 1, "Synthetic vague text one", "PG-01"),
                GoldCases.of(Stratum.NEAR_TIE, 2, "Synthetic near tie text two", "PG-01"));

        assertThatThrownBy(() -> GoldsetSplitter.assign(cases, SEED, 0.30)).hasMessageContaining("spans strata");
        assertThatThrownBy(() -> GoldsetSplitter.assign(cases, " ", 0.30)).hasMessageContaining("seed");
        assertThatThrownBy(() -> GoldsetSplitter.assign(cases, SEED, 1.0)).hasMessageContaining("testFraction");
    }

    @Test
    void rankUsesSha256OfSeedAndUnit() {
        assertThat(GoldsetSplitter.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
