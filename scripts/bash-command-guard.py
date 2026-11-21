#!/usr/bin/env python3
"""Bash command guard: blocks destructive operations and gates shell-based
file writes through the same workflow gates as Write/Edit tools.

Shell parsing here is intentionally best-effort. Hard guarantees come from
permissions.deny; this hook narrows the obvious bypass channels
(redirection, tee, sed -i, dd) without trying to be a full shell parser.
"""
from __future__ import annotations

import json
import re
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from aidd_common import (  # noqa: E402
    SCRIPT_PREFIX,
    agent_script_write_allowed,
    allowed_governance_path,
    deny,
    project_root,
    rel_path,
    workflow_gates_pass,
)

BLOCK_PATTERNS = [
    (re.compile(r"\bchmod\s+-R\s+777\b"), "Refusing chmod -R 777."),
    (re.compile(r"\b(curl|wget)\b[^\n]*(\||>)\s*(sh|bash)\b"), "Refusing remote script pipe/download execution."),
    (re.compile(r"\bnpm\s+audit\s+fix\b"), "Do not run npm audit fix automatically. Create an explicit dependency upgrade task."),
    (re.compile(r"\bgit\s+push\b[^\n]*--force(?!-with-lease)"), "Use --force-with-lease or ask the user explicitly before force-pushing."),
    (re.compile(r"\bdocker\s+system\s+prune\b[^\n]*-a"), "Refusing broad docker system prune -a without explicit user approval."),
]

DANGEROUS_RM_TARGETS = re.compile(r"^(/|~|\$HOME)(/)?$")


QUOTED_SPAN_RE = re.compile(r"'[^']*'|\"[^\"]*\"")


def rm_is_destructive(command: str) -> str | None:
    """Detect recursive+force rm against / or $HOME in any flag order/style."""
    for segment in re.split(r"[;&|]+", command):
        tokens = segment.strip().split()
        if not tokens or tokens[0] != "rm":
            # also catch "sudo rm", "env rm", etc.
            if len(tokens) < 2 or tokens[1] != "rm":
                continue
            tokens = tokens[1:]
        flags = [t for t in tokens[1:] if t.startswith("-")]
        args = [t for t in tokens[1:] if not t.startswith("-")]
        recursive = any(f in ("--recursive", "-R") or ("r" in f.lower() and not f.startswith("--")) for f in flags)
        force = any(f == "--force" or ("f" in f and not f.startswith("--")) for f in flags)
        if recursive and force:
            for target in args:
                if DANGEROUS_RM_TARGETS.match(target):
                    return f"Refusing recursive force delete of {target!r}."
    return None


# Best-effort extraction of shell write targets.
REDIRECT_RE = re.compile(r"(?<![0-9&])>{1,2}\s*([^\s;&|<>]+)")
TEE_RE = re.compile(r"\btee\b\s+(?:-a\s+)?([^\s;&|]+)")
SED_INPLACE_RE = re.compile(r"\bsed\b[^\n;|&]*\s-i(?:\S*)?\s+(?:-e\s+)?(?:'[^']*'|\"[^\"]*\"|\S+)\s+([^\s;&|]+)")
DD_OF_RE = re.compile(r"\bdd\b[^\n;|&]*\bof=([^\s;&|]+)")

IGNORED_TARGETS = ("/dev/", "/tmp/", "/var/tmp/")
TASKLIST_PREFIX = "docs/tasklist/"


def shell_write_targets(command: str) -> list[str]:
    command = mask_quoted_meta(command)
    targets: list[str] = []
    for regex in (REDIRECT_RE, TEE_RE, SED_INPLACE_RE, DD_OF_RE):
        targets.extend(regex.findall(command))
    cleaned = []
    for t in targets:
        try:
            t = shlex.split(t)[0] if t else t
        except ValueError:
            pass
        if t and not t.startswith(IGNORED_TARGETS) and t not in ("/dev/null", "&1", "&2"):
            cleaned.append(t)
    return cleaned


def mask_quoted_meta(command: str) -> str:
    """Strip shell metacharacters inside quoted spans so a '>' in quoted text
    (e.g. echo "==> label") is not mistaken for a redirect target."""
    return QUOTED_SPAN_RE.sub(lambda m: re.sub(r"[<>|;&]", "", m.group(0)), command)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return

    if payload.get("tool_name") != "Bash":
        return

    command = (payload.get("tool_input", {}) or {}).get("command", "")
    if not command:
        return

    reason = rm_is_destructive(command)
    if reason:
        deny(f"AIDD Bash guard blocked command: {reason}")

    for pattern, msg in BLOCK_PATTERNS:
        if pattern.search(command):
            deny(f"AIDD Bash guard blocked command: {msg}")

    # Gate shell-based writes into application code the same way as Write/Edit.
    root = project_root(payload)
    write_targets = shell_write_targets(command)
    gated: list[str] = []
    for target in write_targets:
        rel = rel_path(root, target)
        if rel is None:
            continue  # outside project root or unresolvable; permissions layer decides
        if rel.startswith(TASKLIST_PREFIX):
            deny(
                "Tasklists must be modified with Edit/Write so the "
                "deterministic repair-budget guard can inspect the result."
            )
        if rel.startswith(SCRIPT_PREFIX) and not agent_script_write_allowed(rel):
            deny(
                "Only explicitly allowlisted task scripts may be written by "
                f"sandboxed Bash; blocked path: {rel}"
            )
        if not allowed_governance_path(rel):
            gated.append(rel)

    if gated:
        ok, message = workflow_gates_pass(root)
        if not ok:
            deny(
                "AIDD Bash guard blocked shell write to application paths before "
                f"workflow gates pass: {sorted(set(gated))}\n" + message
            )


if __name__ == "__main__":
    main()
