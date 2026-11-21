# FEATURE-004: English Goldset
Status: PRD_READY

Issue: https://github.com/denrzv/service-request-triage/issues/4 (depends on FEATURE-001, merged).
Branch: `claude/clever-mccarthy-i2uiet` (session-assigned branch; one ticket per branch).

## Context / Problem
FEATURE-005 (Jev baseline), FEATURE-007 (threshold calibration) and
FEATURE-013 (comparative evaluation) need an independent, labeled, synthetic
English dataset (ADR-009, `docs/evaluation.md` section 1). The v1 policy from
FEATURE-003 ships uncalibrated thresholds that can only be derived from such a
dataset. No dataset, label schema, frozen split or leakage guard exists yet,
and nothing currently prevents labels from reaching runtime prompts or future
investigator tools (ADR-006, ADR-010, `docs/contracts.md` section 6).

## Goals
- 200–300 hand-authored synthetic English customer requests with ground-truth
  labels expressed in the FEATURE-003 `v1` taxonomy vocabulary.
- A strict, versioned label schema; every record carries `caseId`, `message`,
  `channel`, `labelSource`, `taxonomyVersion`, expected intent, domain,
  requested outcome, formal SR type, reason code, recommended route,
  `requiresHumanReview`, allowed alternative labels and a label rationale.
- Balanced tricky strata alongside simple single-intent controls.
- A frozen development/calibration vs held-out test split, reproducible from a
  stable seed, with SHA-256 hashes recorded in a manifest.
- Tests that fail the build if goldset files or labels become reachable from
  the runtime artifact (classpath, prompts, future agent tools).
- A dataset tool for schema validation, duplicate detection, coverage report
  and split freezing; the same rules enforced by the Maven build.
- A dataset README covering label semantics and limitations of synthetic,
  single-author labeling, including ambiguous and multi-label cases.

## Non-goals
- Running any provider or computing metrics (FEATURE-005/013).
- Calibrating policy thresholds (FEATURE-005/007).
- Using provider outputs (Jev, DeepSeek) as ground truth.
- REST input contracts (`IncomingCustomerRequest`, FEATURE-012).
- Russian or any non-English text; real customer, bank, card or account data.

## Users & Roles
- Benchmark developers (FEATURE-005/013) who load development and held-out cases.
- Policy maintainers who calibrate thresholds on the development split only.
- Reviewers who audit label rationale and dataset coverage.

## User Stories
- As an evaluator, I want a frozen held-out split identified by hashes so that
  reported metrics are reproducible and tuning cannot silently touch test labels.
- As a policy maintainer, I want tricky strata (multi-intent, vague,
  contradictory, out-of-taxonomy) so that abstention and review paths are measured.
- As a security reviewer, I want automated proof that labels are not packaged
  into the runtime artifact or agent-accessible resources.

## Main Scenarios
1. A benchmark loads `dev.jsonl` for calibration and `test.jsonl` only for the
   final report; both parse into typed records using the domain enums.
2. A maintainer adds or corrects cases, runs the dataset tool to validate,
   report coverage and re-freeze the split, and the manifest hashes change.
3. `./mvnw verify` re-validates the schema, invariants, duplicates, hashes,
   split assignment and isolation on every build.

## Edge Scenarios
1. Unknown enum value, unknown JSON property, missing field, blank message or
   rationale: validation fails naming the case and field.
2. Reason code not belonging to the formal SR type; `DRAFT_PROPOSAL` with
   `UNCLASSIFIED`; `ABSTAIN` with a formal type; `requiresHumanReview`
   inconsistent with the route; `LLM_INVESTIGATION` as an expected route: fail.
3. Duplicate `caseId`, identical normalized message, or near-duplicate text
   outside a declared paraphrase group: fail.
4. Paraphrase group split across dev/test or with inconsistent labels: fail
   (prevents near-identical leakage from dev into test).
5. File edited after freezing (hash mismatch) or split assignment not
   reproducible from the seed: fail.
6. Non-ASCII text, e-mail addresses or long digit runs (card/account-like
   numbers): fail as a synthetic-data guard.
7. Goldset path, case IDs, rationale or messages found in `src/main`,
   `target/classes` or test resources: fail.

## Acceptance Criteria
(Mirrors issue #4.)
- AC1: At least 200 synthetic English customer requests (target 200–300), no real customer data.
- AC2: Every record includes `caseId`, `message`, `channel`, expected
  intent/domain/outcome/formal type/route, `taxonomyVersion` and label rationale.
- AC3: Balanced tricky strata: multi-intent, vague, contradictory,
  out-of-taxonomy, duplicate charge, customer dissatisfaction, no-complaint controls.
- AC4: Frozen development/calibration vs held-out test split with stable seed and hashes.
- AC5: No goldset labels accessible via agent tool/retrieval APIs; enforced in tests.
- AC6: Label schema validation, duplicate detection and dataset coverage script.
- AC7: README explains limitations of synthetic labeling and ambiguous/multi-label cases.

## Non-functional requirements
- Security: no secrets; labels never on the runtime classpath; message text is
  untrusted input and is never executed or interpreted.
- Privacy/data protection: synthetic English only; automated PII-shape guard.
- Reliability: deterministic validation and split; LF line endings pinned so
  hashes are platform-independent.
- Performance/scale: validation of ~300 records with pairwise near-duplicate
  check completes in well under a second.
- Observability: coverage report (counts per stratum, split and label).
- Compatibility: Java 25, Boot 3.5.x, no new dependencies; Python tool uses
  only the standard library.

## Data considerations
- Identifiers: `caseId` (`GS-0001`…), `paraphraseGroup` (`PG-01`…), dataset version `v1`.
- Sensitive data: none; synthetic only.
- Retention/lifecycle: files are immutable once frozen; corrections create a new
  manifest (and, if labels change semantics, a new dataset version).

## Constraints & Assumptions
- Labels use taxonomy `v1` (FEATURE-003); `channel` uses the contracts
  `Channel` enum (`INTERNET_BANK`, `CONTACT_CENTER`, `WEB_FORM`).
- The expected route is the ideal terminal route a careful human triager would
  choose under the synthetic taxonomy (`DRAFT_PROPOSAL`, `HUMAN_REVIEW`,
  `ABSTAIN`); it is a labeling guideline, not output of policy `v1`.
- Labels are authored by one author (an AI assistant under maintainer
  direction); no second independent reviewer is available — documented limitation.
- 70/30 development/held-out split, per stratum, as suggested in `docs/evaluation.md`.

## Risks
- Single-author synthetic labels may encode the author's bias and be easier
  than real traffic; mitigated by rationale, alternatives and README limits.
- Coupling labels to taxonomy `v1`: a taxonomy change requires relabeling and a
  new dataset version.
- Future agent tools could read files from disk; mitigated by source-scan
  guards that apply automatically to all future `src/main` code.

## Open Questions
- None blocking.
