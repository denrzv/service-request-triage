#!/usr/bin/env python3
"""Focused regression tests for deterministic verification metadata."""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
VERIFICATION_PATH = SCRIPT_DIRECTORY / "aidd_verification.py"
OLD_CONTRACT = "triage-openapi-v0.9.2.yaml"
NEW_CONTRACT = "triage-openapi-v0.9.3.yaml"

sys.path.insert(0, str(SCRIPT_DIRECTORY))
SPEC = importlib.util.spec_from_file_location(
    "aidd_verification_under_test",
    VERIFICATION_PATH,
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load verification module from {VERIFICATION_PATH}")
VERIFICATION = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFICATION
SPEC.loader.exec_module(VERIFICATION)


class PorcelainChangedPathsTest(unittest.TestCase):
    def test_rename_retains_complete_old_and_new_contract_paths(self) -> None:
        raw = (
            f"RM {NEW_CONTRACT}".encode()
            + b"\0"
            + OLD_CONTRACT.encode()
            + b"\0"
        )

        paths = VERIFICATION.porcelain_changed_paths(raw)

        self.assertEqual(sorted((OLD_CONTRACT, NEW_CONTRACT)), paths)

    def test_regular_and_untracked_paths_are_not_truncated(self) -> None:
        modified_path = "scripts/aidd_verification.py"
        untracked_path = "scripts/test-aidd-verification.py"
        raw = (
            f" M {modified_path}".encode()
            + b"\0"
            + f"?? {untracked_path}".encode()
            + b"\0"
        )

        paths = VERIFICATION.porcelain_changed_paths(raw)

        self.assertEqual(sorted((modified_path, untracked_path)), paths)


if __name__ == "__main__":
    unittest.main()
