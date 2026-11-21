# CI-001: Reliability review
Status: RELIABILITY_PASS

## Checklist
- Timeouts: build 20 min, gitleaks, aidd-validation and dependency-review 10 min, CodeQL 30 min. Plan states 20 min per job; the other values are reasonable and non-blocking.
- Retries: none by design; failures fail the check, no `continue-on-error` anywhere.
- Duplicates/ordering: jobs independent and parallel; `concurrency` per workflow and ref cancels superseded runs. Group includes workflow name, so ci and codeql do not cancel each other.
- Recovery: re-run on demand; rollback is revert of workflow files.
- Observability: standard check status and job logs.

## Findings
No Blocking or Important findings.

## Notes (non-blocking)
- Cancel-in-progress on `main` pushes may cancel an earlier main run when two merges land quickly; acceptable for checks-only workflows.
- Stale `docs/.active_ticket` is a deterministic failure mode of `aidd-validation` (documented).
