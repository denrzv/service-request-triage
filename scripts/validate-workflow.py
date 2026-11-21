#!/usr/bin/env python3
"""Validate AIDD workflow artifacts for the active ticket."""
from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import dataclass, asdict
from pathlib import Path

from aidd_common import cache_workflow_gates, workflow_adr_paths
from aidd_verification import current_result

WORD_PLACEHOLDER_RE = re.compile(r"\b(TBD|TODO|FIXME|CHANGE_ME)\b", re.IGNORECASE)
ANGLE_PLACEHOLDER_RE = re.compile(
    r"""
    <(?:
        [A-Z][A-Z0-9_-]{1,79}
        |
        (?i:insert|enter|replace|describe|fill|add)\b[^>\n]{0,72}
        |
        [^>\n]{0,72}\b(?i:here|placeholder)\b[^>\n]{0,72}
    )>
    """,
    re.VERBOSE,
)
INLINE_CODE_RE = re.compile(r"`+[^`\n]*`+")
FENCE_MARKERS = ("```", "~~~")
QUALITY_GATE_FIELDS = (
    "Review",
    "QA",
    "Security review",
    "Docs update",
    "Contract review",
    "DB review",
    "Reliability review",
)
ALWAYS_REQUIRED_GATES = {"Review", "QA"}
GATE_POLICY_VERSION = "1"
GATE_DECISIONS = {"required", "not-required"}
ACCEPTED_ADR_STATUS = "ACCEPTED"
ADR_GATE_LABEL = "ADR decision gate"
ADR_GATE_PATH = "docs/adr/"
FINAL_GATE_ARTIFACTS = {
    "Review": ("reports/review/{ticket}.md", {"REVIEW_APPROVED"}),
    "QA": ("reports/qa/{ticket}.md", {"QA_PASSED"}),
    "Security review": (
        "reports/security/{ticket}.md",
        {"SECURITY_PASS", "SECURITY_PASS_WITH_NOTES"},
    ),
    "Docs update": ("reports/docs/{ticket}.md", {"DOCS_COMPLETE"}),
    "Contract review": ("reports/review/{ticket}-contracts.md", {"CONTRACT_PASS"}),
    "DB review": ("reports/review/{ticket}-db.md", {"DB_PASS"}),
    "Reliability review": (
        "reports/review/{ticket}-reliability.md",
        {"RELIABILITY_PASS"},
    ),
}
FINAL_PRE_COMMIT_ARTIFACT = (
    "reports/pre-commit/{ticket}.md",
    {"PRE_COMMIT_PASSED"},
)
FINAL_CONTROLLER_STATES = {"VERIFYING", "READY_FOR_USER_COMMIT"}
STATE_RELATIVE_PATH = Path(".aidd/runtime/features")
TEMPLATE_MARKERS = (
    "Describe the current problem",
    "Summarize the technical approach",
    "Describe the target flow",
    "Short task title",
    "As a user/system",
    "`path/to/file`: reason",
    "Set status to `QA_PASSED`",
)

@dataclass
class Check:
    name: str
    path: str
    ok: bool
    message: str
    status: str | None = None


def repo_root() -> Path:
    env_root = os.environ.get("CLAUDE_PROJECT_DIR")
    if env_root:
        return Path(env_root).resolve()
    return Path(__file__).resolve().parents[1]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def active_ticket(root: Path, explicit: str | None) -> str | None:
    if explicit:
        return explicit.strip()
    active = root / "docs" / ".active_ticket"
    if not active.exists():
        return None
    lines = active.read_text(encoding="utf-8").splitlines()
    return lines[0].strip() if lines else None


def extract_status(text: str) -> str | None:
    match = re.search(r"(?m)^Status:\s*([A-Z_]+)\s*$", text)
    return match.group(1) if match else None


def has_heading(text: str, heading: str) -> bool:
    return re.search(rf"(?m)^##\s+{re.escape(heading)}\s*$", text) is not None


def markdown_prose(text: str) -> str:
    prose_lines = []
    active_fence = None
    for line in text.splitlines():
        stripped = line.lstrip()
        fence = next((marker for marker in FENCE_MARKERS if stripped.startswith(marker)), None)
        if active_fence:
            if fence == active_fence:
                active_fence = None
            prose_lines.append("")
            continue
        if fence:
            active_fence = fence
            prose_lines.append("")
            continue
        prose_lines.append(INLINE_CODE_RE.sub("", line))
    return "\n".join(prose_lines)


def contains_placeholder(text: str) -> bool:
    prose = markdown_prose(text)
    return (
        WORD_PLACEHOLDER_RE.search(prose) is not None
        or ANGLE_PLACEHOLDER_RE.search(prose) is not None
    )


