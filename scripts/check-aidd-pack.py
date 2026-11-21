#!/usr/bin/env python3
"""Static integrity check for the central-session AIDD workflow."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from aidd_task_budget import adaptive_repair_budget, repair_budget_status

ROOT = Path(__file__).resolve().parents[1]
AGENT_DIRECTORY = ROOT / ".claude/agents"
SKILL_DIRECTORY = ROOT / ".claude/skills"
SETTINGS_PATH = ROOT / ".claude/settings.json"
FRONTMATTER_PATTERN = re.compile(r"^---\n(.*?)\n---\n", re.S)
REVIEWER_NAME = "reviewer"
FORK_MARKER = "context: fork"
errors: list[str] = []


def validate_yaml_if_available(path: Path, frontmatter: str) -> None:
    try:
        import yaml  # type: ignore
    except Exception:
        return
    try:
        yaml.safe_load(frontmatter)
    except Exception as exc:
        errors.append(f"Invalid YAML frontmatter in {path}: {exc}")


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"Invalid .claude/settings.json: {exc}")
        return {}


def inspect_agents() -> set[str]:
    seen_agents: set[str] = set()
    for path in sorted(AGENT_DIRECTORY.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        match = FRONTMATTER_PATTERN.match(text)
        if not match:
            errors.append(f"Agent missing YAML frontmatter: {path}")
            continue
        frontmatter = match.group(1)
        validate_yaml_if_available(path, frontmatter)
        name = re.search(r"(?m)^name:\s*([a-z0-9-]+)\s*$", frontmatter)
        description = re.search(r"(?m)^description:\s*(.+)$", frontmatter)
        if not name:
            errors.append(f"Agent missing name: {path}")
        else:
            seen_agents.add(name.group(1))
        if not description:
            errors.append(f"Agent missing description: {path}")
    if seen_agents != {REVIEWER_NAME}:
        errors.append(
            "Central workflow must define exactly one subagent, reviewer; "
            f"found {sorted(seen_agents)}"
        )
    return seen_agents


def inspect_skills(seen_agents: set[str]) -> None:
    forked_skills: list[str] = []
    for path in sorted(SKILL_DIRECTORY.glob("*/SKILL.md")):
        text = path.read_text(encoding="utf-8")
        match = FRONTMATTER_PATTERN.match(text)
        if not match:
            errors.append(f"Skill missing YAML frontmatter: {path}")
            continue
        frontmatter = match.group(1)
        validate_yaml_if_available(path, frontmatter)
        if "description:" not in frontmatter:
            errors.append(f"Skill missing description: {path}")
        if FORK_MARKER not in frontmatter:
            continue
        forked_skills.append(path.parent.name)
        agent = re.search(r"(?m)^agent:\s*([A-Za-z0-9:-]+)\s*$", frontmatter)
        if not agent:
            errors.append(f"Forked skill missing agent: {path}")
        elif agent.group(1) not in seen_agents:
            errors.append(f"Skill {path} references unknown agent: {agent.group(1)}")
    if forked_skills != ["review"]:
        errors.append(
            "Only /review may fork in the central workflow; "
            f"found {forked_skills}"
        )


def require_files() -> None:
    required_files = {
        ".aidd/schemas/feature-state.schema.json",
        ".aidd/schemas/verification-result.schema.json",
        "hooks/read_guard.py",
        "scripts/agent-write-guard.py",
        "scripts/aidd-evolution-report.py",
        "scripts/aidd-stop-gate.py",
        "scripts/aidd-task-context.py",
        "scripts/aidd_common.py",
        "scripts/aidd_state.py",
        "scripts/aidd_task_budget.py",
        "scripts/aidd_verification.py",
        "scripts/bash-command-guard.py",
        "scripts/diagram-impact-check.py",
        "scripts/diagram-write-guard.py",
        "scripts/gate-code-write.py",
        "scripts/render-diagrams.sh",
        "scripts/test-aidd-verification.py",
        "scripts/test-validate-workflow.py",
        "scripts/validate-workflow.py",
    }
    for relative in sorted(required_files):
        if not (ROOT / relative).exists():
            errors.append(f"Missing required file: {relative}")

    required_skills = {
        "bugfix",
        "contract-review",
        "db-review",
        "docs-update",
        "evolution-review",
        "feature-development",
        "fix-tasks",
        "gate-policy",
        "idea",
        "implement",
        "implement-deep",
        "plan",
        "pre-commit",
        "qa",
        "reliability-review",
        "researcher",
        "review",
        "review-deep",
        "security-review",
        "small-change",
        "tasks",
        "validate",
    }
    missing = sorted(
        name for name in required_skills
        if not (SKILL_DIRECTORY / name / "SKILL.md").exists()
    )
    if missing:
        errors.append(f"Missing lifecycle skills: {missing}")


def check_repair_budget() -> None:
    examples = {
        0: 12,
        20: 12,
        21: 13,
        24: 13,
        25: 14,
        29: 15,
        41: 18,
        100: 18,
    }
    for regular_tasks, expected_budget in examples.items():
        actual_budget = adaptive_repair_budget(regular_tasks)
        if actual_budget != expected_budget:
            errors.append(
                "Adaptive repair budget mismatch: "
                f"{regular_tasks} produced {actual_budget}, expected {expected_budget}"
            )

    override_fixture = (
        "Repair budget override: 19\n"
        + "\n".join(f"- [ ] TASK-{task_id}: Test" for task_id in range(1, 30))
        + "\n"
        + "\n".join(
            f"- [ ] TASK-FIX-{task_id}: Test" for task_id in range(1, 20)
        )
    )
    status = repair_budget_status(override_fixture)
    expected = (15, 19, 0, 0)
    actual = (
        status.default_budget,
        status.repair_budget,
        status.remaining,
        status.overage,
    )
    if actual != expected:
        errors.append("Explicit repair budget override calculation is invalid")


def check_central_cycle() -> None:
    feature_path = SKILL_DIRECTORY / "feature-development/SKILL.md"
    if not feature_path.exists():
        return
    feature_text = feature_path.read_text(encoding="utf-8")
    markers = {
        "central session",
        "aidd_state.py",
        "aidd_verification.py",
        "three implementation",
        "/review $0",
        "exactly once",
        "SESSION_CHECKPOINT_READY",
        "READY_FOR_USER_COMMIT",
        "Never stage, commit, push",
    }
    missing = sorted(marker for marker in markers if marker not in feature_text)
    if missing:
        errors.append(
            "feature-development is missing central lifecycle markers: "
            + ", ".join(missing)
        )

    bugfix_path = SKILL_DIRECTORY / "bugfix/SKILL.md"
    if bugfix_path.exists():
        bugfix_text = bugfix_path.read_text(encoding="utf-8")
        bugfix_markers = {
            "reports/bugfix/$0.md",
            "/fix-tasks $0 bugfix",
            "/pre-commit $0",
            "/review $0",
            "/validate $0",
            "BUGFIX_RESOLVED",
            "Never stage, commit, push",
        }
        missing_bugfix = sorted(
            marker for marker in bugfix_markers if marker not in bugfix_text
        )
        if missing_bugfix:
            errors.append(
                "bugfix is missing controlled repair markers: "
                + ", ".join(missing_bugfix)
            )

    small_change_path = SKILL_DIRECTORY / "small-change/SKILL.md"
    if small_change_path.exists():
        small_change_text = small_change_path.read_text(encoding="utf-8")
        small_change_markers = {
            "disable-model-invocation: true",
            "Workflow path: SMALL_CHANGE",
            "PRD_READY",
            "PLAN_APPROVED",
            "TASKLIST_READY",
            "/pre-commit $0",
            "/review $0",
            "/validate $0",
            "Never stage, commit, push",
        }
        missing_small_change = sorted(
            marker
            for marker in small_change_markers
            if marker not in small_change_text
        )
        if missing_small_change:
            errors.append(
                "small-change is missing compact lifecycle markers: "
                + ", ".join(missing_small_change)
            )

    write_guard = (ROOT / "scripts/agent-write-guard.py").read_text(
        encoding="utf-8"
    )
    for marker in (
        '"reviewer"',
        '"reports/bugfix/**"',
        '"reports/review/**"',
        '"reports/qa/**"',
        '"reports/security/**"',
        "enforce_repair_budget",
        "Only the user/maintainer",
        "enforce_script_scope",
    ):
        if marker not in write_guard:
            errors.append(f"agent-write-guard is missing marker: {marker}")

    bash_guard = (ROOT / "scripts/bash-command-guard.py").read_text(
        encoding="utf-8"
    )
    if "Tasklists must be modified with Edit/Write" not in bash_guard:
        errors.append("Shell tasklist writes must not bypass repair-budget checks")
    if "agent_script_write_allowed" not in bash_guard:
        errors.append("Shell script writes must use the scoped script allowlist")

    common = (ROOT / "scripts/aidd_common.py").read_text(encoding="utf-8")
    for marker in ("AGENT_EDITABLE_SCRIPT_PATHS",):
        if marker not in common:
            errors.append(f"Scoped script allowlist is missing marker: {marker}")


def check_adr_decision_gate() -> None:
    common = (ROOT / "scripts/aidd_common.py").read_text(encoding="utf-8")
    for marker in ("workflow_adr_paths", "ADR_REFERENCE_RE"):
        if marker not in common:
            errors.append(f"ADR cache invalidation is missing marker: {marker}")

    validator = (ROOT / "scripts/validate-workflow.py").read_text(
        encoding="utf-8"
    )
    for marker in (
        "adr_decision_check",
        "ACCEPTED_ADR_STATUS",
        "ticket-owned or plan-linked ADR",
    ):
        if marker not in validator:
            errors.append(f"ADR decision gate is missing validator marker: {marker}")

    plan_skill = (SKILL_DIRECTORY / "plan/SKILL.md").read_text(encoding="utf-8")
    reviewer = (AGENT_DIRECTORY / "reviewer.md").read_text(encoding="utf-8")
    if "Status: ACCEPTED" not in plan_skill or "HUMAN_REQUIRED" not in plan_skill:
        errors.append("Plan skill must resolve ADRs before PLAN_APPROVED")
    if "Status: ACCEPTED" not in reviewer:
        errors.append("Reviewer must enforce accepted governing ADRs")


def check_settings(settings: dict) -> None:
    if settings.get("model") != "sonnet":
        errors.append("Project default model must be sonnet")
    if settings.get("effortLevel") != "medium":
        errors.append("Project default effortLevel must be medium")
    if (settings.get("env") or {}).get("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE") != "70":
        errors.append("Auto-compact override must be 70")
    permissions = settings.get("permissions") or {}
    denied_permissions = set(permissions.get("deny") or [])
    if "Edit(./scripts/**)" in denied_permissions:
        errors.append("Blanket scripts deny blocks scoped small-change script edits")
    for rule in ("Read(./.env)", "Read(./**/*.pem)", "Read(./**/*.key)"):
        if rule not in denied_permissions:
            errors.append(f"Project must deny secret reads: {rule}")

    hooks = settings.get("hooks") or {}
    stop_commands = [
        hook.get("command", "")
        for entry in hooks.get("Stop", [])
        for hook in entry.get("hooks", [])
    ]
    if not any("scripts/aidd-stop-gate.py" in command for command in stop_commands):
        errors.append("Stop hook must enforce the feature controller state")

    for event, entries in hooks.items():
        for entry in entries:
            for hook in entry.get("hooks", []):
                command = hook.get("command", "")
                match = re.search(r"(hooks|scripts)/[A-Za-z0-9._-]+", command)
                if match and not (ROOT / match.group(0)).exists():
                    errors.append(
                        f"Hook references missing file: {match.group(0)} ({event})"
                    )


def check_json_schemas() -> None:
    for path in sorted((ROOT / ".aidd/schemas").glob("*.json")):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"Invalid JSON schema {path}: {exc}")


def main() -> int:
    settings = load_settings()
    seen_agents = inspect_agents()
    inspect_skills(seen_agents)
    require_files()
    check_repair_budget()
    check_central_cycle()
    check_adr_decision_gate()
    check_settings(settings)
    check_json_schemas()
    for script in (ROOT / "scripts").glob("*.sh"):
        if not script.read_text(encoding="utf-8").startswith("#!/"):
            errors.append(f"Shell script missing shebang: {script}")

    if errors:
        print("AIDD pack self-check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("AIDD pack self-check OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
