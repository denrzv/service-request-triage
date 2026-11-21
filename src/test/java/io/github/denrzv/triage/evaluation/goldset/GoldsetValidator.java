package io.github.denrzv.triage.evaluation.goldset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-record goldset rules; mirrors {@code validate_dataset} in {@code tools/goldset.py}: unique IDs and
 * normalized messages, near-duplicates only inside a declared paraphrase group, coherent paraphrase groups that
 * never straddle the split, and dataset balance.
 */
public final class GoldsetValidator {

    public static final double NEAR_DUPLICATE_JACCARD = 0.8;
    public static final int MIN_TOTAL = 200;
    public static final int MAX_TOTAL = 300;
    public static final int MIN_PER_STRATUM = 12;
    public static final int MIN_GROUP_SIZE = 2;

    private GoldsetValidator() {}

    /** Validates the union of the given splits; throws {@link GoldsetValidationException} listing every violation. */
    public static void validate(Map<GoldSplit, List<GoldCase>> splits) {
        List<String> violations = new ArrayList<>();
        List<GoldCase> all = new ArrayList<>();
        Map<String, GoldSplit> splitOf = new HashMap<>();
        splits.forEach((split, cases) -> cases.forEach(gold -> {
            all.add(gold);
            if (splitOf.putIfAbsent(gold.caseId(), split) != null) {
                violations.add(gold.caseId() + ": duplicate caseId");
            }
        }));
        checkDuplicateText(all, violations);
        checkParaphraseGroups(all, splitOf, violations);
        checkBalance(all, violations);
        if (!violations.isEmpty()) {
            throw new GoldsetValidationException(violations);
        }
    }

    private static void checkDuplicateText(List<GoldCase> all, List<String> violations) {
        Map<String, String> byNormalized = new HashMap<>();
        List<Set<String>> tokens = all.stream().map(gold -> TextNormalizer.tokens(gold.message())).toList();
        for (GoldCase gold : all) {
            String previous = byNormalized.putIfAbsent(TextNormalizer.normalize(gold.message()), gold.caseId());
            if (previous != null) {
                violations.add(gold.caseId() + ": message duplicates " + previous + " after normalization");
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                GoldCase left = all.get(i);
                GoldCase right = all.get(j);
                boolean sameGroup = left.paraphraseGroup() != null
                        && left.paraphraseGroup().equals(right.paraphraseGroup());
                double similarity = TextNormalizer.jaccard(tokens.get(i), tokens.get(j));
                if (!sameGroup && similarity >= NEAR_DUPLICATE_JACCARD) {
                    violations.add(String.format(
                            "%s: near-duplicate of %s (token Jaccard %.2f) outside a paraphrase group",
                            right.caseId(), left.caseId(), similarity));
                }
            }
        }
    }

    private static void checkParaphraseGroups(
            List<GoldCase> all, Map<String, GoldSplit> splitOf, List<String> violations) {
        Map<String, List<GoldCase>> groups = new TreeMap<>();
        all.stream()
                .filter(gold -> gold.paraphraseGroup() != null)
                .forEach(gold -> groups.computeIfAbsent(gold.paraphraseGroup(), key -> new ArrayList<>()).add(gold));
        groups.forEach((group, members) -> {
            if (members.size() < MIN_GROUP_SIZE) {
                violations.add(group + ": paraphrase group needs at least " + MIN_GROUP_SIZE + " cases");
            }
            GoldCase first = members.getFirst();
            for (GoldCase member : members) {
                if (member.stratum() != first.stratum()) {
                    violations.add(group + ": spans strata (" + member.caseId() + ")");
                }
                if (splitOf.get(member.caseId()) != splitOf.get(first.caseId())) {
                    violations.add(group + ": straddles dev/test (" + member.caseId() + ")");
                }
                if (!sameLabels(first, member)) {
                    violations.add(group + ": labels differ between " + first.caseId() + " and " + member.caseId());
                }
            }
        });
    }

    private static boolean sameLabels(GoldCase left, GoldCase right) {
        return left.intent() == right.intent()
                && left.secondaryIntents().equals(right.secondaryIntents())
                && left.domain() == right.domain()
                && left.requestedOutcome() == right.requestedOutcome()
                && left.formalSrType() == right.formalSrType()
                && left.reasonCode().equals(right.reasonCode())
                && left.recommendedRoute() == right.recommendedRoute();
    }

    private static void checkBalance(List<GoldCase> all, List<String> violations) {
        if (all.size() < MIN_TOTAL || all.size() > MAX_TOTAL) {
            violations.add("dataset size " + all.size() + " outside [" + MIN_TOTAL + ", " + MAX_TOTAL + "]");
        }
        Map<Stratum, Integer> counts = new EnumMap<>(Stratum.class);
        all.forEach(gold -> counts.merge(gold.stratum(), 1, Integer::sum));
        for (Stratum stratum : Stratum.values()) {
            int count = counts.getOrDefault(stratum, 0);
            if (count < MIN_PER_STRATUM) {
                violations.add("stratum " + stratum + " has " + count + " cases, needs at least " + MIN_PER_STRATUM);
            }
        }
    }
}
