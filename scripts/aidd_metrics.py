#!/usr/bin/env python3
"""Append privacy-safe Claude Code lifecycle metrics to a local JSONL file."""
from __future__ import annotations

import json
import os
import re
import shlex
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

TEXT_ENCODING = "utf-8"
METRICS_RELATIVE_PATH = Path(".aidd/metrics/events.jsonl")
ACTIVE_TICKET_RELATIVE_PATH = Path("docs/.active_ticket")
TOOL_PATH_KEYS = ("file_path", "notebook_path", "path")
TOOL_EVENTS = {"PostToolUse", "PostToolUseFailure", "PermissionRequest"}
COMPLETION_MARKERS = (
    "PARTIAL_REQUIRES_FOLLOWUP",
    "BLOCKED_NEEDS_USER_INPUT",
    "COMPLETE",
)
OUTCOME_MARKERS = (
    "VERIFICATION_BLOCKED",
    "VERIFICATION_PASS",
    "RELIABILITY_BLOCKED",
    "RELIABILITY_PASS",
    "CONTRACT_BLOCKED",
    "CONTRACT_PASS",
    "SECURITY_PASS_WITH_NOTES",
    "DOCS_COMPLETE",
    "DOCS_BLOCKED",
    "DB_BLOCKED",
    "DB_PASS",
    "REVIEW_APPROVED",
    "REVIEW_BLOCKED",
    "QA_PASSED",
    "QA_BLOCKED",
    "SECURITY_PASS",
    "SECURITY_BLOCKED",
)
STANDARD_REVIEWER_AGENT = "reviewer"
DEEP_REVIEWER_AGENT = "reviewer-opus"
OUTCOME_PREFIX_BY_AGENT = {
    STANDARD_REVIEWER_AGENT: "VERIFICATION_",
    DEEP_REVIEWER_AGENT: "REVIEW_",
    "qa": "QA_",
    "security-privacy-guardian": "SECURITY_",
    "tech-writer": "DOCS_",
    "contract-guardian": "CONTRACT_",
    "db-migration-guardian": "DB_",
    "reliability-guardian": "RELIABILITY_",
}
TICKET_PATTERN = re.compile(r"\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\d+\b")
TASK_PATTERN = re.compile(r"\bTASK-(?:FIX-)?\d+\b", re.IGNORECASE)
TASK_HEADER_PATTERN = re.compile(r"(?im)^\s*Task:\s*(TASK-(?:FIX-)?\d+)\b")
IMPLEMENTER_AGENT = "implementer"
DEEP_IMPLEMENTER_AGENT = "implementer-opus"
IMPLEMENTER_AGENTS = frozenset({IMPLEMENTER_AGENT, DEEP_IMPLEMENTER_AGENT})

AGENT_PHASES = {
    "analyst": "idea",
    "researcher": "research",
    "planner": "plan",
    "task-planner": "tasks",
    IMPLEMENTER_AGENT: "implement",
    DEEP_IMPLEMENTER_AGENT: "implement",
    STANDARD_REVIEWER_AGENT: "verify",
    DEEP_REVIEWER_AGENT: "review",
    "qa": "qa",
    "security-privacy-guardian": "security-review",
    "tech-writer": "docs-update",
    "validator": "validate",
    "contract-guardian": "contract-review",
    "db-migration-guardian": "db-review",
    "reliability-guardian": "reliability-review",
}

COMMAND_PHASES = {
    "idea": "idea",
    "researcher": "research",
    "plan": "plan",
    "tasks": "tasks",
    "implement": "implement",
    "review": "verify",
    "qa": "qa",
    "security-review": "security-review",
    "docs-update": "docs-update",
    "contract-review": "contract-review",
    "db-review": "db-review",
    "reliability-review": "reliability-review",
    "fix-tasks": "fix-tasks",
    "gate-policy": "gate-policy",
    "validate": "validate",
    "feature-development": "feature-development",
}


def project_root(payload: dict) -> Path:
    configured_root = os.environ.get("CLAUDE_PROJECT_DIR")
    return Path(configured_root or payload.get("cwd") or Path.cwd()).resolve()


def active_ticket(root: Path) -> str | None:
    path = root / ACTIVE_TICKET_RELATIVE_PATH
    try:
        lines = path.read_text(encoding=TEXT_ENCODING).splitlines()
    except OSError:
        return None
    return lines[0].strip() if lines else None


def current_time_fields() -> dict:
    timestamp_ms = time.time_ns() // 1_000_000
    timestamp = datetime.fromtimestamp(timestamp_ms / 1000, timezone.utc).isoformat()
    return {"timestamp": timestamp, "timestamp_ms": timestamp_ms}


def append_event(root: Path, event: dict) -> None:
    path = root / METRICS_RELATIVE_PATH
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        encoded = (json.dumps(event, separators=(",", ":"), sort_keys=True) + "\n").encode(TEXT_ENCODING)
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        try:
            os.write(descriptor, encoded)
        finally:
            os.close(descriptor)
    except (OSError, TypeError, ValueError):
        # Metrics must never block or alter the workflow they observe.
        return


