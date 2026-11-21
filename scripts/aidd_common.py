#!/usr/bin/env python3
"""Shared helpers for AIDD PreToolUse guards.

Keep this module dependency-free (stdlib only). Guards import it via a
sys.path insert of their own directory, so it must live next to them in
scripts/.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from contextlib import suppress
from pathlib import Path

# Tool input keys that can carry a target file path, per tool:
# Write/Edit use file_path, NotebookEdit uses notebook_path,
# some legacy payloads use path.
PATH_KEYS = ("file_path", "notebook_path", "path")

WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}

CACHE_SCHEMA_VERSION = 2
CACHE_RELATIVE_PATH = Path(".aidd/cache/workflow-gates.json")
VALIDATOR_RELATIVE_PATH = Path("scripts/validate-workflow.py")
COMMON_RELATIVE_PATH = Path("scripts/aidd_common.py")
ADR_RELATIVE_DIRECTORY = Path("docs/adr")
ADR_REFERENCE_RE = re.compile(r"docs/adr/[A-Za-z0-9._-]+\.md")
# Index files that list baseline ADRs inline; they are referenced by ADR ID,
# not gated as a single decision with one Status line.
ADR_INDEX_FILENAMES = frozenset({"README.md"})
ADR_SECTION_RE = re.compile(r"(?ms)^## ADRs\s*$\n(.*?)(?=^## |\Z)")
RESEARCH_REQUIRED_VALUES = {"1", "true", "yes"}
TEXT_ENCODING = "utf-8"
SCRIPT_PREFIX = "scripts/"
# Exact scripts/* paths an agent may edit after workflow gates pass.
# Empty by default: every workflow script is human-maintained. A maintainer
# adds exact paths here (and a matching Edit(...) allow rule) when a ticket
# legitimately owns an operational script.
AGENT_EDITABLE_SCRIPT_PATHS: frozenset[str] = frozenset()
GATE_CACHE_EVENT = "gate_cache"
CACHE_HIT = "hit"
CACHE_MISS = "miss"
CACHE_REFRESH = "refresh"

# Paths writable without workflow gates (governance artifacts only).
# Deliberately excludes .claude/, scripts/, hooks/, .github/:
#   - .claude/settings.json, scripts/, hooks/ are additionally hard-denied
#     in permissions.deny (enforcement layer is human-maintained);
#   - other .claude/ and .github/ writes must pass workflow gates like
#     application code.
DEFAULT_ALLOWED_PREFIXES = (
    "docs/",
    "reports/",
    "diagrams/",
    "conventions/",
)
DEFAULT_ALLOWED_FILES = {
    "AGENTS.md",
    "CLAUDE.md",
    "README.md",
    "INSTALL.md",
    "architecture.md",
    "conventions.md",
    "workflow.md",
    ".gitignore",
}


def deny(reason: str) -> None:
    """Emit a PreToolUse deny decision and exit 0 (JSON is only honored on exit 0)."""
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


def target_path(tool_input: dict) -> str:
    for key in PATH_KEYS:
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            return value
    return ""


def rel_path(root: Path, file_path: str) -> str | None:
    """Resolve file_path relative to root; None if empty or outside root."""
    if not file_path:
        return None
    p = Path(file_path)
    if not p.is_absolute():
        p = root / p
    try:
        return p.resolve().relative_to(root).as_posix()
    except ValueError:
        return None


def allowed_governance_path(rel: str) -> bool:
    extra_prefixes = tuple(filter(None, os.environ.get("AIDD_ALLOWED_WRITE_PREFIXES", "").split(":")))
    prefixes = DEFAULT_ALLOWED_PREFIXES + extra_prefixes
    return rel in DEFAULT_ALLOWED_FILES or any(rel.startswith(prefix) for prefix in prefixes)


def agent_script_write_allowed(rel: str) -> bool:
    """Return whether an agent may edit this exact script path after gates."""
    return rel in AGENT_EDITABLE_SCRIPT_PATHS


def active_ticket(root: Path) -> str | None:
    active = root / "docs" / ".active_ticket"
    if not active.exists():
        return None
    lines = active.read_text(encoding=TEXT_ENCODING).splitlines()
    return lines[0].strip() if lines else None


def file_digest(path: Path) -> str | None:
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def workflow_adr_paths(root: Path, ticket: str) -> tuple[Path, ...]:
    """Return ticket-owned and plan-linked ADR paths relative to the repo root."""
    relative_paths: set[Path] = set()
    adr_directory = root / ADR_RELATIVE_DIRECTORY
    ticket_prefix = f"{ticket}-"
    ticket_filename = f"{ticket}.md"

    if adr_directory.is_dir():
        for path in adr_directory.glob("*.md"):
            if path.name == ticket_filename or path.name.startswith(ticket_prefix):
                relative_paths.add(ADR_RELATIVE_DIRECTORY / path.name)

    plan_path = root / "docs" / "plan" / f"{ticket}.md"
    try:
        plan_text = plan_path.read_text(encoding=TEXT_ENCODING)
    except OSError:
        plan_text = ""
    section_match = ADR_SECTION_RE.search(plan_text)
    if section_match:
        relative_paths.update(
            Path(reference)
            for reference in ADR_REFERENCE_RE.findall(section_match.group(1))
            if Path(reference).name not in ADR_INDEX_FILENAMES
        )

    return tuple(sorted(relative_paths, key=Path.as_posix))


def workflow_gate_signature(root: Path, ticket: str, require_research: bool) -> dict:
    relative_paths = [
        Path("docs/.active_ticket"),
        Path(f"docs/prd/{ticket}.prd.md"),
        Path(f"docs/research/{ticket}.md"),
        Path(f"docs/plan/{ticket}.md"),
        Path(f"docs/tasklist/{ticket}.md"),
        VALIDATOR_RELATIVE_PATH,
        COMMON_RELATIVE_PATH,
        *workflow_adr_paths(root, ticket),
    ]
    return {
        "schema": CACHE_SCHEMA_VERSION,
        "ticket": ticket,
        "requireResearch": require_research,
        "files": {
            path.as_posix(): file_digest(root / path)
            for path in relative_paths
        },
    }


def record_gate_cache_event(root: Path, ticket: str, status: str) -> None:
    try:
        from aidd_metrics import record_internal_event
    except ImportError:
        return
    record_internal_event(root, GATE_CACHE_EVENT, ticket, status=status)


def cache_workflow_gates(root: Path, ticket: str, require_research: bool) -> None:
    cache_path = root / CACHE_RELATIVE_PATH
    temporary_path = cache_path.with_name(f"{cache_path.name}.{os.getpid()}.tmp")
    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"signature": workflow_gate_signature(root, ticket, require_research)}
        temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding=TEXT_ENCODING)
        temporary_path.replace(cache_path)
        record_gate_cache_event(root, ticket, CACHE_REFRESH)
    except OSError:
        # Caching is an optimization. A successful validation must not fail when
        # the local cache directory is unavailable or read-only.
        pass
    finally:
        with suppress(OSError):
            temporary_path.unlink(missing_ok=True)


def cached_workflow_gates_pass(root: Path, ticket: str, require_research: bool) -> bool:
    cache_path = root / CACHE_RELATIVE_PATH
    try:
        payload = json.loads(cache_path.read_text(encoding=TEXT_ENCODING))
    except (OSError, json.JSONDecodeError):
        return False
    expected = workflow_gate_signature(root, ticket, require_research)
    return payload.get("signature") == expected


def workflow_gates_pass(root: Path) -> tuple[bool, str]:
    """Use a valid cache or run validate-workflow.py."""
    validator = root / VALIDATOR_RELATIVE_PATH
    if not validator.exists():
        # Pack not fully installed; fail open rather than brick the repo.
        return True, ""
    ticket = active_ticket(root)
    require_research = os.environ.get("AIDD_REQUIRE_RESEARCH", "").lower() in RESEARCH_REQUIRED_VALUES
    if ticket:
        if cached_workflow_gates_pass(root, ticket, require_research):
            record_gate_cache_event(root, ticket, CACHE_HIT)
            return True, ""
        record_gate_cache_event(root, ticket, CACHE_MISS)
    quiet = subprocess.run(
        [sys.executable, str(validator), "--quiet"],
        cwd=root, text=True, capture_output=True,
    )
    if quiet.returncode == 0:
        return True, ""
    details = subprocess.run(
        [sys.executable, str(validator)],
        cwd=root, text=True, capture_output=True,
    )
    message = details.stdout.strip() or details.stderr.strip() or "Workflow gates are not satisfied."
    return False, message
