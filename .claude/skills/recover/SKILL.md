---
name: recover
description: "Recover a partially completed ticket by mapping current git diff to tasklist items, finding missing artifacts, and recommending the next action."
argument-hint: "<TICKET>"
arguments:
  - ticket
allowed-tools: Read, Glob, Grep, Bash, Edit, Write
---
Recover ticket `$0`. If `$0` is empty, read `docs/.active_ticket`.

Steps:
1. Run `git status --short` and inspect `git diff --stat`.
2. Read PRD, plan, and tasklist.
3. Map current changes to one task. If no task exists, add a proposed recovery task to the tasklist.
4. Run `python3 scripts/validate-workflow.py`.
5. Recommend one next action: continue, commit, fix, review, QA, or reset.

Keep any writes limited to workflow artifacts.
