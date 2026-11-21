# ADR: FEATURE-004 — Goldset outside the runtime classpath, JSONL schema, hash-rank frozen split
Status: ACCEPTED
Date: 2026-11-21

## Context
ADR-009 requires an independent synthetic English goldset before graph/LLM
work; ADR-010 requires English synthetic data; ADR-011 keeps ground truth
immutable and separate from human corrections; `docs/contracts.md` section 6
requires investigator tools to exclude the goldset. FEATURE-005/007/013 will
calibrate on a development split and report on a held-out split. Anything
under `src/main/resources` is packaged in the runtime jar and can be read by
future agent tools. The split must be reproducible by independent
implementations (Python dataset tool, Java build check).

## Decision
1. **Location and isolation.** Dataset files live in
   `samples/english-goldset/v1/` (`dev.jsonl`, `test.jsonl`, `manifest.json`),
   outside `src/`, never a Maven resource. The Java loader and checks live in
   **test scope** (`src/test/java/.../evaluation/goldset`), so the runtime
   artifact cannot contain the goldset or its reader. `GoldsetIsolationTest`
   fails the build when `src/main`, the compiled main output or test resources
   reference the goldset path, case IDs, label fields, rationales or messages,
   or when `pom.xml` adds `samples/` as a resource. These source-scan guards
   apply automatically to future tool code (FEATURE-008). FEATURE-005 may
   promote the loader to a separate evaluation profile/module through its own
   ADR, never into the runtime artifact.
2. **Schema.** One JSON object per line, strict (unknown properties and enum
   names rejected), labels typed by taxonomy `v1` enums and the contracts
   `Channel` enum (added to `domain.model`). Expected route is a *terminal*
   route (`DRAFT_PROPOSAL`, `HUMAN_REVIEW`, `ABSTAIN`); `LLM_INVESTIGATION` is
   a process step, not ground truth. `requiresHumanReview` equals
   `recommendedRoute != DRAFT_PROPOSAL`. Allowed alternatives per label
   dimension capture legitimate ambiguity; `secondaryIntents` captures
   multi-intent cases; `paraphraseGroup` marks deliberate paraphrases.
3. **Frozen split.** Per stratum, split units (paraphrase group, else case) are
   ranked by `sha256("<seed>:<unitId>")`; units are assigned to test while the
   stratum's test count is below `floor(0.30 * stratumSize + 0.5)`. Seed,
   algorithm name, fraction and per-file SHA-256 are recorded in
   `manifest.json`. Files are canonical (sorted by `caseId`, fixed key order,
   ASCII, LF pinned by `.gitattributes`), so re-freezing is idempotent and
   hashes are platform-independent.
4. **Two independent checkers.** `tools/goldset.py` (stdlib) is the authoring
   tool (`validate`, `coverage`, `freeze`, `verify`); the Maven build
   re-implements validation, duplicate detection, hash and split checks in Java
   against the real domain enums and `v1` taxonomy. CI runs both.
5. **Label provenance.** `labelSource` is `SYNTHETIC_AUTHOR`; provider outputs
   are never used as labels.

## Alternatives considered
- `src/test/resources` or `src/main/resources`: main resources ship in the
  runtime jar (rejected); test resources mix the goldset with fake-provider
  fixtures that future tool tests may index (rejected).
- Separate input and label files: stronger physical separation but doubles
  join/consistency logic; one record per case with test-scope loading and
  isolation guards is sufficient for a PoC.
- RNG-based shuffled split: not reproducible across languages (rejected).
- Python-only or Java-only validation: Python alone is not enforced against
  the real Java enums; Java alone gives authors no lightweight coverage tool.

## Consequences
- Positive: reproducible frozen split, build-enforced isolation and schema,
  labels expressed in the same vocabulary the Policy Engine consumes.
- Negative: validation rules exist in two languages and must change together
  (both are covered by tests); labels are tied to taxonomy `v1`.
- Risks: single-author labels; a taxonomy change requires a new dataset version.

## Rollout / Rollback
- Additive; no runtime behaviour change. Revert the PR to roll back.
- Any label or text change re-freezes the manifest; semantic label changes
  create `v2` rather than mutating `v1` once results have been published.
