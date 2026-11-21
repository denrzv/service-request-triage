package io.github.denrzv.triage.domain.port;

/** Terminal HTTP failure such as 400, 401, 403 or 422. Never retried. */
public final class JevClientException extends JevException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public JevClientException(int status, String detail) {
        super("Jev rejected the request with HTTP " + status + ": " + detail, null);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
