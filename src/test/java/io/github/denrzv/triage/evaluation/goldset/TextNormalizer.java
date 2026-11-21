package io.github.denrzv.triage.evaluation.goldset;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text normalization for duplicate detection; mirrors {@code normalize}/{@code tokens} in {@code tools/goldset.py}. */
final class TextNormalizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private TextNormalizer() {}

    /** Lower-cases and collapses every run of non-alphanumeric characters into one space. */
    static String normalize(String text) {
        return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    static Set<String> tokens(String text) {
        String normalized = normalize(text);
        return normalized.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(normalized.split(" ")));
    }

    /** Jaccard similarity of two token sets; two empty sets are identical. */
    static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        int union = left.size() + right.size() - intersection.size();
        return (double) intersection.size() / union;
    }
}
