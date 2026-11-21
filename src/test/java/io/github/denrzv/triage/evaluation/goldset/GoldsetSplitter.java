package io.github.denrzv.triage.evaluation.goldset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Language-neutral, deterministic split (algorithm {@value #ALGORITHM}); mirrors {@code assign_split} in
 * {@code tools/goldset.py}. Per stratum, split units (paraphrase group, otherwise case) are ranked by
 * {@code sha256("<seed>:<unitId>")} and assigned to {@link GoldSplit#TEST} while the stratum's test count is below
 * {@code floor(testFraction * stratumSize + 0.5)}. Paraphrases therefore never straddle the split.
 */
public final class GoldsetSplitter {

    public static final String ALGORITHM = "sha256-rank-per-stratum-v1";

    private GoldsetSplitter() {}

    /** Returns the split of every case ID. */
    public static Map<String, GoldSplit> assign(Collection<GoldCase> cases, String seed, double testFraction) {
        if (seed == null || seed.isBlank()) {
            throw new IllegalArgumentException("seed must not be blank");
        }
        if (!(testFraction > 0.0 && testFraction < 1.0)) {
            throw new IllegalArgumentException("testFraction must be within (0, 1)");
        }
        Map<Stratum, Map<String, List<GoldCase>>> units = groupUnits(cases);
        Map<String, GoldSplit> result = new HashMap<>();
        units.forEach((stratum, byUnit) -> assignStratum(byUnit, seed, testFraction, result));
        return result;
    }

    static int target(int stratumSize, double testFraction) {
        return (int) Math.floor(testFraction * stratumSize + 0.5);
    }

    private static Map<Stratum, Map<String, List<GoldCase>>> groupUnits(Collection<GoldCase> cases) {
        Map<Stratum, Map<String, List<GoldCase>>> units = new EnumMap<>(Stratum.class);
        Map<String, Stratum> unitStratum = new HashMap<>();
        for (GoldCase gold : cases) {
            Stratum previous = unitStratum.putIfAbsent(gold.splitUnit(), gold.stratum());
            if (previous != null && previous != gold.stratum()) {
                throw new IllegalArgumentException(
                        "split unit " + gold.splitUnit() + " spans strata " + previous + " and " + gold.stratum());
            }
            units.computeIfAbsent(gold.stratum(), key -> new TreeMap<>())
                    .computeIfAbsent(gold.splitUnit(), key -> new ArrayList<>())
                    .add(gold);
        }
        return units;
    }

    private static void assignStratum(
            Map<String, List<GoldCase>> byUnit, String seed, double testFraction, Map<String, GoldSplit> result) {
        int size = byUnit.values().stream().mapToInt(List::size).sum();
        int target = target(size, testFraction);
        Map<String, String> rank = new LinkedHashMap<>();
        byUnit.keySet().forEach(unit -> rank.put(unit, sha256Hex(seed + ":" + unit)));
        List<String> ordered = new ArrayList<>(byUnit.keySet());
        ordered.sort(Comparator.comparing((String unit) -> rank.get(unit)).thenComparing(Comparator.naturalOrder()));
        int testCount = 0;
        for (String unit : ordered) {
            GoldSplit split = testCount < target ? GoldSplit.TEST : GoldSplit.DEV;
            List<GoldCase> members = byUnit.get(unit);
            if (split == GoldSplit.TEST) {
                testCount += members.size();
            }
            members.forEach(gold -> result.put(gold.caseId(), split));
        }
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
