package io.github.denrzv.triage.domain.port;

/** A 2xx response whose body is missing required data or violates the documented schema. Not retried. */
public final class JevProtocolException extends JevException {

    private static final long serialVersionUID = 1L;

    public JevProtocolException(String reason) {
        super("Invalid Jev response: " + reason, null);
    }

    public JevProtocolException(String reason, Throwable cause) {
        super("Invalid Jev response: " + reason, cause);
    }
}
