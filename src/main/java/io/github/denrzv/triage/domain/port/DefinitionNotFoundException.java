package io.github.denrzv.triage.domain.port;

/** A requested taxonomy or policy version is not loaded. There is never a silent fallback to another version. */
public class DefinitionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DefinitionNotFoundException(String kind, String version) {
        super(kind + " version " + version + " is not loaded");
    }
}
