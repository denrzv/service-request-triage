#!/usr/bin/env python3
"""Path-aware Claude Code PreToolUse gate for application code writes.

Blocks Write/Edit/NotebookEdit outside governance paths until PRD, plan,
accepted ADR decisions, and tasklist workflow gates pass. Bash-based writes
are gated separately by scripts/bash-command-guard.py.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from aidd_common import (  # noqa: E402
    WRITE_TOOLS,
    allowed_governance_path,
    deny,
    project_root,
    rel_path,
    target_path,
    workflow_gates_pass,
)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    if payload.get("tool_name") not in WRITE_TOOLS:
        return 0

    root = project_root(payload)
    tool_input = payload.get("tool_input", {}) or {}
    file_path = target_path(tool_input)

    if not file_path:
        # Unknown payload shape; other layers (permissions, agent guard) still apply.
        return 0

    rel = rel_path(root, file_path)
    if rel is None:
        deny(f"AIDD gate blocked write outside project root: {file_path}")

    if allowed_governance_path(rel):
        return 0

    ok, message = workflow_gates_pass(root)
    if not ok:
        deny(
            "AIDD gate blocked application code/config write before "
            "PRD_READY, PLAN_APPROVED, ACCEPTED ADR decisions, and "
            "TASKLIST_READY.\n" + message
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
