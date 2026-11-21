package io.github.denrzv.triage.domain.model;

/** Synthetic intake channel of a customer request (docs/contracts.md section 1). Context only, never a label. */
public enum Channel {
    INTERNET_BANK,
    CONTACT_CENTER,
    WEB_FORM
}
