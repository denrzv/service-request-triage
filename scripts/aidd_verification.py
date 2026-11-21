#!/usr/bin/env python3
"""Run deterministic checks and bind their result to the current working tree."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

TEXT_ENCODING = "utf-8"
SAFE_TICKET = re.compile(r"^[A-Z0-9][A-Z0-9-]*$")
RUNTIME_ROOT = Path(".aidd/runtime/features")
VERIFICATION_DIRECTORY = "verification"
RESULT_FILE = "latest.json"
PASS_STATUS = "PASS"
FAIL_STATUS = "FAIL"
FULL_PROFILE = "full"
VERIFICATION_PROFILES = (FULL_PROFILE,)
CHECK_COMMANDS = {
    FULL_PROFILE: ("bash", "scripts/pre-commit-check.sh"),
}
LOG_FILES = {
    FULL_PROFILE: "pre-commit.log",
}
RENAME_OR_COPY_CODES = frozenset({"R", "C"})
VERIFICATION_PATHSPEC = (
    ".",
    ":(exclude)docs/**",
    ":(exclude)reports/**",
    ":(exclude)diagrams/**",
)


def repository_root() -> Path:
    configured = (
        os.environ.get("AIDD_PROJECT_DIR")
        or os.environ.get("CODEX_PROJECT_DIR")
        or os.environ.get("CLAUDE_PROJECT_DIR")
    )
    return Path(configured or Path(__file__).resolve().parents[1]).resolve()


def require_ticket(ticket: str) -> str:
    normalized = ticket.strip().upper()
    if not SAFE_TICKET.fullmatch(normalized):
        raise ValueError(f"Invalid ticket identifier: {ticket}")
    return normalized


def git_bytes(root: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode(TEXT_ENCODING, errors="replace").strip())
    return result.stdout


def untracked_paths(root: Path, pathspec: tuple[str, ...] = ()) -> list[Path]:
    args = ["ls-files", "--others", "--exclude-standard", "-z"]
    if pathspec:
        args.extend(["--", *pathspec])
    raw = git_bytes(root, *args)
    return [
        root / item.decode(TEXT_ENCODING)
        for item in raw.split(b"\0")
        if item
    ]


def working_tree_fingerprint(root: Path) -> str:
    digest = hashlib.sha256()
    digest.update(
        git_bytes(
            root,
            "status",
            "--porcelain=v1",
            "-z",
            "--",
            *VERIFICATION_PATHSPEC,
        )
    )
    digest.update(
        git_bytes(
            root,
            "diff",
            "--binary",
            "HEAD",
            "--",
            *VERIFICATION_PATHSPEC,
        )
    )
    for path in sorted(untracked_paths(root, VERIFICATION_PATHSPEC)):
        relative = path.relative_to(root).as_posix()
        digest.update(relative.encode(TEXT_ENCODING))
        if path.is_file():
            digest.update(path.read_bytes())
    return digest.hexdigest()


def porcelain_changed_paths(raw: bytes) -> list[str]:
    """Parse NUL-delimited porcelain v1, retaining both sides of renames."""
    items = raw.split(b"\0")
    paths: list[str] = []
    index = 0
    while index < len(items):
        item = items[index]
        index += 1
        if not item:
            continue

        text = item.decode(TEXT_ENCODING, errors="replace")
        if len(text) < 3:
            paths.append(text)
            continue

        status = text[:2]
        paths.append(text[3:])
        if not RENAME_OR_COPY_CODES.intersection(status):
            continue
        if index >= len(items) or not items[index]:
            continue
        paths.append(items[index].decode(TEXT_ENCODING, errors="replace"))
        index += 1

    return sorted(set(paths))


def changed_files(root: Path) -> list[str]:
    raw = git_bytes(root, "status", "--porcelain=v1", "-z")
    return porcelain_changed_paths(raw)


def verification_directory(root: Path, ticket: str) -> Path:
    return root / RUNTIME_ROOT / ticket / VERIFICATION_DIRECTORY


def result_path(root: Path, ticket: str) -> Path:
    return verification_directory(root, ticket) / RESULT_FILE


def atomic_write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding=TEXT_ENCODING,
    )
    temporary.replace(path)


def run_verification(root: Path, ticket: str, profile: str = FULL_PROFILE) -> dict:
    directory = verification_directory(root, ticket)
    directory.mkdir(parents=True, exist_ok=True)
    command = CHECK_COMMANDS[profile]
    result = subprocess.run(
        list(command),
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    combined_output = result.stdout + result.stderr
    log_path = directory / LOG_FILES[profile]
    log_path.write_text(combined_output, encoding=TEXT_ENCODING)
    payload = {
        "schemaVersion": 2,
        "ticket": ticket,
        "status": PASS_STATUS if result.returncode == 0 else FAIL_STATUS,
        "verificationProfile": profile,
        "command": " ".join(command),
        "exitCode": result.returncode,
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "workingTreeFingerprint": working_tree_fingerprint(root),
        "changedFiles": changed_files(root),
        "outputLog": log_path.relative_to(root).as_posix(),
    }
    atomic_write_json(result_path(root, ticket), payload)
    if combined_output:
        print(combined_output.rstrip())
    print(
        f"[aidd-verification] {payload['status']} "
        f"profile={profile} fingerprint={payload['workingTreeFingerprint']}"
    )
    return payload


def load_result(root: Path, ticket: str) -> dict:
    path = result_path(root, ticket)
    if not path.exists():
        raise FileNotFoundError(f"Verification evidence missing: {path}")
    return json.loads(path.read_text(encoding=TEXT_ENCODING))


def expected_profile(profile: str | None) -> str:
    selected = profile or os.environ.get("AIDD_VERIFICATION_PROFILE") or FULL_PROFILE
    if selected not in VERIFICATION_PROFILES:
        raise ValueError(f"Unknown verification profile: {selected}")
    return selected


def current_result(
    root: Path,
    ticket: str,
    profile: str | None = None,
) -> tuple[bool, str]:
    selected_profile = expected_profile(profile)
    result = load_result(root, ticket)
    if result.get("status") != PASS_STATUS:
        return False, f"Latest deterministic verification is {result.get('status', 'missing')}"

    # schemaVersion 1 evidence predates profiles and therefore represents the
    # historical/full shared gate. This preserves backward compatibility for
    # Claude while preventing Codex Cloud evidence from satisfying a full check.
    actual_profile = str(result.get("verificationProfile") or FULL_PROFILE)
    if actual_profile != selected_profile:
        return (
            False,
            f"Verification profile mismatch: expected {selected_profile}, found {actual_profile}",
        )

    expected = str(result.get("workingTreeFingerprint") or "")
    actual = working_tree_fingerprint(root)
    if expected != actual:
        return False, "Deterministic verification is stale for the current working tree"
    return True, f"Current PASS evidence ({selected_profile}): {expected}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ticket", required=True, type=require_ticket)
    parser.add_argument(
        "--profile",
        choices=VERIFICATION_PROFILES,
        default=FULL_PROFILE,
        help="Verification profile. Default 'full' preserves the shared Claude/local gate.",
    )
    parser.add_argument("command", choices=("run", "check", "show"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repository_root()
    try:
        if args.command == "run":
            result = run_verification(root, args.ticket, args.profile)
            return int(result["exitCode"] != 0)
        if args.command == "show":
            print(json.dumps(load_result(root, args.ticket), indent=2, sort_keys=True))
            return 0
        ok, message = current_result(root, args.ticket, args.profile)
        print(message)
        return 0 if ok else 1
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
