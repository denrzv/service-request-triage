#!/usr/bin/env python3
"""Print a narrow tasklist slice without loading the whole ticket history."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from aidd_task_budget import TASK_HEADER, format_repair_budget, repair_budget_status

TEXT_ENCODING = "utf-8"
SECTION_HEADER = re.compile(r"^## ")
SAFE_TICKET = re.compile(r"^[A-Z0-9][A-Z0-9-]*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract one task or list pending task IDs."
    )
    parser.add_argument("--ticket", required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--task", help="Exact TASK-N or TASK-FIX-N identifier")
    action.add_argument(
        "--pending",
        action="store_true",
        help="Print unchecked task IDs in file order",
    )
    action.add_argument(
        "--repair-status",
        action="store_true",
        help="Print adaptive fix-task budget usage",
    )
    return parser.parse_args()


def tasklist_path(ticket: str) -> Path:
    if not SAFE_TICKET.fullmatch(ticket):
        raise ValueError(f"Invalid ticket identifier: {ticket}")
    root = Path(__file__).resolve().parents[1]
    return root / "docs" / "tasklist" / f"{ticket}.md"


def task_blocks(lines: list[str]) -> list[tuple[str, bool, int, int]]:
    headers: list[tuple[str, bool, int]] = []
    for index, line in enumerate(lines):
        match = TASK_HEADER.match(line)
        if match:
            headers.append(
                (match.group("id"), match.group("state").lower() == "x", index)
            )

    blocks: list[tuple[str, bool, int, int]] = []
    for position, (task_id, checked, start) in enumerate(headers):
        next_task = headers[position + 1][2] if position + 1 < len(headers) else len(lines)
        end = next_task
        for index in range(start + 1, next_task):
            if SECTION_HEADER.match(lines[index]):
                end = index
                break
        blocks.append((task_id, checked, start, end))
    return blocks


def run() -> int:
    args = parse_args()
    try:
        path = tasklist_path(args.ticket)
        lines = path.read_text(encoding=TEXT_ENCODING).splitlines()
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2

    blocks = task_blocks(lines)
    if args.repair_status:
        print(format_repair_budget(repair_budget_status("\n".join(lines))))
        return 0

    if args.pending:
        for task_id, checked, _, _ in blocks:
            if not checked:
                print(task_id)
        return 0

    requested = (args.task or "").upper()
    for task_id, _, start, end in blocks:
        if task_id == requested:
            print("\n".join(lines[start:end]).rstrip())
            return 0

    print(f"Task not found: {requested}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(run())
