#!/usr/bin/env python3
"""Unit tests for tools/goldset.py (standard library unittest). Run: python3 tools/test_goldset.py"""

from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import goldset  # noqa: E402  (path set above)

TAXONOMY = goldset.load_taxonomy(goldset.DEFAULT_TAXONOMY)


def valid_record(number: int = 1, stratum: str = "SIMPLE_COMPLAINT", message: str | None = None,
                 group: str | None = None) -> dict:
    record = {
        "caseId": f"GS-{number:04d}",
        "message": message or f"Synthetic case text {number}",
        "channel": "WEB_FORM",
        "labelSource": "SYNTHETIC_AUTHOR",
        "taxonomyVersion": "v1",
        "stratum": stratum,
        "intent": "COMPLAINT",
        "secondaryIntents": [],
        "domain": "PAYMENTS",
        "requestedOutcome": "REFUND",
        "formalSrType": "CLAIM",
        "reasonCode": "PAYMENT_DISPUTE",
        "recommendedRoute": "HUMAN_REVIEW",
        "requiresHumanReview": True,
        "alternatives": {name: [] for name in goldset.ALTERNATIVE_FIELDS},
        "paraphraseGroup": group,
        "labelRationale": "Synthetic test record with labels valid for its stratum.",
    }
    if stratum == "MULTI_INTENT":
        record["secondaryIntents"] = ["INQUIRY"]
    elif stratum == "OUT_OF_TAXONOMY":
        record.update(intent="INQUIRY", domain="OTHER", requestedOutcome="EXPLAIN", formalSrType="UNCLASSIFIED",
                      reasonCode="LOW_TAXONOMY_FIT", recommendedRoute="ABSTAIN")
    elif stratum == "NO_COMPLAINT_CONTROL":
        record.update(intent="INQUIRY", requestedOutcome="EXPLAIN", formalSrType="INFORMATION_REQUEST",
                      reasonCode="GENERAL_INFORMATION", recommendedRoute="DRAFT_PROPOSAL", requiresHumanReview=False)
    return record


def balanced(per_stratum: int = 17) -> list[dict]:
    records, number = [], 1
    for stratum in goldset.STRATA:
        for _ in range(per_stratum):
            records.append(valid_record(number, stratum))
            number += 1
    return records


def write_jsonl(path: Path, records: list[dict]) -> Path:
    path.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")
    return path


class RecordRulesTest(unittest.TestCase):

    def test_valid_record_has_no_errors(self):
        self.assertEqual([], goldset.record_errors(valid_record(), TAXONOMY))

    def test_invalid_records_are_rejected_with_reason(self):
        cases = [
            ({"caseId": "CASE-1"}, "caseId must match"),
            ({"intent": "PRAISE"}, "field intent"),
            ({"channel": "SMS"}, "field channel"),
            ({"requiresHumanReview": "true"}, "JSON boolean"),
            ({"message": "short"}, "message length"),
            ({"message": "I was charged twice — please fix"}, "printable ASCII"),
            ({"message": "Write to me at someone@example.test please"}, "e-mail"),
            ({"message": "My account 12345678 is blocked"}, "6 or more consecutive digits"),
            ({"recommendedRoute": "LLM_INVESTIGATION"}, "terminal route"),
            ({"requiresHumanReview": False}, "requiresHumanReview must be true"),
            ({"recommendedRoute": "DRAFT_PROPOSAL", "requiresHumanReview": False}, "CLAIM requires"),
            ({"recommendedRoute": "ABSTAIN"}, "ABSTAIN requires"),
            ({"reasonCode": "TECHNICAL_MALFUNCTION"}, "belongs to TECHNICAL_REQUEST"),
            ({"reasonCode": "REFUND_GRANTED"}, "unknown reason code"),
            ({"taxonomyVersion": "v9"}, "does not match loaded taxonomy"),
            ({"secondaryIntents": ["COMPLAINT"]}, "secondaryIntents must not contain the primary"),
            ({"paraphraseGroup": "G1"}, "paraphraseGroup must be null"),
            ({"stratum": "MULTI_INTENT"}, "MULTI_INTENT requires"),
            ({"stratum": "OUT_OF_TAXONOMY"}, "OUT_OF_TAXONOMY requires"),
            ({"stratum": "NO_COMPLAINT_CONTROL"}, "NO_COMPLAINT_CONTROL must not"),
            ({"stratum": "DUPLICATE_CHARGE", "domain": "ACCOUNTS", "reasonCode": "ACCOUNT_COMPLAINT"},
             "DUPLICATE_CHARGE requires"),
        ]
        for changes, expected in cases:
            with self.subTest(expected=expected):
                record = valid_record()
                record.update(changes)
                errors = goldset.record_errors(record, TAXONOMY)
                self.assertTrue(any(expected in e for e in errors), errors)

    def test_alternatives_rules(self):
        record = valid_record(stratum="NEAR_TIE")
        record["alternatives"]["formalSrType"] = ["CLAIM"]
        self.assertIn("alternatives.formalSrType must not contain the primary label",
                      goldset.record_errors(record, TAXONOMY))
        simple = valid_record()
        simple["alternatives"]["formalSrType"] = ["TECHNICAL_REQUEST"]
        self.assertTrue(any("no alternatives" in e for e in goldset.record_errors(simple, TAXONOMY)))

    def test_missing_and_unknown_properties(self):
        record = valid_record()
        del record["labelSource"]
        record["customerName"] = "Synthetic"
        errors = goldset.record_errors(record, TAXONOMY)
        self.assertEqual(["missing properties ['labelSource']", "unknown properties ['customerName']"], errors)


