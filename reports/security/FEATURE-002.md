# FEATURE-002: Security review
Status: SECURITY_PASS_WITH_NOTES
Outcome: SECURITY_PASS_WITH_NOTES

## Checklist
- Secrets: key only from `JEV_API_KEY` via `application.yml`; `JevProperties.toString()` prints only `apiKeyPresent`; no exception or log carries the key (tests assert `hasMessageNotContaining(API_KEY)`); `.env.example` contains placeholders only; gitleaks passed in the pre-commit run.
- Trust boundaries: customer message is untrusted and only sent in the JSON body; responses are untrusted and strictly validated; response bodies are never logged or placed in exceptions (422 reports only `loc`/`type`, 400/401/403 detail withheld); `JevClassificationRequest.toString()` omits the message.
- Input: local validation (non-blank message, size limits) before any network call; base URL is configuration only (https default); no redirects followed by default HttpClient.
- Authz/audit: no Task Management write path, no goldset exposure, no model-made policy decision; usage and resolved model returned for upstream audit.
- PII/privacy: README notes the cloud provider receives the message text; synthetic English data only; live smoke is opt-in and billed.
- Supply chain: no new dependency; JDK `HttpServer` in tests.

## Findings
No Blocking or Important findings.
Notes (Minor):
- S1: `triage.jev.base-url` is not forced to https; operators could configure plain http. Acceptable for tests and local mocks; consider a guard when non-test deployment config appears.
- S2: A wrapped `IOException` cause is preserved on `JevUnavailableException`; JDK I/O exception messages contain host/path but not headers or body.