def safe_relative_path(root: Path, tool_input: dict) -> str | None:
    raw_path = next(
        (tool_input.get(key) for key in TOOL_PATH_KEYS if tool_input.get(key)),
        None,
    )
    if not isinstance(raw_path, str):
        return None
    path = Path(raw_path)
    candidate = path if path.is_absolute() else root / path
    try:
        return candidate.resolve().relative_to(root).as_posix()
    except (OSError, ValueError):
        return None


def bash_category(command: str) -> str:
    if "validate-workflow.py" in command:
        return "workflow-validation"
    if "pre-commit-check.sh" in command or "security-check.sh" in command:
        return "project-validation"
    try:
        tokens = shlex.split(command)
    except ValueError:
        return "other"
    token_text = " ".join(tokens)
    executable_names = {Path(token).name for token in tokens if not token.startswith("-")}
    if executable_names & {"rg", "grep", "find"}:
        return "repository-search"
    categories = (
        ("mvnw", "maven"),
        ("gradlew", "gradle"),
        ("npm", "node"),
        ("pnpm", "node"),
        ("yarn", "node"),
        ("git", "git"),
        ("pytest", "test"),
        ("curl", "live-check"),
        ("docker", "container"),
    )
    for marker, category in categories:
        if marker in token_text:
            return category
    return "other"


def command_context(payload: dict, root: Path) -> dict:
    command_name = str(payload.get("command_name") or "unknown")
    command_args = str(payload.get("command_args") or "")
    ticket_match = TICKET_PATTERN.search(command_args)
    task_match = TASK_PATTERN.search(command_args)
    return {
        "command": command_name,
        "phase": COMMAND_PHASES.get(command_name, command_name),
        "ticket": ticket_match.group(0) if ticket_match else active_ticket(root),
        "task": task_match.group(0).upper() if task_match else None,
    }


def last_marker(message: str, markers: tuple[str, ...]) -> str | None:
    matches = (
        (message.rfind(marker), marker)
        for marker in markers
    )
    return max(
        (match for match in matches if match[0] >= 0),
        default=(-1, None),
    )[1]


def subagent_context(payload: dict) -> dict:
    agent_type = str(payload.get("agent_type") or "unknown")
    context = {
        "agent_id": payload.get("agent_id"),
        "agent_type": agent_type,
        "phase": AGENT_PHASES.get(agent_type, agent_type),
    }
    if payload.get("hook_event_name") == "SubagentStop":
        message = str(payload.get("last_assistant_message") or "")
        outcome_prefix = OUTCOME_PREFIX_BY_AGENT.get(agent_type)
        allowed_outcomes = (
            tuple(marker for marker in OUTCOME_MARKERS if marker.startswith(outcome_prefix))
            if outcome_prefix else ()
        )
        context["result"] = last_marker(message, COMPLETION_MARKERS)
        context["outcome"] = last_marker(message, allowed_outcomes)
        if agent_type in IMPLEMENTER_AGENTS:
            task_match = TASK_HEADER_PATTERN.search(message)
            context["task"] = task_match.group(1).upper() if task_match else None
            context["task_source"] = "final-header" if task_match else None
    return context


def tool_context(payload: dict, root: Path) -> dict:
    tool_name = str(payload.get("tool_name") or "unknown")
    tool_input = payload.get("tool_input", {}) or {}
    context = {
        "agent_id": payload.get("agent_id"),
        "tool": tool_name,
        "tool_use_id": payload.get("tool_use_id"),
    }
    duration_ms = payload.get("duration_ms")
    if isinstance(duration_ms, (int, float)):
        context["duration_ms"] = duration_ms
    path = safe_relative_path(root, tool_input)
    if path:
        context["path"] = path
    if tool_name == "Bash":
        context["category"] = bash_category(str(tool_input.get("command") or ""))
    return context


def build_event(payload: dict, root: Path) -> dict:
    event_name = str(payload.get("hook_event_name") or "unknown")
    event = {
        "event": event_name,
        "session_id": payload.get("session_id"),
        "ticket": active_ticket(root),
        **current_time_fields(),
    }
    if event_name == "UserPromptExpansion":
        event.update(command_context(payload, root))
    elif event_name in {"SubagentStart", "SubagentStop"}:
        event.update(subagent_context(payload))
    elif event_name in TOOL_EVENTS:
        event.update(tool_context(payload, root))
    elif event_name == "SessionStart":
        event["source"] = payload.get("source")
        event["model"] = payload.get("model")
    elif event_name == "SessionEnd":
        event["reason"] = payload.get("reason")
    return event


def record_internal_event(root: Path, name: str, ticket: str | None, **fields: object) -> None:
    event = {
        "event": name,
        "ticket": ticket,
        **current_time_fields(),
        **fields,
    }
    append_event(root, event)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, TypeError):
        return
    root = project_root(payload)
    append_event(root, build_event(payload, root))


if __name__ == "__main__":
    main()
