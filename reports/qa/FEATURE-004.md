# FEATURE-004: QA
Status: QA_PASSED
Outcome: QA_PASSED

Evidence: `aidd_verification.py --ticket FEATURE-004 check` current PASS; surefire totals 214 tests, 0 failures, 0 errors, 1 skipped (opt-in live smoke). Python: 17 tool tests OK; `tools/goldset.py verify` OK. Live provider calls: none, not applicable.

## Acceptance mapping
- AC1 >= 200 synthetic English cases: 240 (dev 165 / test 75); `GoldsetContractTest.sizeAndStrataAreBalanced`. Pass.
- AC2 required fields: strict `GoldCase` schema (`GoldsetLoaderTest`, missing/unknown property rows). Pass.
- AC3 tricky strata: 12 strata incl. multi-intent, vague, contradictory, out-of-taxonomy, duplicate charge, dissatisfaction-with-technical, no-complaint control; each >= 12 and 20-40 % held-out. Pass.
- AC4 frozen split with seed and hashes: manifest hashes, recomputed split (`splitIsReproducibleFromFrozenRecipe`), `GoldsetSplitterTest`, LF pinned by `.gitattributes`. Pass.
- AC5 labels not reachable by tools/retrieval: `GoldsetIsolationTest` (src/main scan, compiled output, classpath lookup, test resources, pom, ArchUnit, planted leaks). Pass.
- AC6 validation, duplicates, coverage tool: `GoldsetValidatorTest`, `tools/test_goldset.py`, `coverage` run manually. Pass.
- AC7 README limitations: single-author, easier-than-real, small held-out, ambiguity and multi-label sections present. Pass.

## Positive / negative / edge
- Positive: shipped v1 loads typed; every formal type and terminal route present in both splits; ambiguity and multi-intent cases in both splits.
- Negative: 29 invalid-record rows (bad enum, reason code vs type, route/review mismatch, `LLM_INVESTIGATION` route, non-ASCII, e-mail, digit runs, blank text); duplicates, near-duplicates, split-straddling and inconsistent paraphrase groups rejected; hash mismatch and CR/missing newline rejected.
- Edge: rounding of per-stratum test count, paraphrase-unit integrity, canonical ordering, caseId not revealing stratum.
- Regression: `ArchitectureBoundaryTest`, `StartupHealthTest` and all FEATURE-001..003 suites green.
