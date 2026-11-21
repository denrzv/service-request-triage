package io.github.denrzv.triage.domain.port;

/** Retryable failures (429, 5xx, I/O, timeout) persisted until the attempt budget was exhausted. */
public final class JevUnavailableException extends JevException {

    private static final long serialVersionUID = 1L;

    private final int attempts;
    private final int lastStatus;

    /** @param lastStatus last HTTP status, or 0 when the last failure was not an HTTP response */
    public JevUnavailableException(int attempts, int lastStatus, Throwable cause) {
        super("Jev unavailable after " + attempts + " attempt(s), last status " + lastStatus, cause);
        this.attempts = attempts;
        this.lastStatus = lastStatus;
    }

    public int attempts() {
        return attempts;
    }

    public int lastStatus() {
        return lastStatus;
    }
}
