#!/usr/bin/env python3
"""Defense-in-depth read guard for Claude Code hooks.

Primary secret protection is configured through permissions.deny in
.claude/settings.json because @file references bypass PreToolUse Read hooks.
This hook catches direct Read/Grep/Glob attempts and returns a current-format
PreToolUse deny decision.

Tool-aware: for Grep, the `pattern` field is a *content* regex, so it is not
checked against path rules (searching code for the word "secret" is
legitimate). Only path-like inputs are validated.
"""
import json
import os
import re
import sys

SENSITIVE_PATTERNS = [
    re.compile(r"(^|/)\.env(?:\.|$|[*?\[])"),
    re.compile(r"(^|/)secrets(/|$)", re.I),
    re.compile(r"credential", re.I),
    re.compile(r"secret", re.I),
    re.compile(r"\.(pem|key|p12|jks)$", re.I),
]
SAFE_ENV_TEMPLATES = (".env.example", ".env.prod.example")

# Which tool_input keys are path-like, per tool.
# Grep: `pattern` is a content regex -> excluded; `path`/`glob` are paths.
# Glob: `pattern` is a file glob -> included.
PATH_KEYS_BY_TOOL = {
    "Read": ("file_path", "path"),
    "Grep": ("path", "glob"),
    "Glob": ("pattern", "path"),
}


def deny(reason: str) -> None:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(0)

tool_name = payload.get("tool_name", "")
keys = PATH_KEYS_BY_TOOL.get(tool_name)
if keys is None:
    sys.exit(0)

tool_input = payload.get("tool_input", {}) or {}
values = []
for key in keys:
    value = tool_input.get(key)
    if isinstance(value, str):
        values.append(value.replace(os.sep, "/"))

for value in values:
    normalized = value.rstrip("/")
    if any(
        normalized == template or normalized.endswith(f"/{template}")
        for template in SAFE_ENV_TEMPLATES
    ):
        continue
    if any(p.search(value) for p in SENSITIVE_PATTERNS):
        deny(f"AIDD read guard blocked access to a sensitive path or pattern: {value}")

sys.exit(0)