def ready_artifact(root: Path, label: str, rel_path: str, expected_status: str, required_headings: list[str]) -> Check:
    path = root / rel_path
    if not path.exists():
        return Check(label, rel_path, False, f"Missing artifact: {rel_path}")
    text = read(path)
    status = extract_status(text)
    if status != expected_status:
        return Check(label, rel_path, False, f"Expected Status: {expected_status}, found: {status or 'missing'}", status)
    missing = [h for h in required_headings if not has_heading(text, h)]
    if missing:
        return Check(label, rel_path, False, f"Missing required headings: {', '.join(missing)}", status)
    if contains_placeholder(text) or any(marker in text for marker in TEMPLATE_MARKERS):
        return Check(label, rel_path, False, "Artifact is marked ready but still contains template placeholders or TODO/TBD markers", status)
    return Check(label, rel_path, True, "OK", status)


def optional_artifact(root: Path, label: str, rel_path: str, expected_statuses: set[str]) -> Check:
    path = root / rel_path
    if not path.exists():
        return Check(label, rel_path, False, f"Optional artifact missing: {rel_path}")
    text = read(path)
    status = extract_status(text)
    if status not in expected_statuses:
        return Check(label, rel_path, False, f"Expected one of {sorted(expected_statuses)}, found: {status or 'missing'}", status)
    return Check(label, rel_path, True, "OK", status)


def adr_decision_check(root: Path, ticket: str) -> Check:
    relative_paths = workflow_adr_paths(root, ticket)
    if not relative_paths:
        return Check(
            ADR_GATE_LABEL,
            ADR_GATE_PATH,
            True,
            "No ticket-owned or plan-linked ADRs",
        )

    missing: list[str] = []
    unresolved: list[str] = []
    for relative_path in relative_paths:
        path = root / relative_path
        try:
            status = extract_status(read(path))
        except OSError:
            missing.append(relative_path.as_posix())
            continue
        if status != ACCEPTED_ADR_STATUS:
            unresolved.append(
                f"{relative_path.as_posix()}={status or 'missing status'}"
            )

    if missing:
        return Check(
            ADR_GATE_LABEL,
            ADR_GATE_PATH,
            False,
            "Missing plan-linked ADRs: " + ", ".join(missing),
        )
    if unresolved:
        return Check(
            ADR_GATE_LABEL,
            ADR_GATE_PATH,
            False,
            "Every ticket-owned or plan-linked ADR must be ACCEPTED before "
            "implementation: " + ", ".join(unresolved),
        )
    return Check(
        ADR_GATE_LABEL,
        ADR_GATE_PATH,
        True,
        f"All {len(relative_paths)} ticket-owned or plan-linked ADRs are ACCEPTED",
    )


def required_pass_artifact(
    root: Path,
    label: str,
    rel_path: str,
    expected_statuses: set[str],
) -> Check:
    path = root / rel_path
    if not path.exists():
        return Check(label, rel_path, False, f"Required final artifact missing: {rel_path}")
    text = read(path)
    status = extract_status(text)
    if status not in expected_statuses:
        return Check(
            label,
            rel_path,
            False,
            f"Expected one of {sorted(expected_statuses)}, found: {status or 'missing'}",
            status,
        )
    if re.search(r"(?im)^\s*-\s*Status:\s*Unresolved\s*$", text):
        return Check(
            label,
            rel_path,
            False,
            "Pass status conflicts with an unresolved Blocking/Important finding",
            status,
        )
    return Check(label, rel_path, True, "OK", status)


def tasklist_has_tasks(root: Path, ticket: str) -> Check:
    rel = f"docs/tasklist/{ticket}.md"
    path = root / rel
    if not path.exists():
        return Check("Task entries", rel, False, "Tasklist missing")
    text = read(path)
    if not re.search(r"(?m)^- \[[ xX]\] TASK-\d+:", text):
        return Check("Task entries", rel, False, "Tasklist has no '- [ ] TASK-N:' entries")
    return Check("Task entries", rel, True, "OK")


def quality_gate_decisions(root: Path, ticket: str) -> dict[str, str] | None:
    path = root / "docs" / "plan" / f"{ticket}.md"
    if not path.exists():
        return None
    text = read(path)
    if not has_heading(text, "Quality gate impact"):
        return None
    decisions = {}
    for label in QUALITY_GATE_FIELDS:
        match = re.search(rf"(?im)^\s*{re.escape(label)}:\s*([a-z-]+)\s*$", text)
        if match and match.group(1).lower() in GATE_DECISIONS:
            decisions[label] = match.group(1).lower()
    return decisions if len(decisions) == len(QUALITY_GATE_FIELDS) else None


