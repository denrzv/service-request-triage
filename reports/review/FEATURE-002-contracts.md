# FEATURE-002: Contract review
Status: CONTRACT_PASS
Outcome: CONTRACT_PASS

## Checklist
- Compatibility: purely additive; new domain port/records; no existing contract changed. `docs/contracts.md` gains an implemented-notes paragraph and states the new port sits beneath `ClassificationModelPort`.
- Schemas: request `{model, state:{customerMessage, channel?}, questions}`; Choice criteria object (1..255), Score criteria array (1..10), Noul instructions only; response `answers/model/usage` parsed strictly (types, [0,1] ranges, choice among criteria, score legend/probability keys "0".."n-1"); unknown fields tolerated. Verified against fixtures and mapper/parser tests; fixtures are schema-derived, not live-captured (documented).
- Consumers: FEATURE-005/007 will consume `JevClassificationPort`; exceptions are unchecked and documented with `@throws` on the port.
- Rollout order: merge disabled by default (`triage.jev.enabled=false`); no consumer yet.
- Provider behaviour for 401/403/429/5xx/Retry-After is unverified against the live API; recorded in README, ADR and PRD.

## Findings
No Blocking or Important findings.
Note: the PRD wording on surfacing the 400 `detail` differs from the implementation (see review N1, Minor).