class LoadFileTest(unittest.TestCase):

    def test_reports_line_and_case_without_message_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = valid_record(2, message="Secret synthetic text é here")
            path = write_jsonl(Path(tmp) / "cases.jsonl", [valid_record(1), bad])
            with self.assertRaises(goldset.GoldsetError) as ctx:
                goldset.load_file(path, TAXONOMY)
            joined = "\n".join(ctx.exception.violations)
            self.assertIn("cases.jsonl:2 GS-0002", joined)
            self.assertNotIn("Secret synthetic text", joined)

    def test_rejects_duplicate_keys_crlf_and_missing_newline(self):
        with tempfile.TemporaryDirectory() as tmp:
            dup = Path(tmp) / "dup.jsonl"
            dup.write_text('{"caseId":"GS-0001","caseId":"GS-0002"}\n', encoding="utf-8")
            crlf = Path(tmp) / "crlf.jsonl"
            crlf.write_bytes(json.dumps(valid_record()).encode() + b"\r\n")
            open_end = Path(tmp) / "open.jsonl"
            open_end.write_bytes(json.dumps(valid_record()).encode())
            for path, expected in ((dup, "Duplicate field 'caseId'"), (crlf, "CR line endings"),
                                   (open_end, "missing final newline")):
                with self.subTest(path=path.name):
                    with self.assertRaises(goldset.GoldsetError) as ctx:
                        goldset.load_file(path, TAXONOMY)
                    self.assertIn(expected, "\n".join(ctx.exception.violations))


class DatasetRulesTest(unittest.TestCase):

    def test_balanced_dataset_is_clean(self):
        self.assertEqual([], goldset.dataset_errors({"DEV": balanced(), "TEST": []}))

    def test_duplicates_and_near_duplicates(self):
        records = balanced()
        records.append(valid_record(901, "VAGUE", "SYNTHETIC case-text 1!"))
        records.append(valid_record(902, "VAGUE", "The mobile app crashes every time I open the card screen"))
        records.append(valid_record(903, "VAGUE", "The mobile app crashes every time I open the card screen now"))
        errors = goldset.dataset_errors({"DEV": records, "TEST": [valid_record(1, "VAGUE", "Different text here")]})
        joined = "\n".join(errors)
        self.assertIn("GS-0001: duplicate caseId", joined)
        self.assertIn("GS-0901: message duplicates GS-0001 after normalization", joined)
        self.assertIn("GS-0903: near-duplicate of GS-0902", joined)

    def test_paraphrase_group_rules(self):
        dev = balanced()
        dev.append(valid_record(901, "VAGUE", "Synthetic singleton paraphrase text", "PG-01"))
        dev.append(valid_record(902, "VAGUE", "Synthetic straddling paraphrase alpha", "PG-02"))
        dev.append(valid_record(904, "VAGUE", "Synthetic strata paraphrase gamma", "PG-03"))
        dev.append(valid_record(905, "NEAR_TIE", "Synthetic strata paraphrase delta", "PG-03"))
        dev.append(valid_record(906, "VAGUE", "Synthetic label paraphrase epsilon", "PG-04"))
        differing = valid_record(907, "VAGUE", "Synthetic label paraphrase zeta", "PG-04")
        differing["requestedOutcome"] = "INVESTIGATE"
        dev.append(differing)
        test = [valid_record(903, "VAGUE", "Synthetic straddling paraphrase beta", "PG-02")]
        joined = "\n".join(goldset.dataset_errors({"DEV": dev, "TEST": test}))
        for expected in ("PG-01: paraphrase group needs at least 2", "PG-02: straddles dev/test",
                         "PG-03: spans strata", "PG-04: labels differ between GS-0906 and GS-0907"):
            self.assertIn(expected, joined)

    def test_balance(self):
        records = [r for r in balanced(12) if r["stratum"] != "NEAR_TIE" or r["caseId"] == "GS-0121"]
        joined = "\n".join(goldset.dataset_errors({"DEV": records, "TEST": []}))
        self.assertIn("dataset size 133 outside [200, 300]", joined)
        self.assertIn("stratum NEAR_TIE has 1 cases, needs at least 12", joined)