def completed_tasks_check(root: Path, ticket: str) -> Check:
    rel = f"docs/tasklist/{ticket}.md"
    path = root / rel
    if not path.exists():
        return Check("Final task completion", rel, False, "Tasklist missing")
    entries = re.findall(
        r"(?m)^- \[([ xX])\] (TASK-(?:FIX-)?\d+):",
        read(path),
    )
    if not entries:
        return Check("Final task completion", rel, False, "No regular or fix tasks found")
    unchecked = [task_id for marker, task_id in entries if marker.lower() != "x"]
    if unchecked:
        return Check(
            "Final task completion",
            rel,
            False,
            "Incomplete tasks: " + ", ".join(unchecked),
        )
    return Check(
        "Final task completion",
        rel,
        True,
        f"All {len(entries)} regular and fix tasks are complete",
    )


def final_quality_gate_checks(root: Path, ticket: str) -> list[Check]:
    decisions = quality_gate_decisions(root, ticket)
    required_labels = set(ALWAYS_REQUIRED_GATES)
    if decisions is not None:
        required_labels.update(
            label for label, decision in decisions.items() if decision == "required"
        )

    checks = []
    for label in QUALITY_GATE_FIELDS:
        if label not in required_labels:
            continue
        rel_template, expected_statuses = FINAL_GATE_ARTIFACTS[label]
        rel = rel_template.format(ticket=ticket)
        checks.append(required_pass_artifact(
            root,
            f"Final {label}",
            rel,
            expected_statuses,
        ))

    if decisions is None:
        checks.append(Check(
            "Final conditional gates",
            f"docs/plan/{ticket}.md",
            True,
            "Legacy plan: Review and QA required; conditional gates cannot be derived",
        ))
    else:
        rel_template, expected_statuses = FINAL_PRE_COMMIT_ARTIFACT
        rel = rel_template.format(ticket=ticket)
        checks.append(required_pass_artifact(
            root,
            "Final pre-commit",
            rel,
            expected_statuses,
        ))
    return checks


def deterministic_evidence_check(root: Path, ticket: str) -> Check:
    rel = (
        STATE_RELATIVE_PATH
        / ticket
        / "verification"
        / "latest.json"
    ).as_posix()
    try:
        ok, message = current_result(root, ticket)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        return Check("Fresh deterministic evidence", rel, False, str(exc))
    return Check("Fresh deterministic evidence", rel, ok, message)


def controller_state_check(root: Path, ticket: str) -> Check:
    rel = (STATE_RELATIVE_PATH / ticket / "state.json").as_posix()
    path = root / rel
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return Check("Feature controller state", rel, False, str(exc))
    status = str(state.get("status") or "missing")
    state_ticket = str(state.get("ticket") or "")
    if state_ticket != ticket:
        return Check(
            "Feature controller state",
            rel,
            False,
            f"State ticket {state_ticket or 'missing'} does not match {ticket}",
            status,
        )
    if status not in FINAL_CONTROLLER_STATES:
        return Check(
            "Feature controller state",
            rel,
            False,
            f"Expected one of {sorted(FINAL_CONTROLLER_STATES)}, found: {status}",
            status,
        )
    return Check("Feature controller state", rel, True, "OK", status)


def quality_gate_policy_check(root: Path, ticket: str) -> Check:
    rel = f"docs/plan/{ticket}.md"
    path = root / rel
    if not path.exists():
        return Check("Quality gate policy", rel, False, "Plan missing")
    text = read(path)
    if not has_heading(text, "Quality gate impact"):
        return Check(
            "Quality gate policy",
            rel,
            True,
            "Legacy plan without a versioned quality gate policy",
        )
    version_match = re.search(r"(?im)^\s*Gate policy version:\s*(\d+)\s*$", text)
    if not version_match or version_match.group(1) != GATE_POLICY_VERSION:
        return Check("Quality gate policy", rel, False, "Expected Gate policy version: 1")

    decisions = {}
    missing_or_invalid = []
    missing_rationales = []
    for label in QUALITY_GATE_FIELDS:
        decision_match = re.search(
            rf"(?im)^\s*{re.escape(label)}:\s*([a-z-]+)\s*$",
            text,
        )
        decision = decision_match.group(1).lower() if decision_match else None
        if decision not in GATE_DECISIONS:
            missing_or_invalid.append(label)
        else:
            decisions[label] = decision
        rationale_match = re.search(
            rf"(?im)^\s*-\s*{re.escape(label)}:\s*(\S.+?)\s*$",
            text,
        )
        if not rationale_match:
            missing_rationales.append(label)

    if missing_or_invalid:
        return Check(
            "Quality gate policy",
            rel,
            False,
            "Missing or invalid required/not-required decisions: " + ", ".join(missing_or_invalid),
        )
    incorrectly_optional = [
        label for label in ALWAYS_REQUIRED_GATES
        if decisions.get(label) != "required"
    ]
    if incorrectly_optional:
        return Check(
            "Quality gate policy",
            rel,
            False,
            "Always-required gates marked not-required: " + ", ".join(incorrectly_optional),
        )
    if missing_rationales:
        return Check(
            "Quality gate policy",
            rel,
            False,
            "Missing gate rationales: " + ", ".join(missing_rationales),
        )
    required = sum(decision == "required" for decision in decisions.values())
    return Check(
        "Quality gate policy",
        rel,
        True,
        f"Policy v1: {required} required, {len(decisions) - required} not-required",
    )


