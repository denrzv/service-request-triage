# Evaluation Strategy — Classification, Abstention, Routing and Cost

**Purpose:** demonstrate whether Jev-only, DeepSeek-only or the hybrid conditional graph offers the best trade-off for synthetic English Service Request triage. A successful demo is not evidence of production suitability.

## 1. Synthetic dataset

Initial target: **200–300 English messages** with independent ground-truth labels. Separate into development/calibration and **held-out test** subsets (for example 70/30); do not allow agents or policy tuning to read held-out labels.

Recommended strata:
- Simple single-intent requests (complaint, inquiry, service action, incident report).
- Payment disputes/duplicate charges/refund requests.
- Digital channel authentication and technical incident complaints.
- Customer support dissatisfaction mixed with a technical symptom.
- Multi-intent messages that plausibly require multiple task proposals.
- Out-of-taxonomy and 'none fit' cases.
- Incomplete/vague/contradictory text.
- Ambiguous near-tie alternatives.
- Controls with no complaint language.
- Multiple messages equivalent in meaning but paraphrased to test stability.

Every record carries `caseId`, `message`, `channel`, `labelSource`, `taxonomyVersion`, `intent`, `domain`, `requestedOutcome`, `formalSrType`, `recommendedRoute`, `requiresHumanReview`, plus rationale/allowed alternate labels.

**Goldset is not an agent tool resource**. Use separate folders/explicit test fixture access control.

> **Implemented in FEATURE-004:** `samples/english-goldset/v1/` (240 cases, dev 165 / held-out test 75, seeded
> hash-rank split with SHA-256 manifest). Records carry the fields above plus `stratum`, `reasonCode`,
> `secondaryIntents` and `paraphraseGroup`; "allowed alternate labels" are the `alternatives` object. The dataset
> is outside `src/`, read only by test-scoped code, and `GoldsetIsolationTest` enforces that labels never reach the
> runtime artifact. Schema, labeling guideline and limitations: `samples/english-goldset/README.md`; decisions:
> `docs/adr/FEATURE-004-english-goldset.md`.

## 2. Baselines

| Mode | What executes | Main reason to include |
|---|---|---|
| RULES_ONLY | Small deterministic keyword/rule baseline | Tests value beyond simple rules |
| JEV_ONLY | Jev semantic questions + deterministic Policy Engine | Cost/quality of decision model |
| LLM_ONLY | DeepSeek direct structured classification + same policy | LLM comparison under matched taxonomy |
| HYBRID | Jev + confidence gate + conditional DeepSeek investigation + policy | Does selective escalation improve risk/cost? |

All modes must use **same taxonomy, held-out labels, policy invariants and input texts**. Model versions, question/prompt versions, graph version and retries must be captured.

> **Implemented in FEATURE-005 (RULES_ONLY and JEV_ONLY):** test-scope harness `evaluation.benchmark`, question set
> `triage-jev-v1`, policy `v1` with the investigation budget treated as used up (no LLM fallback). Committed run with
> offline replay and byte-for-byte reproducibility test: `reports/evaluation/feature-005-jev-baseline/`. Decisions:
> `docs/adr/FEATURE-005-jev-baseline-benchmark.md`. Findings of that run (synthetic data, indicative only):
> - Held-out formal-type macro-F1: JEV_ONLY 0.872 vs RULES_ONLY 0.511 (rules 0.795 on the development split they were
>   written from, so they overfit); complaint recall 0.786 vs 0.464; out-of-taxonomy cases are proposed as
>   `UNCLASSIFIED` by both (via the none-fit rule path).
> - Wrong auto-route rate is high for both (held-out 0.300 vs 0.650 at 0.533 coverage). Most JEV_ONLY wrong
>   auto-routes are multi-intent, contradictory, vague and near-tie messages with the right formal type whose gold
>   route is review; Choice confidence and margin do not flag them reliably.
> - Companion Noul separates fit well (mean `taxonomy_fit` 0.94 in-taxonomy vs 0.07 to 0.11 out-of-taxonomy) and
>   `multi_intent` separates gold multi-intent cases (0.86 to 0.91 vs about 0.10), yet no single threshold reaches the
>   illustrative 0.10 wrong-auto-route target on the calibration split; raising `taxonomyFitMin` alone lowers
>   complaint recall. Joint/risk-specific thresholds (FEATURE-007) or investigation (FEATURE-009) are needed.
> - Cost: about 800 input tokens per call with seven questions, p50 latency 136 ms from this environment.
> - Metric definitions used: wrong auto-route = `DRAFT_PROPOSAL` with wrong formal type or a gold route other than
>   `DRAFT_PROPOSAL`; OOT success = `OUT_OF_TAXONOMY` stratum proposed as `UNCLASSIFIED`; macro averages skip classes
>   with neither support nor predictions; undefined ratios are reported as `n/a`.

