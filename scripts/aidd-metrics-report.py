#!/usr/bin/env python3
"""Render a compact report from local AIDD JSONL metrics."""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from aidd_task_budget import format_repair_budget, repair_budget_status
from statistics import median

TEXT_ENCODING = "utf-8"
DEFAULT_EVENTS_PATH = Path(".aidd/metrics/events.jsonl")
ACTIVE_TICKET_PATH = Path("docs/.active_ticket")
READ_TOOLS = {"Read"}
SEARCH_TOOLS = {"Glob", "Grep"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
PASS_OUTCOMES = {
    "VERIFICATION_PASS",
    "REVIEW_APPROVED",
    "QA_PASSED",
    "SECURITY_PASS",
    "SECURITY_PASS_WITH_NOTES",
    "DOCS_COMPLETE",
    "CONTRACT_PASS",
    "DB_PASS",
    "RELIABILITY_PASS",
}
QUALITY_PHASES = {
    "verify",
    "review",
    "qa",
    "security-review",
    "docs-update",
    "contract-review",
    "db-review",
    "reliability-review",
}
OUTCOME_PREFIX_BY_PHASE = {
    "verify": "VERIFICATION_",
    "review": "REVIEW_",
    "qa": "QA_",
    "security-review": "SECURITY_",
    "docs-update": "DOCS_",
    "contract-review": "CONTRACT_",
    "db-review": "DB_",
    "reliability-review": "RELIABILITY_",
}
PHASE_BOUNDARY_EVENTS = {"UserPromptExpansion", "SessionEnd"}
STOP_MISSING = "STOP_MISSING"
SUPERSEDED = "SUPERSEDED"
OPEN_ENDED_RESULTS = {STOP_MISSING, SUPERSEDED}
COMMAND_TASK_WINDOW_MS = 60_000
GATE_POLICY_VERSION = "1"
REQUIRED_DECISION = "required"
NOT_REQUIRED_DECISION = "not-required"
PRE_COMMIT_REPORT_TEMPLATE = "reports/pre-commit/{ticket}.md"
PRE_COMMIT_PASSED_STATUS = "PRE_COMMIT_PASSED"
GATE_POLICY_FIELDS = (
    ("review", "Review"),
    ("qa", "QA"),
    ("security-review", "Security review"),
    ("docs-update", "Docs update"),
    ("contract-review", "Contract review"),
    ("db-review", "DB review"),
    ("reliability-review", "Reliability review"),
)


def active_ticket(root: Path) -> str | None:
    try:
        lines = (root / ACTIVE_TICKET_PATH).read_text(encoding=TEXT_ENCODING).splitlines()
    except OSError:
        return None
    return lines[0].strip() if lines else None


def load_events(path: Path) -> list[dict]:
    events = []
    try:
        lines = path.read_text(encoding=TEXT_ENCODING).splitlines()
    except OSError:
        return events
    for line in lines:
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            events.append(value)
    return events


def empty_tool_stats() -> dict:
    return {
        "calls": 0,
        "duration_ms": 0,
        "reads": 0,
        "searches": 0,
        "edits": 0,
        "bash": 0,
        "failures": 0,
        "permissions": 0,
        "bash_categories": Counter(),
        "failure_categories": Counter(),
    }


def update_tool_stats(stats: dict, event: dict) -> None:
    event_name = event.get("event")
    tool = event.get("tool")
    if event_name == "PermissionRequest":
        stats["permissions"] += 1
        return
    stats["calls"] += 1
    stats["duration_ms"] += int(event.get("duration_ms") or 0)
    stats["reads"] += int(tool in READ_TOOLS)
    stats["searches"] += int(tool in SEARCH_TOOLS)
    stats["edits"] += int(tool in EDIT_TOOLS)
    stats["bash"] += int(tool == "Bash")
    if tool == "Bash":
        stats["bash_categories"][str(event.get("category") or "other")] += 1
    if event_name == "PostToolUseFailure":
        stats["failures"] += 1
        failure_category = event.get("category") if tool == "Bash" else tool
        stats["failure_categories"][str(failure_category or "unknown")] += 1


def last_agent_activity(events: list[dict], agent_id: str, start_ms: int | None) -> int | None:
    timestamps = [
        event.get("timestamp_ms") for event in events
        if event.get("agent_id") == agent_id and isinstance(event.get("timestamp_ms"), int)
    ]
    return max(timestamps) if timestamps else start_ms


def has_later_phase_boundary(events: list[dict], start: dict) -> bool:
    start_ms = start.get("timestamp_ms")
    session_id = start.get("session_id")
    if not isinstance(start_ms, int) or not session_id:
        return False
    return any(
        event.get("session_id") == session_id
        and event.get("event") in PHASE_BOUNDARY_EVENTS
        and isinstance(event.get("timestamp_ms"), int)
        and event["timestamp_ms"] > start_ms
        for event in events
    )


def has_later_same_phase_start(starts: dict, start: dict) -> bool:
    start_ms = start.get("timestamp_ms")
    if not isinstance(start_ms, int):
        return False
    return any(
        candidate is not start
        and candidate.get("session_id") == start.get("session_id")
        and candidate.get("phase") == start.get("phase")
        and isinstance(candidate.get("timestamp_ms"), int)
        and candidate["timestamp_ms"] > start_ms
        for candidate in starts.values()
    )


def nearest_implement_command(events: list[dict], start: dict) -> dict | None:
    start_ms = start.get("timestamp_ms")
    if not isinstance(start_ms, int):
        return None
    candidates = [
        event for event in events
        if event.get("event") == "UserPromptExpansion"
        and event.get("phase") == "implement"
        and event.get("task")
        and event.get("session_id") == start.get("session_id")
        and event.get("ticket") == start.get("ticket")
        and isinstance(event.get("timestamp_ms"), int)
        and 0 <= start_ms - event["timestamp_ms"] <= COMMAND_TASK_WINDOW_MS
    ]
    return max(candidates, key=lambda event: event["timestamp_ms"]) if candidates else None


def resolve_implement_task(events: list[dict], start: dict, stop: dict | None) -> dict:
    command = nearest_implement_command(events, start)
    command_task = command.get("task") if command else None
    stop_task = stop.get("task") if stop else None
    stop_source = stop.get("task_source") if stop else None
    if command_task:
        return {
            "task": command_task,
            "task_source": "command",
            "task_mismatch": stop_task if stop_task and stop_task != command_task else None,
            "stop_task_source": stop_source or "legacy-final-text" if stop_task else None,
        }
    return {
        "task": stop_task,
        "task_source": stop_source or "legacy-final-text" if stop_task else None,
        "task_mismatch": None,
        "stop_task_source": stop_source,
    }


def mark_ambiguous_legacy_tasks(runs: list[dict]) -> None:
    task_counts = Counter(
        run["task"] for run in runs
        if run.get("phase") == "implement" and run.get("task")
    )
    for run in runs:
        run["task_ambiguous"] = bool(
            run.get("phase") == "implement"
            and run.get("task")
            and task_counts[run["task"]] > 1
            and run.get("task_source") == "legacy-final-text"
        )


def phase_runs(events: list[dict], ticket: str | None) -> list[dict]:
    starts = {}
    stops = {}
    tools = defaultdict(empty_tool_stats)
    for event in events:
        agent_id = event.get("agent_id")
        event_name = event.get("event")
        if event_name == "SubagentStart" and agent_id:
            starts[agent_id] = event
        elif event_name == "SubagentStop" and agent_id:
            stops[agent_id] = event
        elif agent_id and event_name in {"PostToolUse", "PostToolUseFailure", "PermissionRequest"}:
            update_tool_stats(tools[agent_id], event)

    runs = []
    for agent_id, start in starts.items():
        if ticket and start.get("ticket") != ticket:
            continue
        stop = stops.get(agent_id)
        end_ms = stop.get("timestamp_ms") if stop else None
        start_ms = start.get("timestamp_ms")
        wall_ms = max(0, end_ms - start_ms) if isinstance(end_ms, int) and isinstance(start_ms, int) else None
        last_activity_ms = last_agent_activity(events, agent_id, start_ms)
        observed_wall_ms = (
            max(0, last_activity_ms - start_ms)
            if isinstance(last_activity_ms, int) and isinstance(start_ms, int)
            else None
        )
        stop_missing = stop is None and has_later_phase_boundary(events, start)
        superseded = stop is None and has_later_same_phase_start(starts, start)
        phase = start.get("phase") or "unknown"
        raw_outcome = stop.get("outcome") if stop else None
        outcome = raw_outcome if phase in QUALITY_PHASES else None
        unexpected_outcome = raw_outcome if raw_outcome and phase not in QUALITY_PHASES else None
        task_context = resolve_implement_task(events, start, stop) if phase == "implement" else {}
        runs.append({
            "phase": phase,
            "agent": start.get("agent_type") or "unknown",
            "task": None,
            "start_ms": start_ms,
            "wall_ms": wall_ms,
            "observed_wall_ms": observed_wall_ms,
            "last_activity_ms": last_activity_ms,
            "result": (
                stop.get("result") if stop
                else SUPERSEDED if superseded
                else STOP_MISSING if stop_missing
                else "RUNNING"
            ),
            "outcome": outcome,
            "unexpected_outcome": unexpected_outcome,
            "agent_id": agent_id,
            "session_id": start.get("session_id"),
            **task_context,
            **tools[agent_id],
        })
    ordered_runs = sorted(runs, key=lambda run: run["start_ms"] or 0)
    mark_ambiguous_legacy_tasks(ordered_runs)
    return ordered_runs


def format_duration(duration_ms: int | None) -> str:
    if duration_ms is None:
        return "running"
    if duration_ms < 1000:
        return f"{duration_ms}ms"
    seconds = duration_ms // 1000
    minutes, remaining_seconds = divmod(seconds, 60)
    return f"{minutes}m {remaining_seconds:02d}s" if minutes else f"{remaining_seconds}s"


def non_tool_duration(run: dict) -> int | None:
    wall_ms = run.get("wall_ms")
    if wall_ms is None:
        return None
    return max(0, wall_ms - int(run.get("duration_ms") or 0))


def wall_display(run: dict) -> str:
    if run.get("result") in OPEN_ENDED_RESULTS:
        return f">= {format_duration(run.get('observed_wall_ms'))}"
    return format_duration(run.get("wall_ms"))


def non_tool_display(run: dict) -> str:
    if run.get("result") in OPEN_ENDED_RESULTS:
        observed_ms = run.get("observed_wall_ms")
        if observed_ms is None:
            return "unknown"
        observed_non_tool_ms = max(0, observed_ms - int(run.get("duration_ms") or 0))
        return f">= {format_duration(observed_non_tool_ms)}"
    return format_duration(non_tool_duration(run))


def tool_ratio(run: dict) -> str:
    wall_ms = run.get("wall_ms")
    if not wall_ms:
        return "-"
    ratio = int(run.get("duration_ms") or 0) / wall_ms * 100
    return f"{ratio:.0f}%"


def has_valid_quality_outcome(run: dict) -> bool:
    outcome = run.get("outcome")
    expected_prefix = OUTCOME_PREFIX_BY_PHASE.get(run.get("phase"))
    return bool(outcome and expected_prefix and outcome.startswith(expected_prefix))


def format_categories(categories: Counter) -> str:
    if not categories:
        return "-"
    return ", ".join(f"{name}:{count}" for name, count in sorted(categories.items()))


def task_display(run: dict) -> str:
    task = run.get("task")
    if not task:
        return "-"
    return "UNKNOWN" if run.get("task_ambiguous") else task


def print_run_table(runs: list[dict]) -> None:
    print("| Phase | Task | Wall | Tool | Non-tool | Tool % | Calls | R/S/E/B | Fail | Perm | Result | Outcome |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|")
    for run in runs:
        tool_breakdown = f"{run['reads']}/{run['searches']}/{run['edits']}/{run['bash']}"
        print(
            f"| {run['phase']} | {task_display(run)} | {wall_display(run)} | "
            f"{format_duration(run['duration_ms'])} | {non_tool_display(run)} | "
            f"{tool_ratio(run)} | {run['calls']} | {tool_breakdown} | {run['failures']} | "
            f"{run['permissions']} | {run['result'] or '-'} | {run['outcome'] or '-'} |"
        )


def print_summary(runs: list[dict]) -> None:
    completed = [run for run in runs if run.get("wall_ms") is not None]
    if not completed:
        return
    total_wall = sum(run["wall_ms"] for run in completed)
    total_tool = sum(run["duration_ms"] for run in completed)
    total_calls = sum(run["calls"] for run in completed)
    median_wall = int(median(run["wall_ms"] for run in completed))
    median_tool = int(median(run["duration_ms"] for run in completed))
    median_calls = median(run["calls"] for run in completed)
    total_ratio = total_tool / total_wall * 100 if total_wall else 0
    print(
        "\nSummary: "
        f"total wall {format_duration(total_wall)}, tool {format_duration(total_tool)}, "
        f"non-tool {format_duration(max(0, total_wall - total_tool))}, tool ratio {total_ratio:.0f}%, "
        f"calls {total_calls}."
    )
    print(
        "Median per phase: "
        f"wall {format_duration(median_wall)}, tool {format_duration(median_tool)}, calls {median_calls:g}."
    )


def load_gate_policy(root: Path, ticket: str | None) -> dict[str, str] | None:
    if not ticket:
        return None
    path = root / "docs" / "plan" / f"{ticket}.md"
    try:
        text = path.read_text(encoding=TEXT_ENCODING)
    except OSError:
        return None
    version_match = re.search(r"(?im)^\s*Gate policy version:\s*(\d+)\s*$", text)
    if not version_match or version_match.group(1) != GATE_POLICY_VERSION:
        return None
    decisions = {}
    for phase, label in GATE_POLICY_FIELDS:
        match = re.search(
            rf"(?im)^\s*{re.escape(label)}:\s*(required|not-required)\s*$",
            text,
        )
        decisions[phase] = match.group(1).lower() if match else "invalid"
    return decisions


def gate_run_passed(phase: str, run: dict) -> bool:
    if phase in QUALITY_PHASES:
        return has_valid_quality_outcome(run) and run.get("outcome") in PASS_OUTCOMES
    return run.get("result") == "COMPLETE"


def completed_gate_runs(
    runs: list[dict],
    phase: str,
    include_consolidated_verifier: bool = False,
) -> list[dict]:
    exact = [
        run for run in runs
        if run.get("phase") == phase and run.get("wall_ms") is not None
    ]
    if exact or not include_consolidated_verifier:
        return exact
    return [
        run for run in runs
        if run.get("phase") == "verify" and run.get("wall_ms") is not None
    ]


def print_legacy_quality_summary(runs: list[dict]) -> None:
    quality_phases = [
        run for run in runs
        if run.get("wall_ms") is not None and run.get("phase") in QUALITY_PHASES
    ]
    quality_outcomes = [run for run in quality_phases if has_valid_quality_outcome(run)]
    if quality_outcomes:
        passed = sum(run["outcome"] in PASS_OUTCOMES for run in quality_outcomes)
        print(f"Recorded quality outcomes: {passed}/{len(quality_outcomes)} passed.")
    missing_outcomes = len(quality_phases) - len(quality_outcomes)
    if missing_outcomes:
        print(f"Quality phases without valid outcome: {missing_outcomes}.")
    print("Gate policy: legacy plan; expected conditional phases are not declared.")


def print_gate_summary(root: Path, ticket: str | None, runs: list[dict]) -> None:
    policy = load_gate_policy(root, ticket)
    if policy is None:
        print_legacy_quality_summary(runs)
        return
    invalid = [phase for phase, decision in policy.items() if decision == "invalid"]
    if invalid:
        print("Gate policy: invalid or incomplete entries: " + ", ".join(invalid) + ".")
        return

    required = [phase for phase, decision in policy.items() if decision == REQUIRED_DECISION]
    optional = [phase for phase, decision in policy.items() if decision == NOT_REQUIRED_DECISION]
    passed = []
    missing = []
    not_passed = []
    optional_run = []
    skipped = []
    for phase in required:
        gate_runs = completed_gate_runs(
            runs,
            phase,
            include_consolidated_verifier=True,
        )
        if not gate_runs:
            missing.append(phase)
        elif any(gate_run_passed(phase, run) for run in gate_runs):
            passed.append(phase)
        else:
            not_passed.append(phase)
    for phase in optional:
        gate_runs = completed_gate_runs(runs, phase)
        if gate_runs:
            optional_run.append(phase)
        else:
            skipped.append(phase)

    print(f"Required gates: {len(passed)}/{len(required)} passed.")
    if missing:
        print("Missing required gates: " + ", ".join(missing) + ".")
    if not_passed:
        print("Required gates not passed: " + ", ".join(not_passed) + ".")
    if skipped:
        print("Skipped by plan: " + ", ".join(skipped) + ".")
    if optional_run:
        print("Optional gates run: " + ", ".join(optional_run) + ".")

    pre_commit_path = root / PRE_COMMIT_REPORT_TEMPLATE.format(ticket=ticket)
    try:
        pre_commit_text = pre_commit_path.read_text(encoding=TEXT_ENCODING)
    except OSError:
        print("Pre-commit gate: missing report.")
        return
    status_match = re.search(r"(?m)^Status:\s*([A-Z_]+)\s*$", pre_commit_text)
    pre_commit_status = status_match.group(1) if status_match else "missing"
    if pre_commit_status == PRE_COMMIT_PASSED_STATUS:
        print("Pre-commit gate: passed.")
    else:
        print(f"Pre-commit gate: not passed ({pre_commit_status}).")


def print_breakdowns(runs: list[dict]) -> None:
    bash_runs = [run for run in runs if run["bash_categories"]]
    if bash_runs:
        print("\n## Bash categories\n")
        print("| Task / phase | Categories |")
        print("|---|---|")
        for run in bash_runs:
            label = task_display(run) if run.get("task") else run["phase"]
            print(f"| {label} | {format_categories(run['bash_categories'])} |")
    failure_runs = [run for run in runs if run["failure_categories"]]
    if failure_runs:
        print("\nFailures: " + "; ".join(
            f"{task_display(run) if run.get('task') else run['phase']} "
            f"({format_categories(run['failure_categories'])})"
            for run in failure_runs
        ))


def nearest_unmatched_stop(events: list[dict], run: dict, start_ids: set[str]) -> dict | None:
    last_activity_ms = run.get("last_activity_ms")
    candidates = [
        event for event in events
        if event.get("event") == "SubagentStop"
        and event.get("agent_id") not in start_ids
        and event.get("session_id") == run.get("session_id")
        and isinstance(event.get("timestamp_ms"), int)
        and isinstance(last_activity_ms, int)
        and event["timestamp_ms"] > last_activity_ms
    ]
    return min(candidates, key=lambda event: event["timestamp_ms"]) if candidates else None


def print_telemetry_warnings(events: list[dict], runs: list[dict]) -> None:
    missing_stops = [run for run in runs if run.get("result") == STOP_MISSING]
    invalid_outcomes = [
        run for run in runs
        if run.get("phase") in QUALITY_PHASES
        and run.get("outcome")
        and not has_valid_quality_outcome(run)
    ]
    unexpected_outcomes = [run for run in runs if run.get("unexpected_outcome")]
    task_mismatches = [run for run in runs if run.get("task_mismatch")]
    ambiguous_tasks = [run for run in runs if run.get("task_ambiguous")]
    unattributed_tasks = [
        run for run in runs
        if run.get("phase") == "implement" and not run.get("task")
    ]
    start_ids = {
        event.get("agent_id") for event in events
        if event.get("event") == "SubagentStart" and event.get("agent_id")
    }
    if not any((
        missing_stops,
        invalid_outcomes,
        unexpected_outcomes,
        task_mismatches,
        ambiguous_tasks,
        unattributed_tasks,
    )):
        return
    print("\n## Telemetry warnings\n")
    for run in missing_stops:
        observed = format_duration(run.get("observed_wall_ms"))
        print(f"- {run['phase']}: SubagentStop missing; observed for at least {observed}.")
        unmatched_stop = nearest_unmatched_stop(events, run, start_ids)
        if unmatched_stop:
            delay_ms = unmatched_stop["timestamp_ms"] - run["last_activity_ms"]
            print(
                "  Nearest unmatched SubagentStop followed "
                f"{format_duration(delay_ms)} later with a different agent id."
            )
    for run in invalid_outcomes:
        expected_prefix = OUTCOME_PREFIX_BY_PHASE[run["phase"]]
        print(
            f"- {run['phase']}: outcome {run['outcome']} is incompatible with this phase; "
            f"expected a {expected_prefix}* marker."
        )
    for run in unexpected_outcomes:
        print(
            f"- {run['phase']}: ignored unexpected quality outcome "
            f"{run['unexpected_outcome']} from a non-quality phase."
        )
    for run in task_mismatches:
        print(
            f"- implement: command identifies {run['task']}, but the final response "
            f"reported {run['task_mismatch']}; using the command task."
        )
    for run in ambiguous_tasks:
        print(
            f"- implement: legacy final-text attribution {run['task']} is duplicated "
            "and has no matching command event; displaying UNKNOWN."
        )
    for run in unattributed_tasks:
        print(
            "- implement: task ID unavailable; no nearby command event or strict "
            "`Task: TASK-N` final header was recorded."
        )


def cache_summary(ticket: str | None, events: list[dict]) -> None:
    cache_events = [
        event for event in events
        if event.get("event") == "gate_cache" and (not ticket or event.get("ticket") == ticket)
    ]
    cache_counts = Counter(str(event.get("status") or "unknown") for event in cache_events)
    attempts = cache_counts["hit"] + cache_counts["miss"]
    hit_rate = cache_counts["hit"] / attempts * 100 if attempts else 0
    print(
        "\nGate cache: "
        f"{cache_counts['hit']} hits / {cache_counts['miss']} misses / "
        f"{cache_counts['refresh']} refreshes; hit rate {hit_rate:.0f}%."
    )


def print_repair_budget(root: Path, ticket: str | None) -> None:
    if not ticket:
        return
    path = root / "docs" / "tasklist" / f"{ticket}.md"
    try:
        text = path.read_text(encoding=TEXT_ENCODING)
    except OSError:
        return
    print("\n" + format_repair_budget(repair_budget_status(text)))


def print_report(root: Path, ticket: str | None, events: list[dict]) -> None:
    runs = phase_runs(events, ticket)
    title = ticket or "all tickets"
    print(f"# AIDD metrics: {title}\n")
    if not runs:
        print("No completed or running subagent phases recorded yet.")
    else:
        print_run_table(runs)
        print_summary(runs)
        print_gate_summary(root, ticket, runs)
        print_repair_budget(root, ticket)
        print_breakdowns(runs)
    print_telemetry_warnings(events, runs)
    cache_summary(ticket, events)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket")
    parser.add_argument("--events", type=Path, default=DEFAULT_EVENTS_PATH)
    args = parser.parse_args()
    root = Path.cwd().resolve()
    ticket = args.ticket or active_ticket(root)
    print_report(root, ticket, load_events(root / args.events))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
