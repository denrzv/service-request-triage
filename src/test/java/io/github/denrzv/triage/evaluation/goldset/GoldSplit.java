package io.github.denrzv.triage.evaluation.goldset;

/** Frozen split. {@link #TEST} labels are held out: never used for tuning, thresholds or prompts. */
public enum GoldSplit {
    DEV("dev.jsonl"),
    TEST("test.jsonl");

    private final String fileName;

    GoldSplit(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
