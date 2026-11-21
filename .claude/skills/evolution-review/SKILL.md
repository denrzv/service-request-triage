---
name: evolution-review
description: "Analyze accumulated AIDD feature-loop evidence and draft a shadow-only workflow improvement proposal. Never modifies the workflow."
argument-hint: "<optional focus>"
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Write
---
# Evidence-based workflow evolution review

Analyze workflow performance without modifying agents, skills, hooks, settings,
scripts, or product code.

1. Run `python3 scripts/aidd-evolution-report.py`.
2. Read only the relevant feature events, metrics summary, and current workflow
   files needed to explain a repeated pattern.
3. Write a proposal to
   `reports/evolution/<YYYY-MM-DD>-shadow-proposal.md` containing:
   - observed evidence and sample size;
   - diagnosis;
   - one minimal proposed workflow change;
   - expected benefit and possible regression;
   - shadow-evaluation method;
   - rejection and rollback criteria.
4. Require at least three features for an early diagnostic proposal and at
   least six completed features before recommending a canary.

Do not edit the workflow automatically. Canary and promotion require an
explicit user decision after shadow evidence.
