# English Goldset (synthetic, v1)

Independent, hand-authored, **synthetic English** evaluation dataset for Service Request triage
(FEATURE-004, ADR-009, ADR-010, [ticket ADR](../../docs/adr/FEATURE-004-english-goldset.md)).
It is used to calibrate policy thresholds on the development split and to report results on the held-out split.
It contains **no real customer, bank, card or account data** and must never be treated as evidence of production
performance.

## Files

| File | Purpose |
|---|---|
| `v1/dev.jsonl` | Development / calibration split (165 cases). Thresholds and prompts may be tuned here. |
| `v1/test.jsonl` | **Held-out** test split (75 cases). Read only for final reporting; never for tuning. |
| `v1/manifest.json` | Split recipe (algorithm, seed, fraction), per-file SHA-256, counts per stratum. |

Cite the manifest hashes in every benchmark report; any edit to a split file changes its hash and fails the build
until the dataset is re-frozen.

## Isolation rules (enforced)

- The dataset lives outside `src/` and is **never** a Maven resource, so it is not in the runtime jar.
- The Java reader (`src/test/java/.../evaluation/goldset`) is test-scoped.
- `GoldsetIsolationTest` fails the build if `src/main`, the compiled main output or `src/test/resources` contain the
  dataset path, goldset case IDs, label-schema fields, messages or rationales, or if `pom.xml` packages `samples/`.
- Labels, rationales, strata and case IDs must never be sent to Jev, DeepSeek, prompts or investigator tools.
  A benchmark sends only `message` (and optionally `channel`) to a provider and joins results back by `caseId`.
- `caseId` values are assigned in a shuffled order and do not reveal stratum or labels.

## Record schema (one JSON object per line)

| Field | Type | Meaning |
|---|---|---|
| `caseId` | `GS-NNNN` | Stable, non-informative join key. |
| `message` | string, 10-600 chars | Synthetic customer text; untrusted input; single-line printable ASCII. |
| `channel` | `INTERNET_BANK` \| `CONTACT_CENTER` \| `WEB_FORM` | Intake channel (`docs/contracts.md` section 1). |
| `labelSource` | `SYNTHETIC_AUTHOR` | Provenance; provider outputs are never labels. |
| `taxonomyVersion` | `v1` | Taxonomy the labels refer to (`src/main/resources/taxonomy/v1.json`). |
| `stratum` | enum | Test-design stratum (below); not a prediction target. |
| `intent` | `PrimaryIntent` | Primary semantic intent. |
| `secondaryIntents` | `PrimaryIntent[]` | Further independent intents (multi-intent cases). |
| `domain` | `ProblemDomain` | Problem domain. |
| `requestedOutcome` | `RequestedOutcome` | What the customer asks for. |
| `formalSrType` | `ServiceRequestType` | Expected formal SR type (`UNCLASSIFIED` = no type should be proposed). |
| `reasonCode` | taxonomy reason code | Must belong to `formalSrType`. |
| `recommendedRoute` | `DRAFT_PROPOSAL` \| `HUMAN_REVIEW` \| `ABSTAIN` | Ideal terminal route. |
| `requiresHumanReview` | boolean | Exactly `recommendedRoute != DRAFT_PROPOSAL`. |
| `alternatives` | object of lists | Labels a careful reviewer would also accept, per dimension (`intent`, `domain`, `requestedOutcome`, `formalSrType`, `recommendedRoute`). |
| `paraphraseGroup` | `PG-NN` or `null` | Deliberate paraphrases with identical labels (stability tests). |
| `labelRationale` | string, 10-400 chars | Why the labels were chosen. |

Files are canonical: sorted by `caseId`, fixed key order, compact JSON, ASCII, LF (`.gitattributes`).

## Labeling guideline (synthetic taxonomy v1)

The expected route is what a careful human triager would choose under the synthetic taxonomy; it is defined
independently of policy code and is **not** produced by policy `v1`.

| Situation | formalSrType / reasonCode | recommendedRoute |
|---|---|---|
| Clear grievance or payment dispute (incl. refund of a payment) | `CLAIM` / domain-specific complaint code | `HUMAN_REVIEW` (claims always need approval) |
| Clear malfunction report without complaint framing | `TECHNICAL_REQUEST` | `DRAFT_PROPOSAL` |
| Clear question | `INFORMATION_REQUEST` | `DRAFT_PROPOSAL` |
| Clear service action | `SERVICE_REQUEST` | `DRAFT_PROPOSAL` |
| In-scope but undecidable (vague, contradictory, near-tie, multi-intent) | best formal type, or `UNCLASSIFIED` / `NONE_FIT` when none can be chosen | `HUMAN_REVIEW` |
| Outside the taxonomy (incl. prompt-injection or configuration-extraction attempts) | `UNCLASSIFIED` / `LOW_TAXONOMY_FIT` | `ABSTAIN` |

