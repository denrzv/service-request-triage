package io.github.denrzv.triage.domain.port;

/** Base type for Jev failures. Messages never contain the API key or customer text. */
public abstract class JevException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected JevException(String message, Throwable cause) {
        super(message, cause);
    }
}
