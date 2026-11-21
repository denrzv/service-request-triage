#!/usr/bin/env python3
"""Block accidental diagram source writes unless the approved plan declares diagram impact."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
DIAGRAM_EXTENSIONS = {".puml", ".plantuml"}


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


def rel_path(root: Path, file_path: str) -> str | None:
    if not file_path:
        return None
    p = Path(file_path)
    if not p.is_absolute():
        p = root / p
    try:
        return p.resolve().relative_to(root).as_posix()
    except ValueError:
        return None


def active_ticket(root: Path) -> str | None:
    active = root / "docs" / ".active_ticket"
    if not active.exists():
        return None
    lines = active.read_text(encoding="utf-8").splitlines()
    return lines[0].strip() if lines else None


def plan_allows_diagrams(root: Path, ticket: str | None) -> bool:
    if not ticket:
        return False
    plan = root / "docs" / "plan" / f"{ticket}.md"
    if not plan.exists():
        return False
    text = plan.read_text(encoding="utf-8", errors="replace")
    return re.search(r"(?im)^\s*Render diagrams required:\s*yes\s*$", text) is not None


def is_diagram_source(rel: str) -> bool:
    if not rel.startswith("diagrams/"):
        return False
    if rel.startswith("diagrams/out/") or "/out/" in rel:
        return False
    return Path(rel).suffix.lower() in DIAGRAM_EXTENSIONS


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    if payload.get("tool_name") not in WRITE_TOOLS:
        return 0

    root = project_root(payload)
    tool_input = payload.get("tool_input", {}) or {}
    file_path = tool_input.get("file_path") or tool_input.get("notebook_path") or tool_input.get("path") or ""
    rel = rel_path(root, file_path)
    if rel is None or not is_diagram_source(rel):
        return 0

    if os.environ.get("AIDD_ALLOW_DIAGRAM_WRITES", "").lower() in {"1", "true", "yes"}:
        return 0

    ticket = active_ticket(root)
    if not plan_allows_diagrams(root, ticket):
        deny(
            "AIDD diagram policy blocked diagram source write. "
            "Update docs/plan/<TICKET>.md first and set 'Render diagrams required: yes' only when C4 System Context, C4 Container, or ERD structure changes. "
            f"Blocked path: {rel}"
        )
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
