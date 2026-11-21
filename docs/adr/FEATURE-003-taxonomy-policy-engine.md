# ADR: FEATURE-003 — Versioned JSON taxonomy/policy, typed decision contracts and a pure rule-table Policy Engine
Status: ACCEPTED
Date: 2026-11-21

## Context
ADR-003 requires `SemanticAssessment`, `ClassificationProposal` and
`RoutingDecision` to stay separate, and a deterministic, versioned Java Policy
Engine to own formal SR type and route. ADR-004 makes `ABSTAIN` and
`HUMAN_REVIEW` first-class and forbids treating uncalibrated thresholds as
truth. `docs/contracts.md` sketches the records with `String`-typed Choice
selections and rationale codes, and an illustrative policy order. Calibration
data does not exist yet (FEATURE-004/005), and policies must change without
source edits. FEATURE-005/007 will build on these contracts.

## Decision
1. **Definitions as versioned JSON resources.** `taxonomy/v1.json` and
   `policy/v1.json` on the classpath, located through configuration
   (`triage.definitions.taxonomy-locations`, `policy-locations`,
   `active-policy-version`) resolved by Spring `ResourceLoader`, so `file:`
   locations and new versions need no code change. Loaded once at startup,
   validated, immutable; any invalid or duplicate definition fails startup.
   Ports `TaxonomyPort` and `ClassificationPolicyPort` (domain) serve them by
   version; an unknown version raises `DefinitionNotFoundException`.
2. **Typed contracts (refinement of `docs/contracts.md`).**
   `ChoiceJudgment<E extends Enum<E>>` replaces the `String`-typed selection so
   invalid categories are rejected when constructing a `SemanticAssessment`;
   `RationaleCode` is an enum because rationale codes are a closed set emitted
   by engine code. All other fields keep the contracts names.
   `ClassificationProposal` additionally requires `taxonomyVersion`;
   `RoutingDecision` requires `policyVersion` and gains `policyCalibrated`.
3. **Ordered rule table.** A rule matches on optional sets of intent, domain,
   outcome and on Noul signals (`PRESENT`/`ABSENT`/`ANY`, using the policy's
   `signalMin`). The first matching rule determines type and reason code; the
   types of the other matching rules become `alternatives`.
4. **Engine order** (pure function of assessment, taxonomy, policy and the
   number of investigations already performed):
   no assessment -> `HUMAN_REVIEW`/`MODEL_UNAVAILABLE`; taxonomy fit below
   minimum -> configured `ABSTAIN|HUMAN_REVIEW`; no rule -> configured
   `ABSTAIN|HUMAN_REVIEW` (none-fit); multi-intent above maximum, then low
   confidence or near-tie on the dimensions the matched rule constrains ->
   `LLM_INVESTIGATION` while `investigationAttempts < maxInvestigations`, else
   `HUMAN_REVIEW`; type requiring approval -> `HUMAN_REVIEW`; otherwise
   `DRAFT_PROPOSAL`. `UNCLASSIFIED` is used only for abstain-type outcomes.
5. **No-op v1 thresholds.** v1 ships `calibrated: false` with minima `0.0` and
   multi-intent maximum `1.0`, so only categorical rules and the synthetic
   approval rule (`CLAIM` requires human review) act. Every decision of an
   uncalibrated policy carries `THRESHOLDS_UNCALIBRATED`. Branches are tested
   with a test-only policy file.
6. **Placement.** Records and ports in `domain`, engine in
   `application.policy`, JSON loading and Spring wiring in
   `infrastructure.taxonomy`. No new dependency.

## Alternatives considered
### Option A: Hard-coded Java rules (switch on enums)
- Pros: simplest, compile-time checked.
- Cons: violates "change policy without modifying source"; versions become code branches.

### Option B: Rule engine library (Drools, Easy Rules) or a scripting DSL
- Pros: expressive.
- Cons: new dependency and supply-chain surface, less transparent, executable configuration.

### Option C: Keep `String` selections and rationale codes exactly as in contracts.md
- Pros: literal match with the draft.
- Cons: invalid categories detected late; free-text codes drift. Mapping from Jev strings to enums belongs in the FEATURE-005/007 mapper anyway.

### Option D: Spring `application.yml` properties for rules
- Pros: native binding.
- Cons: nested rule tables in YAML properties are awkward to version and validate as one unit; policy and taxonomy belong together as immutable documents.

## Consequences
- Positive: auditable decisions (versions, rule IDs, rationale codes), deterministic tests, swappable calibrated versions.
- Negative: two refinements of contracts.md that downstream tickets must follow; JSON schema owned in-repo.
- Risks: uncalibrated v1 auto-drafts on categorical rules alone; synthetic rules mistaken for real policy.
- Mitigations: `calibrated` flag and rationale on every decision; synthetic disclaimers in definition files and README; contracts.md updated.

## Rollout / rollback
- Rollout: additive; definitions load at startup with no external dependency.
- Rollback: revert the PR; no downstream consumer exists yet.

## Links
- PRD: docs/prd/FEATURE-003.prd.md
- Plan: docs/plan/FEATURE-003.md
- Tasklist: docs/tasklist/FEATURE-003.md
- Baseline: ADR-003, ADR-004, ADR-010
