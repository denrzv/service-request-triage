# FEATURE-004: Implementation plan
Status: PLAN_APPROVED

## Overview
Add a hand-authored, synthetic English goldset (`samples/english-goldset/v1/`)
of 240 labeled cases across 12 strata, a frozen 70/30 hash-rank split with a
manifest, a stdlib Python dataset tool, test-scope Java loader/validators that
the Maven build runs, and isolation guards proving labels never reach the
runtime artifact. Decisions in `docs/adr/FEATURE-004-english-goldset.md`.

## Assumptions
- Maven runs use `JAVA_HOME=/usr/lib/jvm/temurin-25` in this environment.
- Labels follow taxonomy `v1`; gold route guideline: `CLAIM` → `HUMAN_REVIEW`;
  clear technical/information/service cases → `DRAFT_PROPOSAL`; in-taxonomy but
  undecidable (vague, contradictory, multi-intent, near-tie) → `HUMAN_REVIEW`;
  out-of-taxonomy → `UNCLASSIFIED`/`LOW_TAXONOMY_FIT`/`ABSTAIN`.
- Single author; no second reviewer (documented).
- The dataset tool lives in `tools/`, not `scripts/`: `scripts/` is reserved for the human-maintained AIDD
  enforcement layer and is write-protected for agents (`scripts/aidd_common.py`).

## Architecture / Flow
```
author cases -> tools/goldset.py freeze <inputs> --out samples/english-goldset/v1
             -> dev.jsonl + test.jsonl + manifest.json (seed, algorithm, sha256, counts)
tools/goldset.py validate|coverage|verify samples/english-goldset/v1   (author/CI)
./mvnw verify -> GoldsetContractTest   (typed parse, invariants, duplicates, hashes, split recomputation, balance)
              -> GoldsetIsolationTest  (src/main, target/classes, test resources, pom.xml)
```
Components:
- `domain.model.Channel` enum (contracts section 1).
- Test scope `io.github.denrzv.triage.evaluation.goldset`: `GoldCase`,
  `GoldAlternatives`, `Stratum`, `GoldSplit`, `GoldsetManifest`,
  `GoldsetLoader` (strict Jackson parse + invariant validation),
  `GoldsetValidator` (cross-record rules: duplicates, paraphrase groups, balance),
  `GoldsetSplitter` (hash-rank assignment), tests.
- `tools/goldset.py`, `tools/test_goldset.py`; CI step.
- `.gitattributes` pins LF for `samples/english-goldset/**`.

## Diagram impact
Render diagrams required: no

| Diagram | Impact | Reason | Expected source |
|---|---|---|---|
| System Context C4 | not changed | No new actor or external system | `diagrams/c4/system-context.puml` |
| Container C4 | not changed | Dataset is a repository artifact, not a deployable or store | `diagrams/c4/container.puml` |
| ERD | not applicable | No persistence | `diagrams/erd/erd.puml` |

## Project invariants
- Graph / policy boundary (StateGraph node, Policy Engine authority): unchanged; no graph or policy code.
- Provider usage (Jev / DeepSeek, ports, budgets, opt-in live tests): none; providers never label data.
- Abstention / human-review paths covered: dataset contains `ABSTAIN` and `HUMAN_REVIEW` ground truth in dedicated strata; validators enforce route/type consistency.
- Synthetic-data and goldset-isolation impact: core of this ticket; goldset outside the classpath, test-scope reader, build-enforced isolation guards, PII-shape guard.

## Contracts
### Inbound contracts
- Endpoint/message/UI event: none.
- Request/input: goldset JSONL record schema v1 (documented in `samples/english-goldset/README.md` and `docs/contracts.md`); `manifest.json` schema.
- Response/output: typed `GoldCase` list per split (test scope).
- Error behavior: `GoldsetValidationException` (test scope) naming file, line/case and field; Python tool exits non-zero with one line per violation.

### Outbound contracts
- Integration: none.
- Timeout: not applicable.
- Retry: not applicable.
- Idempotency: `freeze` is idempotent over frozen files.

### Shared contract changes
- New `domain.model.Channel` enum exactly as in `docs/contracts.md` section 1 (additive).
- `docs/contracts.md` gains a "Goldset record (FEATURE-004)" section; `docs/evaluation.md` section 1 gets an implemented note (record fields as listed there plus `stratum`, `reasonCode`, `secondaryIntents`, `paraphraseGroup`).

## Data changes
- Schema changes: new versioned dataset files; no database.
- Migration/backfill: none.
- Indexes/partitions: none.
- Rollback/forward fix: revert PR; label corrections re-freeze; semantic changes → `v2`.

## Reliability
- Timeout strategy: not applicable.
- Retry/backoff strategy: not applicable.
- Duplicate handling: duplicate IDs, normalized-message duplicates and near-duplicates (token Jaccard >= 0.8 outside a paraphrase group) fail validation.
- Ordering assumptions: files sorted by `caseId`; hashes over exact bytes.
- Partial failure behavior: validation reports all violations, then fails.

## Security & Privacy
- AuthN/AuthZ: not applicable.
- Input validation: strict schema, ASCII-only text, length limits, no e-mail addresses or 6+ digit runs.
- Sensitive data/logging: synthetic only; tests never print full messages beyond case IDs in failures.
- Audit trail: manifest hashes + Git history; `labelSource` provenance.

## Observability
- Logs: none at runtime.
- Metrics: coverage report (per stratum/split/label counts, alternatives, multi-intent, paraphrase groups).
- Traces/correlation: `caseId` is the join key for FEATURE-005/013 reports.
- Alerts/runbook: dataset README "Changing the dataset" section.

## Testing strategy
- Unit tests: `GoldsetLoaderTest` (valid record, each invariant failure, unknown property/enum), `GoldsetSplitterTest` (determinism, group integrity, rounding), `GoldsetValidatorTest` (duplicate/near-duplicate/paraphrase rules) using `@TempDir`/inline records.
- Contract tests: `GoldsetContractTest` over shipped `v1` (counts 200–300, all strata ≥ 12, test fraction per stratum within 20–40 %, taxonomy cross-check, manifest hashes, recomputed split equals files).
- Isolation tests: `GoldsetIsolationTest`.
- Python: `tools/test_goldset.py` (unittest) for the tool; `python3 tools/goldset.py verify samples/english-goldset/v1` in CI.
- Regression tests: existing suite incl. `ArchitectureBoundaryTest`, `StartupHealthTest`.
- Manual checks: `python3 tools/goldset.py coverage samples/english-goldset/v1`.

## Quality gate impact
Gate policy version: 1

Review: required
QA: required
Security review: required
Docs update: required
Contract review: required
DB review: not-required
Reliability review: not-required

### Rationale
- Review: Always required.
- QA: Always required; focus on label invariants, split reproducibility and failure paths.
- Security review: Goldset isolation from prompts/tools is a data-leakage boundary and the PII-shape guard is a privacy control.
- Docs update: Dataset README, root README, evaluation/contracts notes, ADR.
- Contract review: New `Channel` domain enum and the goldset record schema consumed by FEATURE-005/007/013.
- DB review: No database or migrations.
- Reliability review: No runtime behaviour, I/O path, retry or timeout changes.

## Rollout plan
- Deployment order: merge PR; FEATURE-005 consumes the loader.
- Feature flags/config: none.
- Backward compatibility: additive.
- Rollback: revert the PR.

## ADRs
- `docs/adr/FEATURE-004-english-goldset.md`
- Baseline: ADR-009, ADR-010, ADR-011.

## Open Questions
- None blocking.
