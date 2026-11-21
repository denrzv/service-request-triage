#!/usr/bin/env python3
"""English goldset tool (FEATURE-004): validate, report coverage, freeze and verify the frozen split.

Standard library only. Rules mirror the Java checks in
src/test/java/io/github/denrzv/triage/evaluation/goldset (GoldCase, GoldsetValidator, GoldsetSplitter);
change both together. Messages are untrusted synthetic text: they are never printed, only case IDs are.

Usage:
  python3 tools/goldset.py validate samples/english-goldset/v1
  python3 tools/goldset.py verify   samples/english-goldset/v1
  python3 tools/goldset.py coverage samples/english-goldset/v1
  python3 tools/goldset.py freeze   <input.jsonl>... --out samples/english-goldset/v1 [--seed S] [--test-fraction F]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections import Counter, OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TAXONOMY = ROOT / "src" / "main" / "resources" / "taxonomy" / "v1.json"

DATASET_ID = "english-goldset"
SPLIT_ALGORITHM = "sha256-rank-per-stratum-v1"
DEFAULT_SEED = "feature-004-english-goldset-v1"
DEFAULT_TEST_FRACTION = 0.30
SPLIT_FILES = OrderedDict([("DEV", "dev.jsonl"), ("TEST", "test.jsonl")])
MANIFEST = "manifest.json"

FIELDS = [
    "caseId", "message", "channel", "labelSource", "taxonomyVersion", "stratum", "intent",
    "secondaryIntents", "domain", "requestedOutcome", "formalSrType", "reasonCode", "recommendedRoute",
    "requiresHumanReview", "alternatives", "paraphraseGroup", "labelRationale",
]
ALTERNATIVE_FIELDS = ["intent", "domain", "requestedOutcome", "formalSrType", "recommendedRoute"]

CHANNELS = ["INTERNET_BANK", "CONTACT_CENTER", "WEB_FORM"]
LABEL_SOURCES = ["SYNTHETIC_AUTHOR"]
ROUTES = ["DRAFT_PROPOSAL", "LLM_INVESTIGATION", "HUMAN_REVIEW", "ABSTAIN"]
STRATA = [
    "SIMPLE_COMPLAINT", "SIMPLE_INQUIRY", "SIMPLE_SERVICE_ACTION", "SIMPLE_INCIDENT", "DUPLICATE_CHARGE",
    "DISSATISFACTION_WITH_TECHNICAL", "MULTI_INTENT", "VAGUE", "CONTRADICTORY", "OUT_OF_TAXONOMY", "NEAR_TIE",
    "NO_COMPLAINT_CONTROL",
]

MIN_TEXT = 10
MAX_MESSAGE = 600
MAX_RATIONALE = 400
NEAR_DUPLICATE_JACCARD = 0.8
MIN_TOTAL, MAX_TOTAL, MIN_PER_STRATUM, MIN_GROUP_SIZE = 200, 300, 12, 2

CASE_ID = re.compile(r"GS-\d{4}")
GROUP_ID = re.compile(r"PG-\d{2}")
PRINTABLE_ASCII = re.compile(r"[\x20-\x7E]+")
LONG_DIGIT_RUN = re.compile(r"\d{6,}")
NON_ALPHANUMERIC = re.compile(r"[^a-z0-9]+")


class GoldsetError(Exception):
    """Aggregated violations; each entry names file/line or case ID and field, never message text."""

    def __init__(self, violations: list[str]):
        super().__init__(f"{len(violations)} goldset violation(s)")
        self.violations = violations


# ---------------------------------------------------------------- taxonomy


def load_taxonomy(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        "version": data["version"],
        "intents": list(data["primaryIntents"]),
        "domains": list(data["problemDomains"]),
        "outcomes": list(data["requestedOutcomes"]),
        "types": list(data["serviceRequestTypes"]),
        "reasonCodes": {entry["code"]: entry["serviceRequestType"] for entry in data["reasonCodes"]},
    }


# ---------------------------------------------------------------- record rules


def _text_errors(value, field: str, max_length: int) -> list[str]:
    if not isinstance(value, str):
        return [f"{field} must be a string"]
    errors = []
    if not MIN_TEXT <= len(value) <= max_length:
        errors.append(f"{field} length must be within [{MIN_TEXT}, {max_length}]")
    if value.strip() != value:
        errors.append(f"{field} must not have leading or trailing whitespace")
    if not PRINTABLE_ASCII.fullmatch(value):
        errors.append(f"{field} must be single-line printable ASCII English")
    if "@" in value:
        errors.append(f"{field} must not contain '@' (no e-mail addresses)")
    if LONG_DIGIT_RUN.search(value):
        errors.append(f"{field} must not contain 6 or more consecutive digits (no account-like numbers)")
    return errors


def _enum_errors(value, field: str, allowed: list[str]) -> list[str]:
    return [] if isinstance(value, str) and value in allowed else [f"field {field}: value not one of {allowed}"]


def _list_errors(values, field: str, allowed: list[str]) -> list[str]:
    if not isinstance(values, list):
        return [f"{field} must be a list"]
    errors = [e for v in values for e in _enum_errors(v, field, allowed)]
    if len(set(map(str, values))) != len(values):
        errors.append(f"{field} must not contain duplicates")
    return errors


def record_errors(record, taxonomy: dict) -> list[str]:
    """Record-local and taxonomy rules; mirrors GoldCase and GoldsetLoader.checkTaxonomy."""
    if not isinstance(record, dict):
        return ["record must be a JSON object"]
    missing = [f for f in FIELDS if f not in record]
    unknown = [f for f in record if f not in FIELDS]
    if missing or unknown:
        return ([f"missing properties {missing}"] if missing else []) + (
            [f"unknown properties {unknown}"] if unknown else [])
    alternatives = record["alternatives"]
    if not isinstance(alternatives, dict) or sorted(alternatives) != sorted(ALTERNATIVE_FIELDS):
        return [f"alternatives must have exactly the properties {ALTERNATIVE_FIELDS}"]

    errors: list[str] = []
    if not isinstance(record["caseId"], str) or not CASE_ID.fullmatch(record["caseId"]):
        errors.append("caseId must match GS-NNNN")
    errors += _text_errors(record["message"], "message", MAX_MESSAGE)
    errors += _text_errors(record["labelRationale"], "labelRationale", MAX_RATIONALE)
    if not isinstance(record["taxonomyVersion"], str) or not record["taxonomyVersion"].strip():
        errors.append("taxonomyVersion must not be blank")
    if not isinstance(record["reasonCode"], str) or not record["reasonCode"].strip():
        errors.append("reasonCode must not be blank")
    errors += _enum_errors(record["channel"], "channel", CHANNELS)
    errors += _enum_errors(record["labelSource"], "labelSource", LABEL_SOURCES)
    errors += _enum_errors(record["stratum"], "stratum", STRATA)
    errors += _enum_errors(record["intent"], "intent", taxonomy["intents"])
    errors += _enum_errors(record["domain"], "domain", taxonomy["domains"])
    errors += _enum_errors(record["requestedOutcome"], "requestedOutcome", taxonomy["outcomes"])
    errors += _enum_errors(record["formalSrType"], "formalSrType", taxonomy["types"])
    errors += _enum_errors(record["recommendedRoute"], "recommendedRoute", ROUTES)
    errors += _list_errors(record["secondaryIntents"], "secondaryIntents", taxonomy["intents"])
    alt_domains = {
        "intent": taxonomy["intents"], "domain": taxonomy["domains"], "requestedOutcome": taxonomy["outcomes"],
        "formalSrType": taxonomy["types"], "recommendedRoute": ROUTES,
    }
    for name in ALTERNATIVE_FIELDS:
        errors += _list_errors(alternatives[name], f"alternatives.{name}", alt_domains[name])
    if not isinstance(record["requiresHumanReview"], bool):
        errors.append("field requiresHumanReview: must be a JSON boolean")
    group = record["paraphraseGroup"]
    if group is not None and (not isinstance(group, str) or not GROUP_ID.fullmatch(group)):
        errors.append("paraphraseGroup must be null or match PG-NN")
    if errors:
        return errors
    return _label_errors(record, taxonomy)


def _label_errors(r: dict, taxonomy: dict) -> list[str]:
    errors = []
    route, srtype, alts = r["recommendedRoute"], r["formalSrType"], r["alternatives"]
    if r["taxonomyVersion"] != taxonomy["version"]:
        errors.append(f"taxonomyVersion {r['taxonomyVersion']} does not match loaded taxonomy {taxonomy['version']}")
    owner = taxonomy["reasonCodes"].get(r["reasonCode"])
    if owner is None:
        errors.append(f"unknown reason code {r['reasonCode']}")
    elif owner != srtype:
        errors.append(f"reason code {r['reasonCode']} belongs to {owner}, not {srtype}")
    if route == "LLM_INVESTIGATION":
        errors.append("recommendedRoute must be a terminal route")
    if "LLM_INVESTIGATION" in alts["recommendedRoute"]:
        errors.append("alternatives.recommendedRoute must contain terminal routes only")
    if r["requiresHumanReview"] != (route != "DRAFT_PROPOSAL"):
        errors.append("requiresHumanReview must be true exactly when recommendedRoute is not DRAFT_PROPOSAL")
    if route == "DRAFT_PROPOSAL" and srtype == "UNCLASSIFIED":
        errors.append("DRAFT_PROPOSAL requires a formal type other than UNCLASSIFIED")
    if route == "ABSTAIN" and srtype != "UNCLASSIFIED":
        errors.append("ABSTAIN requires formalSrType UNCLASSIFIED")
    if srtype == "CLAIM" and route != "HUMAN_REVIEW":
        errors.append("CLAIM requires recommendedRoute HUMAN_REVIEW")
    if r["intent"] in r["secondaryIntents"]:
        errors.append("secondaryIntents must not contain the primary intent")
    primary = {"intent": r["intent"], "domain": r["domain"], "requestedOutcome": r["requestedOutcome"],
               "formalSrType": srtype, "recommendedRoute": route}
    for name in ALTERNATIVE_FIELDS:
        if primary[name] in alts[name]:
            errors.append(f"alternatives.{name} must not contain the primary label")
    return errors + _stratum_errors(r)


def _stratum_errors(r: dict) -> list[str]:
    stratum = r["stratum"]
    has_alternatives = any(r["alternatives"][name] for name in ALTERNATIVE_FIELDS)
    errors = []
    if stratum.startswith("SIMPLE_") and (r["secondaryIntents"] or has_alternatives):
        errors.append(f"{stratum} cases must have no secondary intents and no alternatives")
    if stratum == "MULTI_INTENT" and not r["secondaryIntents"]:
        errors.append("MULTI_INTENT requires secondaryIntents")
    if stratum == "OUT_OF_TAXONOMY" and (r["formalSrType"], r["reasonCode"], r["recommendedRoute"]) != (
            "UNCLASSIFIED", "LOW_TAXONOMY_FIT", "ABSTAIN"):
        errors.append("OUT_OF_TAXONOMY requires UNCLASSIFIED / LOW_TAXONOMY_FIT / ABSTAIN")
    if stratum == "DUPLICATE_CHARGE" and (r["domain"] != "PAYMENTS" or r["formalSrType"] != "CLAIM"):
        errors.append("DUPLICATE_CHARGE requires domain PAYMENTS and formalSrType CLAIM")
    if stratum == "NO_COMPLAINT_CONTROL" and (r["intent"] == "COMPLAINT" or r["formalSrType"] == "CLAIM"):
        errors.append("NO_COMPLAINT_CONTROL must not be a complaint or CLAIM")
    if stratum in ("VAGUE", "CONTRADICTORY", "NEAR_TIE") and r["recommendedRoute"] == "DRAFT_PROPOSAL":
        errors.append(f"{stratum} cases must not be auto-proposed")
    return errors


def _case_id_of(record) -> str:
    cid = record.get("caseId") if isinstance(record, dict) else None
    if not isinstance(cid, str):
        return "<no caseId>"
    return cid if re.fullmatch(r"[A-Za-z0-9-]{1,16}", cid) else "<invalid caseId>"


def _reject_duplicate_keys(pairs):
    seen = set()
    for key, _ in pairs:
        if key in seen:
            raise ValueError(f"Duplicate field '{key}'")
        seen.add(key)
    return dict(pairs)


def load_file(path: Path, taxonomy: dict) -> list[dict]:
    raw = path.read_bytes()
    if b"\r" in raw:
        raise GoldsetError([f"{path.name}: CR line endings are not allowed"])
    if raw and not raw.endswith(b"\n"):
        raise GoldsetError([f"{path.name}: missing final newline"])
    records, violations = [], []
    lines = raw.decode("utf-8").split("\n")[:-1] if raw else []
    for number, line in enumerate(lines, start=1):
        location = f"{path.name}:{number}"
        if not line.strip():
            violations.append(f"{location} blank line")
            continue
        try:
            record = json.loads(line, object_pairs_hook=_reject_duplicate_keys)
        except ValueError as exc:
            violations.append(f"{location} <unparsed>: {exc}")
            continue
        errors = record_errors(record, taxonomy)
        if errors:
            violations += [f"{location} {_case_id_of(record)}: {e}" for e in errors]
        else:
            records.append(record)
    if violations:
        raise GoldsetError(violations)
    return records


# ---------------------------------------------------------------- dataset rules


def normalize(text: str) -> str:
    return NON_ALPHANUMERIC.sub(" ", text.lower()).strip()


def tokens(text: str) -> set[str]:
    normalized = normalize(text)
    return set(normalized.split(" ")) if normalized else set()


def jaccard(left: set[str], right: set[str]) -> float:
    if not left and not right:
        return 1.0
    return len(left & right) / len(left | right)


def _label_key(r: dict) -> tuple:
    return (r["intent"], tuple(r["secondaryIntents"]), r["domain"], r["requestedOutcome"], r["formalSrType"],
            r["reasonCode"], r["recommendedRoute"])


def dataset_errors(splits: dict[str, list[dict]]) -> list[str]:
    """Cross-record rules; mirrors GoldsetValidator."""
    violations: list[str] = []
    all_records, split_of = [], {}
    for split, records in splits.items():
        for r in records:
            all_records.append(r)
            if r["caseId"] in split_of:
                violations.append(f"{r['caseId']}: duplicate caseId")
            split_of.setdefault(r["caseId"], split)
    seen: dict[str, str] = {}
    for r in all_records:
        key = normalize(r["message"])
        if key in seen:
            violations.append(f"{r['caseId']}: message duplicates {seen[key]} after normalization")
        seen.setdefault(key, r["caseId"])
    token_sets = [tokens(r["message"]) for r in all_records]
    for i, left in enumerate(all_records):
        for j in range(i + 1, len(all_records)):
            right = all_records[j]
            same_group = left["paraphraseGroup"] is not None and left["paraphraseGroup"] == right["paraphraseGroup"]
            similarity = jaccard(token_sets[i], token_sets[j])
            if not same_group and similarity >= NEAR_DUPLICATE_JACCARD:
                violations.append(f"{right['caseId']}: near-duplicate of {left['caseId']} "
                                  f"(token Jaccard {similarity:.2f}) outside a paraphrase group")
    groups: dict[str, list[dict]] = {}
    for r in all_records:
        if r["paraphraseGroup"] is not None:
            groups.setdefault(r["paraphraseGroup"], []).append(r)
    for group in sorted(groups):
        members = groups[group]
        if len(members) < MIN_GROUP_SIZE:
            violations.append(f"{group}: paraphrase group needs at least {MIN_GROUP_SIZE} cases")
        first = members[0]
        for member in members:
            if member["stratum"] != first["stratum"]:
                violations.append(f"{group}: spans strata ({member['caseId']})")
            if split_of[member["caseId"]] != split_of[first["caseId"]]:
                violations.append(f"{group}: straddles dev/test ({member['caseId']})")
            if _label_key(member) != _label_key(first):
                violations.append(f"{group}: labels differ between {first['caseId']} and {member['caseId']}")
    if not MIN_TOTAL <= len(all_records) <= MAX_TOTAL:
        violations.append(f"dataset size {len(all_records)} outside [{MIN_TOTAL}, {MAX_TOTAL}]")
    counts = Counter(r["stratum"] for r in all_records)
    for stratum in STRATA:
        if counts[stratum] < MIN_PER_STRATUM:
            violations.append(f"stratum {stratum} has {counts[stratum]} cases, needs at least {MIN_PER_STRATUM}")
    return violations


# ---------------------------------------------------------------- split and serialization


def split_unit(record: dict) -> str:
    return record["paraphraseGroup"] or record["caseId"]


def split_target(size: int, fraction: float) -> int:
    return math.floor(fraction * size + 0.5)


def assign_split(records: list[dict], seed: str, fraction: float) -> dict[str, str]:
    """Mirrors GoldsetSplitter.assign."""
    if not seed.strip():
        raise ValueError("seed must not be blank")
    if not 0.0 < fraction < 1.0:
        raise ValueError("testFraction must be within (0, 1)")
    units: dict[str, dict[str, list[dict]]] = {}
    unit_stratum: dict[str, str] = {}
    for r in records:
        previous = unit_stratum.setdefault(split_unit(r), r["stratum"])
        if previous != r["stratum"]:
            raise ValueError(f"split unit {split_unit(r)} spans strata {previous} and {r['stratum']}")
        units.setdefault(r["stratum"], {}).setdefault(split_unit(r), []).append(r)
    result: dict[str, str] = {}
    for by_unit in units.values():
        size = sum(len(members) for members in by_unit.values())
        target = split_target(size, fraction)
        ordered = sorted(by_unit, key=lambda u: (hashlib.sha256(f"{seed}:{u}".encode()).hexdigest(), u))
        test_count = 0
        for unit in ordered:
            split = "TEST" if test_count < target else "DEV"
            if split == "TEST":
                test_count += len(by_unit[unit])
            for r in by_unit[unit]:
                result[r["caseId"]] = split
    return result


def canonical_line(record: dict) -> str:
    ordered = OrderedDict((f, record[f]) for f in FIELDS)
    ordered["alternatives"] = OrderedDict((f, record["alternatives"][f]) for f in ALTERNATIVE_FIELDS)
    return json.dumps(ordered, ensure_ascii=True, separators=(",", ":"))


def canonical_bytes(records: list[dict]) -> bytes:
    return "".join(canonical_line(r) + "\n" for r in sorted(records, key=lambda r: r["caseId"])).encode("ascii")


def build_manifest(splits: dict[str, list[dict]], taxonomy_version: str, seed: str, fraction: float) -> OrderedDict:
    files = OrderedDict()
    for split, name in SPLIT_FILES.items():
        files[split] = OrderedDict([("path", name), ("records", len(splits[split])),
                                    ("sha256", hashlib.sha256(canonical_bytes(splits[split])).hexdigest())])
    strata = OrderedDict()
    for stratum in STRATA:
        strata[stratum] = OrderedDict(
            [("dev", sum(r["stratum"] == stratum for r in splits["DEV"])),
             ("test", sum(r["stratum"] == stratum for r in splits["TEST"]))])
    return OrderedDict([
        ("datasetId", DATASET_ID), ("version", "v1"), ("taxonomyVersion", taxonomy_version), ("synthetic", True),
        ("labelSource", "SYNTHETIC_AUTHOR"),
        ("split", OrderedDict([("algorithm", SPLIT_ALGORITHM), ("seed", seed), ("testFraction", fraction)])),
        ("dev", files["DEV"]), ("test", files["TEST"]),
        ("totalRecords", len(splits["DEV"]) + len(splits["TEST"])), ("strata", strata),
    ])


def manifest_text(manifest: dict) -> str:
    return json.dumps(manifest, ensure_ascii=True, indent=2) + "\n"


# ---------------------------------------------------------------- commands


def load_dataset(directory: Path, taxonomy: dict) -> dict[str, list[dict]]:
    splits, violations = {}, []
    for split, name in SPLIT_FILES.items():
        try:
            splits[split] = load_file(directory / name, taxonomy)
        except GoldsetError as exc:
            violations += exc.violations
    if violations:
        raise GoldsetError(violations)
    return splits


def validate(directory: Path, taxonomy: dict) -> dict[str, list[dict]]:
    splits = load_dataset(directory, taxonomy)
    violations = dataset_errors(splits)
    if violations:
        raise GoldsetError(violations)
    return splits


def verify(directory: Path, taxonomy: dict) -> dict[str, list[dict]]:
    splits = validate(directory, taxonomy)
    manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    violations = []
    recipe = manifest["split"]
    if recipe["algorithm"] != SPLIT_ALGORITHM:
        violations.append(f"{MANIFEST}: unsupported split algorithm {recipe['algorithm']}")
    expected = build_manifest(splits, taxonomy["version"], recipe["seed"], recipe["testFraction"])
    if manifest_text(manifest) != manifest_text(expected):
        violations.append(f"{MANIFEST}: does not match the files (hash, counts or metadata changed); re-freeze")
    for split, name in SPLIT_FILES.items():
        if (directory / name).read_bytes() != canonical_bytes(splits[split]):
            violations.append(f"{name}: not in canonical form (key order, sorting or encoding); re-freeze")
    assignment = assign_split(splits["DEV"] + splits["TEST"], recipe["seed"], recipe["testFraction"])
    for split, records in splits.items():
        for r in records:
            if assignment[r["caseId"]] != split:
                violations.append(f"{r['caseId']}: in {split} but the frozen recipe assigns {assignment[r['caseId']]}")
    if violations:
        raise GoldsetError(violations)
    return splits


def freeze(inputs: list[Path], out: Path, taxonomy: dict, seed: str, fraction: float) -> None:
    records, violations = [], []
    for path in inputs:
        try:
            records += load_file(path, taxonomy)
        except GoldsetError as exc:
            violations += exc.violations
    if violations:
        raise GoldsetError(violations)
    assignment = assign_split(records, seed, fraction)
    splits = {split: [r for r in records if assignment[r["caseId"]] == split] for split in SPLIT_FILES}
    violations = dataset_errors(splits)
    if violations:
        raise GoldsetError(violations)
    payload = {name: canonical_bytes(splits[split]) for split, name in SPLIT_FILES.items()}
    out.mkdir(parents=True, exist_ok=True)
    for name, data in payload.items():
        (out / name).write_bytes(data)
    (out / MANIFEST).write_bytes(manifest_text(build_manifest(splits, taxonomy["version"], seed, fraction))
                                 .encode("ascii"))
    verify(out, taxonomy)


def coverage(splits: dict[str, list[dict]]) -> str:
    all_records = splits["DEV"] + splits["TEST"]
    lines = [f"# Goldset coverage ({len(all_records)} cases: dev {len(splits['DEV'])}, test {len(splits['TEST'])})",
             "", "| Stratum | Dev | Test | Total | Test % |", "|---|---:|---:|---:|---:|"]
    for stratum in STRATA:
        dev = sum(r["stratum"] == stratum for r in splits["DEV"])
        test = sum(r["stratum"] == stratum for r in splits["TEST"])
        total = dev + test
        lines.append(f"| {stratum} | {dev} | {test} | {total} | {100 * test / total if total else 0:.0f} |")
    for field in ("intent", "domain", "requestedOutcome", "formalSrType", "reasonCode", "recommendedRoute",
                  "channel"):
        counts = Counter(r[field] for r in all_records)
        test_counts = Counter(r[field] for r in splits["TEST"])
        lines += ["", f"| {field} | Total | Test |", "|---|---:|---:|"]
        lines += [f"| {value} | {count} | {test_counts[value]} |" for value, count in sorted(counts.items())]
    groups = {r["paraphraseGroup"] for r in all_records if r["paraphraseGroup"]}
    lines += ["", "| Signal | Cases |", "|---|---:|",
              f"| multi-intent (secondaryIntents) | {sum(bool(r['secondaryIntents']) for r in all_records)} |",
              f"| with alternative labels | "
              f"{sum(any(r['alternatives'][f] for f in ALTERNATIVE_FIELDS) for r in all_records)} |",
              f"| requiresHumanReview | {sum(r['requiresHumanReview'] for r in all_records)} |",
              f"| in paraphrase groups ({len(groups)} groups) | "
              f"{sum(r['paraphraseGroup'] is not None for r in all_records)} |"]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--taxonomy", type=Path, default=DEFAULT_TAXONOMY)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "verify", "coverage"):
        sub.add_parser(name).add_argument("directory", type=Path)
    freeze_parser = sub.add_parser("freeze")
    freeze_parser.add_argument("inputs", type=Path, nargs="+")
    freeze_parser.add_argument("--out", type=Path, required=True)
    freeze_parser.add_argument("--seed", default=DEFAULT_SEED)
    freeze_parser.add_argument("--test-fraction", type=float, default=DEFAULT_TEST_FRACTION)
    args = parser.parse_args(argv)
    try:
        taxonomy = load_taxonomy(args.taxonomy)
        if args.command == "validate":
            validate(args.directory, taxonomy)
            print(f"[OK] goldset valid: {args.directory}")
        elif args.command == "verify":
            splits = verify(args.directory, taxonomy)
            print(f"[OK] goldset verified: {args.directory} (dev {len(splits['DEV'])}, test {len(splits['TEST'])})")
        elif args.command == "coverage":
            sys.stdout.write(coverage(validate(args.directory, taxonomy)))
        else:
            freeze(args.inputs, args.out, taxonomy, args.seed, args.test_fraction)
            print(f"[OK] goldset frozen into {args.out}")
    except GoldsetError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        for violation in exc.violations:
            print(f"  {violation}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError) as exc:
        print(f"[FAIL] {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
