# FEATURE-005: Contract review
Status: CONTRACT_PASS
Outcome: CONTRACT_PASS

## Checklist
- Compatibility: additive. `TriageQuestionSets.v1()` (`triage-jev-v1`) and `JevAssessmentMapper` are new; no existing port, record or
  adapter signature changed. Any change to names, instructions or criteria requires a new version (documented).
- Schemas: question names, types and criteria keys in `docs/contracts.md` 3a match `TriageQuestionSets` (criteria keys equal the lower-case
  taxonomy enum names; `TriageQuestionSetsTest` checks exact coverage). Recording JSONL format is strict (unknown fields rejected,
  duplicate hashes rejected) and documented in the plan and `JevRecording`.
- Consumers: FEATURE-006/007 reuse the question set and mapper; strict failures map to the `MODEL_UNAVAILABLE` path. Harness stays in
  test scope; runtime does not depend on `..evaluation..`.
- Rollout order: merge, consumers rebase; no runtime config or external API change; Jev request shape unchanged.

## Findings
No Blocking or Important findings.