def diagram_policy_check(root: Path, ticket: str) -> Check:
    rel = f"docs/plan/{ticket}.md"
    path = root / rel
    if not path.exists():
        return Check("Diagram policy", rel, False, "Plan missing")
    text = read(path)
    if not has_heading(text, "Diagram impact"):
        return Check("Diagram policy", rel, False, "Missing required heading: Diagram impact")
    match = re.search(r"(?im)^\s*Render diagrams required:\s*(yes|no)\s*$", text)
    if not match:
        return Check("Diagram policy", rel, False, "Missing 'Render diagrams required: yes/no'")

    render_required = match.group(1).lower() == "yes"
    rows = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|") or "---" in stripped:
            continue
        cells = [c.strip().strip("` ") for c in stripped.strip("|").split("|")]
        if len(cells) < 2 or cells[0].lower() == "diagram":
            continue
        name = cells[0].lower()
        if not any(token in name for token in ["system context", "container", "erd"]):
            continue
        rows.append((cells[0], cells[1].lower()))

    if not rows:
        return Check("Diagram policy", rel, False, "Diagram impact table must include System Context C4, Container C4, and ERD rows")

    allowed = {"not changed", "changed", "new", "removed", "not applicable"}
    invalid = [f"{name}={impact}" for name, impact in rows if impact not in allowed]
    if invalid:
        return Check("Diagram policy", rel, False, "Invalid diagram impact values: " + ", ".join(invalid))

    changed = [name for name, impact in rows if impact in {"changed", "new", "removed"}]
    if render_required and not changed:
        return Check("Diagram policy", rel, False, "Render diagrams required is yes, but no diagram row is changed/new/removed")
    if not render_required and changed:
        return Check("Diagram policy", rel, False, "Render diagrams required is no, but some diagram rows are changed/new/removed: " + ", ".join(changed))

    return Check("Diagram policy", rel, True, "OK")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--json", action="store_true", dest="as_json")
    parser.add_argument(
        "--final",
        action="store_true",
        help="Require completed tasks and pass artifacts for every required quality gate",
    )
    parser.add_argument("--require-research", action="store_true", default=os.getenv("AIDD_REQUIRE_RESEARCH", "").lower() in {"1", "true", "yes"})
    args = parser.parse_args()

    root = repo_root()
    ticket = active_ticket(root, args.ticket)
    checks: list[Check] = []

    if not ticket:
        checks.append(Check("Active ticket", "docs/.active_ticket", False, "Missing or empty active ticket"))
    else:
        checks.append(Check("Active ticket", "docs/.active_ticket", True, f"Active ticket: {ticket}"))
        checks.extend([
            ready_artifact(root, "PRD gate", f"docs/prd/{ticket}.prd.md", "PRD_READY", ["Context / Problem", "Goals", "Non-goals", "Acceptance Criteria", "Open Questions"]),
            ready_artifact(root, "Plan gate", f"docs/plan/{ticket}.md", "PLAN_APPROVED", ["Overview", "Architecture / Flow", "Diagram impact", "Reliability", "Security & Privacy", "Rollout plan", "Testing strategy"]),
            adr_decision_check(root, ticket),
            ready_artifact(root, "Tasklist gate", f"docs/tasklist/{ticket}.md", "TASKLIST_READY", ["Tasks"]),
            tasklist_has_tasks(root, ticket),
            diagram_policy_check(root, ticket),
            quality_gate_policy_check(root, ticket),
        ])
        research = optional_artifact(root, "Research", f"docs/research/{ticket}.md", {"RESEARCH_READY"})
        if args.require_research:
            checks.append(research)
        if args.final:
            checks.append(completed_tasks_check(root, ticket))
            checks.extend(final_quality_gate_checks(root, ticket))
            checks.append(deterministic_evidence_check(root, ticket))
            checks.append(controller_state_check(root, ticket))

    ok = all(c.ok for c in checks)

    if ok and ticket:
        cache_workflow_gates(root, ticket, args.require_research)

    if args.as_json:
        print(json.dumps({"ok": ok, "ticket": ticket, "checks": [asdict(c) for c in checks]}, indent=2))
    elif not args.quiet:
        print(f"[AIDD] Workflow validation for ticket: {ticket or '<missing>'}")
        for c in checks:
            marker = "OK" if c.ok else "FAIL"
            print(f"[{marker}] {c.name}: {c.message} ({c.path})")

    return 0 if ok else 1

if __name__ == "__main__":
    raise SystemExit(main())
