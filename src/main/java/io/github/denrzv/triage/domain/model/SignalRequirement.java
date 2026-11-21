package io.github.denrzv.triage.domain.model;

/** Rule condition on a Noul signal: the probability compared with the policy's {@code signalMin}. */
public enum SignalRequirement {
    ANY,
    PRESENT,
    ABSENT;

    /** Returns whether a Noul probability satisfies this requirement for the given signal boundary. */
    public boolean accepts(double noul, double signalMin) {
        return switch (this) {
            case ANY -> true;
            case PRESENT -> noul >= signalMin;
            case ABSENT -> noul < signalMin;
        };
    }
}
