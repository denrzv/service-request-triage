# ADR: FEATURE-001 — Keyless startup, env-only secrets and inert providers by default
Status: ACCEPTED
Date: 2026-11-21

## Context
The app must start and be healthy without cloud keys; CI has no credentials. Graph core brings `spring-ai-deepseek` transitively; Spring AI model autoconfiguration may demand an API key if a starter is on the classpath. Secrets must come only from environment variables. Jev and DeepSeek adapters arrive in later tickets (FEATURE-002, FEATURE-008).

## Decision
- No Spring AI model starter is added now; provider autoconfiguration is not activated. Later adapters are enabled by explicit properties (for example `triage.jev.enabled`, `triage.deepseek.enabled`, default `false`) and read keys from `JEV_API_KEY` / `DEEPSEEK_API_KEY` environment variables mapped in `application.yml` with empty defaults (`${JEV_API_KEY:}`), never real values.
- `.env.example` holds placeholders only; `.env` stays git-ignored.
- Actuator exposes only `health` (with liveness/readiness probes) and `info`; health details are not shown.
- Structured logging uses Boot's built-in console format (`logging.structured.format.console=logstash`), with no request body or secret logging; a test asserts no key value is logged on startup.
- A context test runs with empty provider environment variables and must pass.
- `triageId` and `graphRunId` are reserved MDC correlation keys; the logstash format emits MDC entries as top-level JSON fields. Later tickets populate them.

## Alternatives considered
### Option A: add the DeepSeek starter now with a dummy key
- Pros: earlier wiring.
- Cons: dummy keys risk accidental calls; contradicts opt-in and keyless startup.

### Option B: profile-based config (`live` profile)
- Pros: familiar.
- Cons: easy to enable by accident; explicit per-provider flags are narrower. Profiles can be layered on later.

## Consequences
- Positive: safe default, CI needs no secrets, small attack surface.
- Negative: later tickets must add their own enablement wiring.
- Risks: autoconfiguration from the transitive `spring-ai-deepseek` could still activate.
- Mitigations: keyless context test; add an autoconfiguration exclusion if observed; `.gitignore`; security-check script.

## Rollout / rollback
- Rollout: part of the bootstrap PR.
- Rollback: revert the PR.

## Links
- PRD: docs/prd/FEATURE-001.prd.md
- Plan: docs/plan/FEATURE-001.md
- Tasklist: docs/tasklist/FEATURE-001.md
