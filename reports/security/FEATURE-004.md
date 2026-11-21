# FEATURE-004: Security review
Status: SECURITY_PASS
Outcome: SECURITY_PASS

## Checklist
- Trust boundary (goldset isolation): dataset outside `src/`, never a Maven resource (pom scan), not on classpath (`GoldsetIsolationTest.goldsetIsNotOnTheClasspath`), compiled output scanned, `src/main` scanned for path, schema markers, case IDs and message/rationale text (applies automatically to future FEATURE-008 tools), runtime-to-evaluation ArchUnit rule. Scanner proven by planted leaks.
- Prompts/tools: no prompt, provider or tool code reads labels; README instructs sending only `message`/`channel`; case IDs do not reveal stratum or labels.
- Input: strict schema, ASCII, length limits, duplicate keys rejected; messages are data only and never executed. Error messages cite case ID and field, never message text.
- Secrets/PII: no secrets; synthetic data only; guards reject e-mail addresses and 6+ digit runs (manual scan of v1 found none). Prompt-injection samples are labeled `ABSTAIN` data, not instructions.
- Audit: manifest SHA-256 and seed, `labelSource` provenance, Git history.
- Supply chain: no new dependency; Python stdlib only; CI step has no secrets.

## Findings
No Blocking or Important findings.
Notes (Minor):
- S1: Isolation relies on source/file scans; a future tool that reads `samples/` through a path built at runtime without the literal markers would not be caught. Mitigation: ArchUnit and review in FEATURE-008.
