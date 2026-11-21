# FEATURE-001: Security review
Status: SECURITY_PASS_WITH_NOTES

## Checklist
- Secrets: keys only via `${JEV_API_KEY:}` / `${DEEPSEEK_API_KEY:}`; .env ignored, .env.example placeholders; gitleaks clean.
- Actuator: only health,info; details/components hidden; env/beans verified non-exposed by test.
- Logging: sentinel key not in startup output (test). No request logging.
- Dependencies: Enforcer bans Boot 4 / Spring AI 2.x; versions pinned. Jackson/reactor overridden by Boot-managed newer versions.
- CI: permissions contents: read, no secrets; actions pinned to major tags.
- No auth surface, no PII, no real data.

## Notes (non-blocking)
- S1: Transitive spring-ai-deepseek could autoconfigure later; keyless test guards it. Re-check when a starter is added.
- S2: GitHub actions pinned by major tag, not SHA; consider hardening later.
- S3: SAA 1.1.2.0 has newer security-fix releases (1.1.2.4-security-fix per README); schedule upgrade evaluation.

No Blocking or Important findings.
