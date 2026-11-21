#!/usr/bin/env python3
"""Persist the controller state for one AIDD feature lifecycle."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

TEXT_ENCODING = "utf-8"
WORKFLOW_VERSION = "4.5.0"
RUNTIME_ROOT = Path(".aidd/runtime/features")
STATE_FILE = "state.json"
EVENTS_FILE = "events.jsonl"
SAFE_TICKET = re.compile(r"^[A-Z0-9][A-Z0-9-]*$")
TERMINAL_STATES = {
    "READY_FOR_USER_COMMIT",
    "SESSION_CHECKPOINT_READY",
    "HUMAN_REQUIRED",
    "BLOCKED",
    "CANCELLED",
}
ACTIVE_STATES = {
    "NEW",
    "PREPARING",
    "READY_FOR_IMPLEMENTATION",
    "IMPLEMENTING",
    "VERIFYING",
    "REPAIRING",
}
ALL_STATES = ACTIVE_STATES | TERMINAL_STATES
MUTATING_STATES = {"IMPLEMENTING", "REPAIRING"}
PROTECTED_BRANCHES = {"main", "master", "detached"}


def repository_root() -> Path:
    configured = os.environ.get("CLAUDE_PROJECT_DIR")
    return Path(configured or Path(__file__).resolve().parents[1]).resolve()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def require_ticket(ticket: str) -> str:
    normalized = ticket.strip().upper()
    if not SAFE_TICKET.fullmatch(normalized):
        raise ValueError(f"Invalid ticket identifier: {ticket}")
    return normalized


def run_git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def branch_name(root: Path) -> str:
    return run_git(root, "branch", "--show-current") or "detached"


def state_directory(root: Path, ticket: str) -> Path:
    return root / RUNTIME_ROOT / ticket


def state_path(root: Path, ticket: str) -> Path:
    return state_directory(root, ticket) / STATE_FILE


def events_path(root: Path, ticket: str) -> Path:
    return state_directory(root, ticket) / EVENTS_FILE


def initial_state(root: Path, ticket: str) -> dict:
    timestamp = now()
    return {
        "schemaVersion": 1,
        "workflowVersion": WORKFLOW_VERSION,
        "ticket": ticket,
        "status": "NEW",
        "currentPhase": "initialization",
        "currentTask": None,
        "branch": branch_name(root),
        "createdAt": timestamp,
        "updatedAt": timestamp,
        "phaseAttempts": {},
        "taskAttempts": {},
        "lastFailureFingerprint": None,
        "sameFailureCount": 0,
        "latestEvidence": None,
        "nextAction": "Prepare ticket artifacts",
        "humanReviewRequired": False,
        "stopBlocks": 0,
    }


def atomic_write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding=TEXT_ENCODING,
    )
    temporary.replace(path)


def append_event(path: Path, event: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(event, separators=(",", ":"), sort_keys=True) + "\n"
    with path.open("a", encoding=TEXT_ENCODING) as stream:
        stream.write(encoded)


def load_state(root: Path, ticket: str) -> dict:
    path = state_path(root, ticket)
    if not path.exists():
        raise FileNotFoundError(f"State not initialized: {path}")
    return json.loads(path.read_text(encoding=TEXT_ENCODING))


def save_transition(
    root: Path,
    state: dict,
    previous_status: str,
    event_name: str,
    failure_fingerprint: str | None,
    count_attempt: bool,
) -> None:
    ticket = state["ticket"]
    atomic_write_json(state_path(root, ticket), state)
    append_event(
        events_path(root, ticket),
        {
            "timestamp": state["updatedAt"],
            "event": event_name,
            "ticket": ticket,
            "workflowVersion": WORKFLOW_VERSION,
            "from": previous_status,
            "to": state["status"],
            "phase": state["currentPhase"],
            "task": state["currentTask"],
            "failureFingerprint": failure_fingerprint,
            "sameFailureCount": state["sameFailureCount"],
            "phaseAttempt": state["phaseAttempts"].get(state["currentPhase"], 0),
            "taskAttempt": state["taskAttempts"].get(state["currentTask"], 0),
            "countAttempt": count_attempt,
            "humanReviewRequired": state["humanReviewRequired"],
        },
    )


def initialize(root: Path, ticket: str) -> dict:
    path = state_path(root, ticket)
    if path.exists():
        return load_state(root, ticket)
    state = initial_state(root, ticket)
    save_transition(root, state, "NONE", "feature_initialized", None, False)
    return state


def increment_attempt(counter: dict, key: str | None) -> None:
    if key:
        counter[key] = int(counter.get(key, 0)) + 1


def failure_count(state: dict, fingerprint: str | None) -> int:
    if not fingerprint:
        return 0
    return (
        int(state.get("sameFailureCount", 0)) + 1
        if state.get("lastFailureFingerprint") == fingerprint
        else 1
    )


def transition(root: Path, args: argparse.Namespace) -> dict:
    current_branch = branch_name(root)
    if args.status in MUTATING_STATES and current_branch in PROTECTED_BRANCHES:
        raise ValueError(
            f"Refusing {args.status} on protected branch {current_branch}; "
            "use a feature branch or isolated worktree"
        )
    state = initialize(root, args.ticket)
    previous_status = str(state["status"])
    state["status"] = args.status
    state["currentPhase"] = args.phase or state.get("currentPhase")
    state["currentTask"] = args.task
    state["nextAction"] = args.next_action or state.get("nextAction")
    state["humanReviewRequired"] = bool(args.human_review)
    state["sameFailureCount"] = failure_count(state, args.failure_fingerprint)
    state["lastFailureFingerprint"] = args.failure_fingerprint
    state["updatedAt"] = now()
    state["branch"] = current_branch
    if args.count_attempt:
        increment_attempt(state["phaseAttempts"], state.get("currentPhase"))
        increment_attempt(state["taskAttempts"], state.get("currentTask"))
    if args.evidence:
        state["latestEvidence"] = args.evidence
    state["stopBlocks"] = 0
    save_transition(
        root,
        state,
        previous_status,
        args.event or "state_transition",
        args.failure_fingerprint,
        args.count_attempt,
    )
    return state


def compact_summary(state: dict) -> str:
    phase = state.get("currentPhase") or "-"
    task = state.get("currentTask") or "-"
    next_action = state.get("nextAction") or "-"
    return (
        f"{state['ticket']} status={state['status']} phase={phase} task={task} "
        f"sameFailureCount={state.get('sameFailureCount', 0)} next={next_action}"
    )


def fingerprint_text(value: str) -> str:
    return hashlib.sha256(value.encode(TEXT_ENCODING)).hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket", required=True, type=require_ticket)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("init")
    subparsers.add_parser("show")
    transition_parser = subparsers.add_parser("transition")
    transition_parser.add_argument("--status", required=True, choices=sorted(ALL_STATES))
    transition_parser.add_argument("--phase")
    transition_parser.add_argument("--task")
    transition_parser.add_argument("--next-action")
    transition_parser.add_argument("--failure-fingerprint")
    transition_parser.add_argument("--evidence")
    transition_parser.add_argument("--event")
    transition_parser.add_argument("--count-attempt", action="store_true")
    transition_parser.add_argument("--human-review", action="store_true")
    fingerprint_parser = subparsers.add_parser("fingerprint")
    fingerprint_parser.add_argument("value")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repository_root()
    try:
        if args.command == "init":
            state = initialize(root, args.ticket)
        elif args.command == "show":
            state = load_state(root, args.ticket)
        elif args.command == "fingerprint":
            print(fingerprint_text(args.value))
            return 0
        else:
            state = transition(root, args)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(compact_summary(state))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
