#!/usr/bin/env python3
"""Prevent an active feature lifecycle from stopping without a terminal state."""
from __future__ import annotations

import json
import sys
from argparse import Namespace

from aidd_state import (
    ACTIVE_STATES,
    append_event,
    atomic_write_json,
    events_path,
    load_state,
    now,
    repository_root,
    state_path,
    transition,
)

ACTIVE_TICKET_PATH = "docs/.active_ticket"
MAX_STOP_BLOCKS = 3
HUMAN_REQUIRED_STATUS = "HUMAN_REQUIRED"
# NEW is a passive initialized state shared by manual slash-command workflows.
# The autonomous controller transitions to PREPARING before it owns the loop.
STOP_GATED_STATES = ACTIVE_STATES - {"NEW"}


def hook_payload() -> dict:
    try:
        value = json.load(sys.stdin)
    except (json.JSONDecodeError, TypeError):
        return {}
    return value if isinstance(value, dict) else {}


def active_ticket(root) -> str | None:
    path = root / ACTIVE_TICKET_PATH
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    return lines[0].strip() if lines else None


def move_to_human_review(root, ticket: str, state: dict) -> None:
    args = Namespace(
        ticket=ticket,
        status=HUMAN_REQUIRED_STATUS,
        phase=state.get("currentPhase"),
        task=state.get("currentTask"),
        next_action="Inspect the repeated Stop gate and resume explicitly",
        failure_fingerprint="stop-gate-repeat-limit",
        evidence=state.get("latestEvidence"),
        event="stop_gate_escalated",
        count_attempt=False,
        human_review=True,
    )
    transition(root, args)


def persist_stop_block(root, ticket: str, state: dict) -> int:
    count = int(state.get("stopBlocks", 0)) + 1
    state["stopBlocks"] = count
    state["updatedAt"] = now()
    atomic_write_json(state_path(root, ticket), state)
    append_event(
        events_path(root, ticket),
        {
            "timestamp": state["updatedAt"],
            "event": "stop_gate_blocked",
            "ticket": ticket,
            "workflowVersion": state["workflowVersion"],
            "from": state["status"],
            "to": state["status"],
            "phase": state.get("currentPhase"),
            "task": state.get("currentTask"),
            "failureFingerprint": None,
            "sameFailureCount": state.get("sameFailureCount", 0),
            "phaseAttempt": state.get("phaseAttempts", {}).get(
                state.get("currentPhase"),
                0,
            ),
            "taskAttempt": state.get("taskAttempts", {}).get(
                state.get("currentTask"),
                0,
            ),
            "countAttempt": False,
            "humanReviewRequired": state.get("humanReviewRequired", False),
        },
    )
    return count


def main() -> None:
    payload = hook_payload()
    root = repository_root()
    ticket = active_ticket(root)
    if not ticket:
        return
    try:
        state = load_state(root, ticket)
    except (OSError, json.JSONDecodeError):
        return
    if state.get("status") not in STOP_GATED_STATES:
        return
    blocks = persist_stop_block(root, ticket, state)
    if blocks >= MAX_STOP_BLOCKS:
        move_to_human_review(root, ticket, state)
        return
    next_action = state.get("nextAction") or "Continue the active feature lifecycle"
    print(json.dumps({
        "decision": "block",
        "reason": (
            f"AIDD lifecycle {ticket} is still {state['status']}. "
            f"Continue with: {next_action}. Stop block {blocks}/{MAX_STOP_BLOCKS}."
        ),
    }))


if __name__ == "__main__":
    main()
