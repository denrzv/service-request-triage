#!/usr/bin/env python3
"""Focused regression tests for the workflow ADR decision gate."""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
VALIDATOR_PATH = SCRIPT_DIRECTORY / "validate-workflow.py"
TEST_TICKET = "ADR-GATE-001"
LINKED_ADR_NAME = "SHARED-001-governing-decision.md"
TICKET_ADR_NAME = f"{TEST_TICKET}-decision.md"
ACCEPTED_STATUS = "ACCEPTED"
PROPOSED_STATUS = "PROPOSED"

sys.path.insert(0, str(SCRIPT_DIRECTORY))
from aidd_common import workflow_gate_signature  # noqa: E402

SPEC = importlib.util.spec_from_file_location("validate_workflow", VALIDATOR_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load validator from {VALIDATOR_PATH}")
VALIDATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VALIDATOR
SPEC.loader.exec_module(VALIDATOR)


class AdrDecisionGateTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        self.root = Path(temporary_directory.name)
        (self.root / "docs/adr").mkdir(parents=True)
        (self.root / "docs/plan").mkdir(parents=True)

    def write_plan(self) -> None:
        (self.root / f"docs/plan/{TEST_TICKET}.md").write_text(
            "# Plan\n\n## ADRs\n"
            f"- `docs/adr/{LINKED_ADR_NAME}`\n\n"
            "## Open Questions\nNone\n",
            encoding="utf-8",
        )

    def write_adr(self, filename: str, status: str) -> None:
        (self.root / "docs/adr" / filename).write_text(
            f"# ADR\nStatus: {status}\n",
            encoding="utf-8",
        )

    def test_accepts_ticket_owned_and_plan_linked_adrs(self) -> None:
        self.write_plan()
        self.write_adr(TICKET_ADR_NAME, ACCEPTED_STATUS)
        self.write_adr(LINKED_ADR_NAME, ACCEPTED_STATUS)

        result = VALIDATOR.adr_decision_check(self.root, TEST_TICKET)

        self.assertTrue(result.ok)
        self.assertIn("All 2", result.message)

    def test_rejects_proposed_adr(self) -> None:
        self.write_plan()
        self.write_adr(TICKET_ADR_NAME, PROPOSED_STATUS)
        self.write_adr(LINKED_ADR_NAME, ACCEPTED_STATUS)

        result = VALIDATOR.adr_decision_check(self.root, TEST_TICKET)

        self.assertFalse(result.ok)
        self.assertIn(f"{TICKET_ADR_NAME}={PROPOSED_STATUS}", result.message)

    def test_rejects_missing_plan_linked_adr(self) -> None:
        self.write_plan()

        result = VALIDATOR.adr_decision_check(self.root, TEST_TICKET)

        self.assertFalse(result.ok)
        self.assertIn(LINKED_ADR_NAME, result.message)

    def test_adr_status_change_invalidates_gate_cache_signature(self) -> None:
        self.write_plan()
        self.write_adr(TICKET_ADR_NAME, ACCEPTED_STATUS)
        self.write_adr(LINKED_ADR_NAME, ACCEPTED_STATUS)
        accepted_signature = workflow_gate_signature(
            self.root,
            TEST_TICKET,
            require_research=False,
        )

        self.write_adr(TICKET_ADR_NAME, PROPOSED_STATUS)
        proposed_signature = workflow_gate_signature(
            self.root,
            TEST_TICKET,
            require_research=False,
        )

        self.assertNotEqual(accepted_signature, proposed_signature)


if __name__ == "__main__":
    unittest.main()
