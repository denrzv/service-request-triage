#!/usr/bin/env python3
"""Restrict subagent writes to their expected artifact areas."""
from __future__ import annotations

import fnmatch
import json
import os
import sys
from pathlib import Path

from aidd_common import SCRIPT_PREFIX, agent_script_write_allowed
from aidd_task_budget import repair_budget_status

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
REVIEWER_AGENT = "reviewer"
TASKLIST_PREFIX = "docs/tasklist/"

AGENT_ALLOWED_PATTERNS = {
    REVIEWER_AGENT: [
        "reports/bugfix/**",
        "reports/review/**",
        "reports/qa/**",
        "reports/security/**",
        "reports/docs/**",
    ],
}


def deny(reason: str) -> None:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


def project_root(payload: dict) -> Path:
    return Path(os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or Path.cwd()).resolve()


def relative(root: Path, file_path: str) -> str | None:
    if not file_path:
        return None
    p = Path(file_path)
    if not p.is_absolute():
        p = root / p
    try:
        return p.resolve().relative_to(root).as_posix()
    except ValueError:
        return None


def matches(rel: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(rel, pattern) for pattern in patterns)


def apply_edit(text: str, edit: dict) -> str:
    old = edit.get("old_string")
    new = edit.get("new_string")
    if not isinstance(old, str) or not isinstance(new, str) or old not in text:
        return text
    return text.replace(old, new) if edit.get("replace_all") else text.replace(old, new, 1)


def proposed_text(current: str, tool_name: str, tool_input: dict) -> str | None:
    if tool_name == "Write":
        content = tool_input.get("content")
        return content if isinstance(content, str) else None
    if tool_name == "Edit":
        return apply_edit(current, tool_input)
    if tool_name == "MultiEdit":
        result = current
        for edit in tool_input.get("edits") or []:
            if isinstance(edit, dict):
                result = apply_edit(result, edit)
        return result
    return None


def enforce_repair_budget(
    root: Path,
    rel: str,
    tool_name: str,
    tool_input: dict,
) -> None:
    if not rel.startswith(TASKLIST_PREFIX):
        return
    path = root / rel
    try:
        current = path.read_text(encoding="utf-8")
    except OSError:
        current = ""
    proposed = proposed_text(current, tool_name, tool_input)
    if proposed is None:
        return

    current_status = repair_budget_status(current)
    proposed_status = repair_budget_status(proposed)
    if current_status.override != proposed_status.override:
        deny(
            "Only the user/maintainer may add or change "
            "`Repair budget override` in a tasklist."
        )
    if (
        proposed_status.fix_tasks > current_status.fix_tasks
        and proposed_status.fix_tasks > proposed_status.repair_budget
    ):
        deny(
            "Repair budget exhausted: proposed "
            f"{proposed_status.fix_tasks}/{proposed_status.repair_budget} fix tasks. "
            "Request an explicit user-approved tasklist override before appending."
        )


def enforce_script_scope(rel: str) -> None:
    if rel.startswith(SCRIPT_PREFIX) and not agent_script_write_allowed(rel):
        deny(
            "Only explicitly allowlisted task scripts may be agent-edited; "
            f"blocked path: {rel}"
        )


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return

    if payload.get("tool_name") not in WRITE_TOOLS:
        return

    root = project_root(payload)
    tool_input = payload.get("tool_input", {}) or {}
    file_path = tool_input.get("file_path") or tool_input.get("notebook_path") or tool_input.get("path") or ""
    if not file_path:
        return
    rel = relative(root, file_path)
    if rel is None:
        if payload.get("agent_type") in AGENT_ALLOWED_PATTERNS:
            deny(
                f"Subagent {payload['agent_type']} may not write outside "
                f"the project root: {file_path}"
            )
        return

    enforce_script_scope(rel)
    enforce_repair_budget(root, rel, payload["tool_name"], tool_input)

    agent_type = payload.get("agent_type")
    if not agent_type or agent_type not in AGENT_ALLOWED_PATTERNS:
        return
    allowed = AGENT_ALLOWED_PATTERNS[agent_type]
    if not matches(rel, allowed):
        deny(f"Subagent {agent_type} may write only to {allowed}; blocked path: {rel}")


if __name__ == "__main__":
    main()