- Complaint framing dominates a technical symptom (formal type `CLAIM`, `TECHNICAL_REQUEST` listed as an alternative).
- Multi-intent: the primary intent is the one whose formal type carries the highest risk
  (`CLAIM` > `TECHNICAL_REQUEST` > `SERVICE_REQUEST` > `INFORMATION_REQUEST`); the others go to `secondaryIntents`.
- `LLM_INVESTIGATION` is a process step, never a ground-truth route.

## Strata (240 cases)

| Stratum | Cases | What it tests |
|---|---:|---|
| `SIMPLE_COMPLAINT` | 24 | Unambiguous complaints across all domains |
| `SIMPLE_INQUIRY` | 24 | Neutral questions |
| `SIMPLE_SERVICE_ACTION` | 24 | Neutral service actions |
| `SIMPLE_INCIDENT` | 24 | Neutral malfunction reports |
| `DUPLICATE_CHARGE` | 20 | Duplicate charges / refunds that must not be auto-routed |
| `DISSATISFACTION_WITH_TECHNICAL` | 20 | Customer dissatisfaction mixed with a technical symptom |
| `MULTI_INTENT` | 20 | Two or three independent intents |
| `VAGUE` | 16 | Incomplete text without subject or outcome |
| `CONTRADICTORY` | 16 | Self-contradicting statements |
| `OUT_OF_TAXONOMY` | 20 | Requests no formal type fits, incl. prompt injection |
| `NEAR_TIE` | 12 | Two formal types equally plausible |
| `NO_COMPLAINT_CONTROL` | 20 | Complaint/dispute/refund vocabulary without a grievance |

Simple strata carry no alternatives or secondary intents. Run the coverage report for label, route and channel
distributions:

```bash
python3 tools/goldset.py coverage samples/english-goldset/v1
```

## Split

Per stratum, split units (paraphrase group, else case) are ranked by `sha256("<seed>:<unitId>")` and assigned to
test while the stratum's test count is below `floor(0.30 * n + 0.5)` (algorithm `sha256-rank-per-stratum-v1`).
Paraphrases never straddle the split. The same algorithm is implemented in `tools/goldset.py` and
`GoldsetSplitter`; the build recomputes it and compares with the files.

## Changing the dataset

1. Edit or add records (keep IDs stable; new IDs must not reveal stratum).
2. `python3 tools/goldset.py freeze samples/english-goldset/v1/dev.jsonl samples/english-goldset/v1/test.jsonl --out samples/english-goldset/v1`
   (validates every rule, re-assigns the split, rewrites canonical files and the manifest). Adding cases can move
   existing units between splits; never do this after held-out results have been published for `v1` — create
   `v2` instead. Semantic label changes after publication also require `v2` (ADR-011: ground truth is immutable).
3. `python3 tools/goldset.py verify samples/english-goldset/v1` and `./mvnw verify`.

## Limitations (read before citing results)

- **Synthetic and single-author.** All texts and labels were written by one author (an AI assistant under
  maintainer direction). No second independent labeler reviewed them, so inter-annotator agreement is unknown
  and the author's interpretation of the taxonomy is baked in.
- **Easier and cleaner than real traffic.** Messages are short, grammatical, single-line ASCII English without
  typos, slang, attachments, long threads or personal data. Real-world accuracy will likely be lower.
- **Small held-out set.** 75 test cases (4-9 per stratum) give wide confidence intervals; per-stratum metrics are
  indicative only. Report counts with every rate.
- **Ambiguity is labelled, not resolved.** `alternatives` lists acceptable labels for ambiguous, near-tie,
  vague and contradictory cases. Report strict accuracy (primary label) and lenient accuracy (primary or
  alternative) separately; never silently merge them.
- **Multi-label is approximated.** One primary intent plus `secondaryIntents`; there is a single formal type and
  route per case. Whether the second intent produced a second task proposal must be evaluated separately.
- **Taxonomy-bound.** Labels refer to taxonomy `v1`; a taxonomy change requires relabeling and a new dataset
  version.
- **Route is a guideline, not policy.** `recommendedRoute` reflects the synthetic labeling guideline above (for
  example, every `CLAIM` needs review); it is not a bank SLA or legal rule.
- **English only.** No claim is made about any other language.
