#!/usr/bin/env python3
"""Summarize feature-loop evidence for human-reviewed workflow evolution."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

EVENT_PATTERN = ".aidd/runtime/features/*/events.jsonl"
PASS_STATE = "READY_FOR_USER_COMMIT"
FAILURE_STATES = {"REPAIRING", "HUMAN_REQUIRED", "BLOCKED"}
HUMAN_STATES = {"HUMAN_REQUIRED", "BLOCKED"}
MINIMUM_EARLY_SAMPLE = 3
DEFAULT_PROMOTION_SAMPLE = 6


def load_events(root: Path) -> list[dict]:
    events: list[dict] = []
    for path in sorted(root.glob(EVENT_PATTERN)):
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(event, dict):
                events.append(event)
    return events


def tickets_reaching(events: list[dict], states: set[str]) -> set[str]:
    return {
        str(event["ticket"])
        for event in events
        if event.get("ticket") and event.get("to") in states
    }


def counted_attempts(events: list[dict]) -> int:
    return sum(bool(event.get("countAttempt")) for event in events)


def report(events: list[dict], minimum_features: int) -> int:
    tickets = {
        str(event["ticket"])
        for event in events
        if event.get("ticket")
    }
    completed = tickets_reaching(events, {PASS_STATE})
    escalated = tickets_reaching(events, HUMAN_STATES)
    failed = tickets_reaching(events, FAILURE_STATES)
    failures = Counter(
        str(event["failureFingerprint"])
        for event in events
        if event.get("failureFingerprint")
    )
    print("# AIDD evolution evidence")
    print()
    print(f"- Observed features: {len(tickets)}")
    print(f"- Completed features: {len(completed)}")
    print(f"- Features entering repair or escalation: {len(failed)}")
    print(f"- Human/block escalations: {len(escalated)}")
    print(f"- Counted implementation/repair/verification attempts: {counted_attempts(events)}")
    print()
    if len(completed) < MINIMUM_EARLY_SAMPLE:
        print(
            "Diagnostic eligibility: NO — collect at least "
            f"{MINIMUM_EARLY_SAMPLE} completed features."
        )
    else:
        print("Diagnostic eligibility: YES — shadow analysis only.")
    if len(completed) < minimum_features:
        print(
            f"Canary recommendation eligibility: NO — collect at least "
            f"{minimum_features} completed features."
        )
    else:
        print("Canary recommendation eligibility: YES — human approval required.")
    if failures:
        print()
        print("## Repeated failure fingerprints")
        print()
        for fingerprint, count in failures.most_common(10):
            print(f"- {fingerprint}: {count}")
    print()
    print("Automatic workflow edits, canary, and promotion are forbidden.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--minimum-features",
        type=int,
        default=DEFAULT_PROMOTION_SAMPLE,
    )
    args = parser.parse_args()
    if args.minimum_features < MINIMUM_EARLY_SAMPLE:
        parser.error(
            f"--minimum-features must be at least {MINIMUM_EARLY_SAMPLE}"
        )
    return report(load_events(Path.cwd().resolve()), args.minimum_features)


if __name__ == "__main__":
    raise SystemExit(main())
