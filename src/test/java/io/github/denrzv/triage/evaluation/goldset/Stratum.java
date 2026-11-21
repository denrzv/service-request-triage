package io.github.denrzv.triage.evaluation.goldset;

/** Test-design stratum of a goldset case. Strata balance the dataset; they are not labels a model predicts. */
public enum Stratum {
    SIMPLE_COMPLAINT,
    SIMPLE_INQUIRY,
    SIMPLE_SERVICE_ACTION,
    SIMPLE_INCIDENT,
    DUPLICATE_CHARGE,
    DISSATISFACTION_WITH_TECHNICAL,
    MULTI_INTENT,
    VAGUE,
    CONTRADICTORY,
    OUT_OF_TAXONOMY,
    NEAR_TIE,
    NO_COMPLAINT_CONTROL;

    /** Simple strata hold unambiguous single-intent cases without alternatives or secondary intents. */
    public boolean isSimple() {
        return name().startsWith("SIMPLE_");
    }
}
