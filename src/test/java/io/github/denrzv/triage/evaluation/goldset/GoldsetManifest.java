package io.github.denrzv.triage.evaluation.goldset;

import java.util.Map;

/**
 * {@code manifest.json} of a frozen goldset version: split recipe, per-file SHA-256 and counts. Changing any record
 * changes a hash, so a benchmark report that cites the manifest hashes is reproducible.
 */
public record GoldsetManifest(
        String datasetId,
        String version,
        String taxonomyVersion,
        boolean synthetic,
        LabelSource labelSource,
        SplitRecipe split,
        FileEntry dev,
        FileEntry test,
        int totalRecords,
        Map<Stratum, StratumCount> strata) {

    public GoldsetManifest {
        strata = Map.copyOf(strata);
    }

    public FileEntry file(GoldSplit split) {
        return split == GoldSplit.DEV ? dev : test;
    }

    /** Deterministic split recipe; see {@link GoldsetSplitter}. */
    public record SplitRecipe(String algorithm, String seed, double testFraction) {}

    public record FileEntry(String path, int records, String sha256) {}

    public record StratumCount(int dev, int test) {}
}