class SplitTest(unittest.TestCase):

    def test_target_rounds_half_up(self):
        for size, expected in ((20, 6), (24, 7), (16, 5), (12, 4), (5, 2), (1, 0)):
            self.assertEqual(expected, goldset.split_target(size, 0.30))

    def test_deterministic_order_independent_and_seed_sensitive(self):
        records = balanced(12)
        first = goldset.assign_split(records, "seed", 0.30)
        self.assertEqual(first, goldset.assign_split(list(reversed(records)), "seed", 0.30))
        self.assertNotEqual(first, goldset.assign_split(records, "other-seed", 0.30))
        self.assertEqual(4 * len(goldset.STRATA), list(first.values()).count("TEST"))

    def test_groups_stay_together(self):
        records = [valid_record(i, "VAGUE", f"Synthetic vague text {i}",
                                "PG-01" if i <= 3 else ("PG-02" if i <= 6 else None)) for i in range(1, 13)]
        for seed in "abcdef":
            split = goldset.assign_split(records, seed, 0.30)
            self.assertEqual(1, len({split["GS-0001"], split["GS-0002"], split["GS-0003"]}))
            self.assertEqual(1, len({split["GS-0004"], split["GS-0005"], split["GS-0006"]}))

    def test_invalid_recipe(self):
        with self.assertRaises(ValueError):
            goldset.assign_split(balanced(1), " ", 0.3)
        with self.assertRaises(ValueError):
            goldset.assign_split(balanced(1), "s", 1.0)
        spanning = [valid_record(1, "VAGUE", group="PG-01"), valid_record(2, "NEAR_TIE", "Other text", "PG-01")]
        with self.assertRaises(ValueError):
            goldset.assign_split(spanning, "s", 0.3)


class FreezeVerifyTest(unittest.TestCase):

    def test_freeze_is_idempotent_and_verify_detects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            source = write_jsonl(tmp_path / "source.jsonl", balanced())
            out = tmp_path / "v1"
            goldset.freeze([source], out, TAXONOMY, "seed", 0.30)
            snapshot = {name: (out / name).read_bytes() for name in ("dev.jsonl", "test.jsonl", "manifest.json")}
            manifest = json.loads(snapshot["manifest.json"])
            self.assertEqual(204, manifest["totalRecords"])
            self.assertEqual(goldset.SPLIT_ALGORITHM, manifest["split"]["algorithm"])

            goldset.freeze([out / "dev.jsonl", out / "test.jsonl"], out, TAXONOMY, "seed", 0.30)
            for name, data in snapshot.items():
                self.assertEqual(data, (out / name).read_bytes(), name)

            dev_lines = snapshot["dev.jsonl"].decode().splitlines(keepends=True)
            moved = dev_lines.pop(0)
            (out / "dev.jsonl").write_text("".join(dev_lines), encoding="ascii")
            test_lines = snapshot["test.jsonl"].decode().splitlines(keepends=True) + [moved]
            (out / "test.jsonl").write_text("".join(sorted(test_lines)), encoding="ascii")
            with self.assertRaises(goldset.GoldsetError) as ctx:
                goldset.verify(out, TAXONOMY)
            joined = "\n".join(ctx.exception.violations)
            self.assertIn("manifest.json: does not match the files", joined)
            self.assertIn("but the frozen recipe assigns DEV", joined)

    def test_verify_detects_non_canonical_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "v1"
            goldset.freeze([write_jsonl(Path(tmp) / "s.jsonl", balanced())], out, TAXONOMY, "seed", 0.30)
            records = [json.loads(line) for line in (out / "dev.jsonl").read_text().splitlines()]
            write_jsonl(out / "dev.jsonl", records)  # default json.dumps spacing is not canonical
            with self.assertRaises(goldset.GoldsetError) as ctx:
                goldset.verify(out, TAXONOMY)
            self.assertIn("dev.jsonl: not in canonical form", "\n".join(ctx.exception.violations))

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "v1"
            source = write_jsonl(Path(tmp) / "s.jsonl", balanced())
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, goldset.main(["freeze", str(source), "--out", str(out)]))
                self.assertEqual(0, goldset.main(["verify", str(out)]))
                buffer = io.StringIO()
                with redirect_stdout(buffer):
                    self.assertEqual(0, goldset.main(["coverage", str(out)]))
                self.assertIn("| SIMPLE_COMPLAINT | 12 | 5 | 17 | 29 |", buffer.getvalue())
            broken = copy.deepcopy(balanced())
            broken[0]["intent"] = "PRAISE"
            bad = write_jsonl(Path(tmp) / "bad.jsonl", broken)
            with redirect_stderr(io.StringIO()) as err:
                self.assertEqual(1, goldset.main(["freeze", str(bad), "--out", str(Path(tmp) / "x")]))
            self.assertIn("bad.jsonl:1 GS-0001: field intent", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=1)
