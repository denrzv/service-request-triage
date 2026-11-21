"""Shared adaptive repair-budget calculation for AIDD tasklists."""
from __future__ import annotations

import re
from dataclasses import dataclass

BASE_REPAIR_BUDGET = 12
REGULAR_TASK_THRESHOLD = 20
REGULAR_TASKS_PER_EXTRA_SLOT = 4
MAX_REPAIR_BUDGET = 18
FIX_TASK_PREFIX = "TASK-FIX-"
REPAIR_BUDGET_OVERRIDE = re.compile(
    r"(?im)^Repair budget override:\s*(?P<value>\d+)\s*$"
)
TASK_ID_PATTERN = r"TASK-(?:FIX-)?\d+"
TASK_HEADER = re.compile(
    rf"(?m)^- \[(?P<state>[ xX])\] (?P<id>{TASK_ID_PATTERN}):"
)


@dataclass(frozen=True)
class RepairBudgetStatus:
    regular_tasks: int
    fix_tasks: int
    default_budget: int
    override: int | None
    repair_budget: int
    remaining: int
    overage: int


def adaptive_repair_budget(regular_tasks: int) -> int:
    tasks_above_threshold = max(0, regular_tasks - REGULAR_TASK_THRESHOLD)
    extra_slots = (
        tasks_above_threshold + REGULAR_TASKS_PER_EXTRA_SLOT - 1
    ) // REGULAR_TASKS_PER_EXTRA_SLOT
    return min(MAX_REPAIR_BUDGET, BASE_REPAIR_BUDGET + extra_slots)


def repair_budget_status(text: str) -> RepairBudgetStatus:
    task_ids = [match.group("id") for match in TASK_HEADER.finditer(text)]
    fix_tasks = sum(task_id.startswith(FIX_TASK_PREFIX) for task_id in task_ids)
    regular_tasks = len(task_ids) - fix_tasks
    default_budget = adaptive_repair_budget(regular_tasks)
    override_match = REPAIR_BUDGET_OVERRIDE.search(text)
    override = int(override_match.group("value")) if override_match else None
    repair_budget = max(default_budget, override or default_budget)
    return RepairBudgetStatus(
        regular_tasks=regular_tasks,
        fix_tasks=fix_tasks,
        default_budget=default_budget,
        override=override,
        repair_budget=repair_budget,
        remaining=max(0, repair_budget - fix_tasks),
        overage=max(0, fix_tasks - repair_budget),
    )


def format_repair_budget(status: RepairBudgetStatus) -> str:
    capacity = (
        f"OVER LIMIT by {status.overage}"
        if status.overage
        else f"{status.remaining} remaining"
    )
    override = (
        f"; explicit override from default {status.default_budget}"
        if status.override is not None and status.repair_budget > status.default_budget
        else ""
    )
    return (
        f"Repair budget: {status.fix_tasks}/{status.repair_budget} used; "
        f"{capacity} ({status.regular_tasks} regular tasks{override})."
    )