## 3. Primary quality metrics

- **Macro-F1** for formal SR type and separately for intent/domain; include per-class confusion matrices.
- **Complaint Recall** and **false negative complaint rate** (critical business signal).
- **Wrong Auto-Route Rate** = wrong decisions among those automatically proposed / applied; count harmful classes separately.
- **Selective Accuracy vs Coverage**: correctness among auto-proposed cases vs fraction that can be auto-proposed.
- **Abstention/Review Rate** and **escalation to DeepSeek rate**.
- **Multi-intent handling quality**: whether second independent intent triggered appropriate human review/task proposal.
- **Out-of-taxonomy rejection recall** using companion Noul.
- **Calibration**: confidence bins, reliability plots, Brier score/ECE where meaningful. Do not assume provider 'confidence' is calibrated.
- **Latency** p50/p95, number of API calls, input/output tokens, **cost per 1,000 requests**.
- **Graph correctness**: route trace, bounded fallback, no unexpected loops.
- **Human correction rate** and reconciliation of draft tasks after correction.
- **Recovery success**: graph interruption + restored progress without duplicate mock draft actions.

## 4. Cost-sensitive routing

Use an illustrative cost matrix (synthetic policy, *not* bank SLA):

| Misclassification | Relative concern |
|---|---|
| Real CLAIM silently treated as INFORMATION_REQUEST | High |
| Duplicate-charge dispute auto-routed without review | High |
| Technical incident sent for manual review | Moderate operational cost |
| Straightforward inquiry escalated to LLM | Low but measurable latency/cost |
| Out-of-taxonomy case forced into known category | High |

Define risk-weighted thresholds **from calibration set**, test on held-out cases. Model's raw top-class probability alone must not be considered proof of accuracy or business appropriateness.

## 5. Threshold experiments

Compare routing based on:
- Choice top probability alone;
- Choice confidence alone;
- Top-two probability margin;
- Companion Noul for taxonomy fit;
- Risk/category-specific policy;
- Optional Jev hierarchical taxonomy (flat vs two-stage).

Plot **coverage vs incorrect auto-route rate** at multiple thresholds and report how many cases require human review. A policy threshold is a tunable versioned configuration, not a magic constant.

## 6. Graph and fallback experiments

1. Jev unambiguously identifies a simple inquiry → Graph skips DeepSeek.
2. Jev identifies a formal complaint with high confidence, but policy still requires human review.
3. Multi-intent case → fallback Agent retrieves synthetic policy, resolves or abstains.
4. Companion Noul rejects all candidate categories → abstain.
5. Jev API 429/timeout → safe failure/review, not default forced classification.
6. DeepSeek responds with malformed JSON/contradictory evidence → safe review path.
7. LLM attempts to execute a write tool → tool not present, no actual side effect.
8. Graph budget exhausted → `PARTIAL` with trace and unresolved questions.
9. Human approval/correction after checkpoint resume → exactly one logical mock draft proposal (idempotency).
10. Repeated same `triageId` → no duplicate mock Task or SR draft.

## 7. Evaluation stages

- **Stage 1:** label dataset and freeze held-out split. Validate label consistency (a second reviewer for ambiguous cases where possible).
- **Stage 2:** Jev-only results, calibration and threshold search without Graph.
- **Stage 3:** LLM-only direct structured baseline.
- **Stage 4:** Hybrid Graph with fixed threshold version, repeated sampling where needed.
- **Stage 5:** crash/restart Human Review checkpoint tests and branch trace.
- **Stage 6:** publish findings and ADR follow-up even if hybrid is worse.

## 8. Output artifacts

`reports/evaluation/<runId>/metrics.json`, `report.md`, confusion matrices (CSV), sample error cases (synthetic), trace references and exact model/taxonomy/prompt/policy versions. Report includes limitations: fake data, no real bank routing, no Russian performance claim, provider cost estimates can differ from invoices.

## Exit criteria

Benchmarks can run offline with mocked provider responses; optional live runs require explicit API key. Held-out labels are never leaked to LLM tools; negative-path tests pass; results are reproducible from dataset hash and versioned policy.
