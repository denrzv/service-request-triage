#!/usr/bin/env python3
"""Decide whether C4/ERD PlantUML diagrams should be rendered for an AIDD ticket.

The script intentionally does not infer architecture changes from code heuristics.
The approved plan is the source of truth. Changed diagram source files are always
rendered because they need validation even if the plan metadata is stale.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

DIAGRAM_EXTENSIONS = {".puml", ".plantuml"}
IMPACT_VALUES = {"not changed", "changed", "new", "removed", "not applicable"}
CHANGED_IMPACTS = {"changed", "new", "removed"}

DEFAULT_SOURCES = {
    "system-context-c4": [
        "diagrams/c4/system-context.puml",
        "diagrams/c4/system_context.puml",
        "diagrams/system-context.puml",
    ],
    "container-c4": [
        "diagrams/c4/container.puml",
        "diagrams/c4/containers.puml",
        "diagrams/container.puml",
    ],
    "erd": [
        "diagrams/erd/erd.puml",
        "diagrams/erd/data-model.puml",
        "diagrams/erd/data_model.puml",
        "diagrams/erd.puml",
    ],
}

@dataclass
class DiagramRow:
    diagram: str
    key: str
    impact: str
    reason: str = ""
    source: str = ""

@dataclass
class Decision:
    ticket: str | None
    plan_path: str | None
    render_required: bool | None
    rows: list[DiagramRow]
    changed_diagram_sources: list[str]
    files_to_render: list[str]
    should_render: bool
    warnings: list[str]
    errors: list[str]


def repo_root() -> Path:
    env_root = os.environ.get("CLAUDE_PROJECT_DIR")
    if env_root:
        return Path(env_root).resolve()
    return Path(__file__).resolve().parents[1]


def active_ticket(root: Path, explicit: str | None) -> str | None:
    if explicit:
        value = explicit.strip()
        if value and value != "-":
            return value
    path = root / "docs" / ".active_ticket"
    if not path.exists():
        return None
    lines = path.read_text(encoding="utf-8").splitlines()
    return lines[0].strip() if lines else None


def normalize_key(value: str) -> str:
    value = value.strip().lower()
    value = value.replace("&", "and")
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    if "system" in value and "context" in value:
        return "system-context-c4"
    if "container" in value:
        return "container-c4"
    if "erd" in value or "data-model" in value or "data" in value:
        return "erd"
    return value


def clean_cell(value: str) -> str:
    value = value.strip()
    value = value.strip("` ")
    return value


def parse_plan(root: Path, ticket: str | None) -> tuple[Path | None, bool | None, list[DiagramRow], list[str]]:
    warnings: list[str] = []
    if not ticket:
        return None, None, [], ["No active ticket; cannot read diagram impact from plan."]
    path = root / "docs" / "plan" / f"{ticket}.md"
    if not path.exists():
        return path, None, [], [f"Plan not found: {path.relative_to(root).as_posix()}"]

    text = path.read_text(encoding="utf-8")
    match = re.search(r"(?im)^\s*Render diagrams required:\s*(yes|no)\s*$", text)
    render_required = None if not match else match.group(1).lower() == "yes"
    if render_required is None:
        warnings.append("Missing 'Render diagrams required: yes/no' in plan Diagram impact section.")

    rows: list[DiagramRow] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|") or "---" in stripped:
            continue
        cells = [clean_cell(c) for c in stripped.strip("|").split("|")]
        if len(cells) < 2:
            continue
        first = cells[0].lower()
        if first in {"diagram", "component"}:
            continue
        key = normalize_key(cells[0])
        if key not in DEFAULT_SOURCES:
            continue
        impact = cells[1].lower()
        if impact not in IMPACT_VALUES:
            warnings.append(f"Unexpected diagram impact for {cells[0]!r}: {cells[1]!r}. Use one of {sorted(IMPACT_VALUES)}.")
        rows.append(DiagramRow(
            diagram=cells[0],
            key=key,
            impact=impact,
            reason=cells[2] if len(cells) > 2 else "",
            source=cells[3] if len(cells) > 3 else "",
        ))

    if render_required is True and not any(r.impact in CHANGED_IMPACTS for r in rows):
        warnings.append("Plan says diagrams are required, but no diagram row is marked changed/new/removed.")
    if render_required is False and any(r.impact in CHANGED_IMPACTS for r in rows):
        warnings.append("Plan says diagrams are not required, but at least one diagram row is marked changed/new/removed.")

    return path, render_required, rows, warnings


def git_changed_files(root: Path) -> list[str]:
    files: set[str] = set()
    commands = [
        ["git", "diff", "--name-only", "--diff-filter=ACMRT"],
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMRT"],
    ]
    for cmd in commands:
        try:
            result = subprocess.run(cmd, cwd=root, text=True, capture_output=True, check=False)
        except FileNotFoundError:
            continue
        if result.returncode != 0:
            continue
        for line in result.stdout.splitlines():
            rel = line.strip()
            if rel:
                files.add(rel)
    return sorted(files)


def all_diagram_sources(root: Path) -> list[str]:
    base = root / "diagrams"
    if not base.exists():
        return []
    return sorted(
        p.relative_to(root).as_posix()
        for p in base.rglob("*")
        if p.is_file() and p.suffix.lower() in DIAGRAM_EXTENSIONS and "/out/" not in p.as_posix()
    )


def source_from_row(row: DiagramRow) -> list[str]:
    if row.source and row.source not in {"-", "n/a", "N/A"}:
        return [row.source]
    return DEFAULT_SOURCES.get(row.key, [])


def existing(root: Path, rels: list[str]) -> list[str]:
    result = []
    for rel in rels:
        rel = rel.strip().strip("`")
        if not rel:
            continue
        p = root / rel
        if p.exists() and p.is_file() and p.suffix.lower() in DIAGRAM_EXTENSIONS:
            result.append(rel)
    return result


def decide(root: Path, ticket: str | None, force: bool = False) -> Decision:
    plan_path, render_required, rows, warnings = parse_plan(root, ticket)
    all_sources = all_diagram_sources(root)
    changed = [
        f for f in git_changed_files(root)
        if f.startswith("diagrams/") and Path(f).suffix.lower() in DIAGRAM_EXTENSIONS and "/out/" not in f
    ]

    files: set[str] = set()
    errors: list[str] = []

    if force or os.environ.get("AIDD_RENDER_DIAGRAMS", "").lower() in {"1", "true", "yes"}:
        files.update(all_sources)
    else:
        files.update(changed)
        if render_required is True:
            for row in rows:
                if row.impact in CHANGED_IMPACTS:
                    files.update(existing(root, source_from_row(row)))
            if not files:
                errors.append(
                    "Plan requires diagram rendering, but no diagram source files were found. "
                    "Create/update the relevant .puml files under diagrams/ first, or run with --force after adding sources."
                )
        elif render_required is False:
            # Only changed diagram source files are rendered. No source changes means no render.
            pass
        else:
            # No reliable plan metadata. Be conservative: render only changed diagram sources.
            pass

    files_to_render = sorted(files)
    should_render = bool(files_to_render) and not errors
    rel_plan = None if plan_path is None else plan_path.relative_to(root).as_posix()
    return Decision(
        ticket=ticket,
        plan_path=rel_plan,
        render_required=render_required,
        rows=rows,
        changed_diagram_sources=changed,
        files_to_render=files_to_render,
        should_render=should_render,
        warnings=warnings,
        errors=errors,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--json", action="store_true", dest="as_json")
    parser.add_argument("--list-files", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    root = repo_root()
    ticket = active_ticket(root, args.ticket)
    decision = decide(root, ticket, args.force)

    if args.list_files:
        for rel in decision.files_to_render:
            print(rel)
        return 1 if decision.errors else 0

    if args.as_json:
        data = asdict(decision)
        data["rows"] = [asdict(r) for r in decision.rows]
        print(json.dumps(data, indent=2, ensure_ascii=False))
    elif not args.quiet:
        print(f"[diagrams] ticket: {decision.ticket or '<missing>'}")
        print(f"[diagrams] plan: {decision.plan_path or '<missing>'}")
        print(f"[diagrams] render required by plan: {decision.render_required}")
        if decision.changed_diagram_sources:
            print("[diagrams] changed diagram sources:")
            for rel in decision.changed_diagram_sources:
                print(f"  - {rel}")
        if decision.files_to_render:
            print("[diagrams] files selected for rendering:")
            for rel in decision.files_to_render:
                print(f"  - {rel}")
        else:
            print("[diagrams] no files selected for rendering")
        for warning in decision.warnings:
            print(f"[warn] {warning}", file=sys.stderr)
        for error in decision.errors:
            print(f"[error] {error}", file=sys.stderr)

    return 1 if decision.errors else 0

if __name__ == "__main__":
    raise SystemExit(main())
