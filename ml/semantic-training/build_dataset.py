#!/usr/bin/env python3
"""Build the deterministic AlarmControl semantic-intent training dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from coupled_artifact_publisher import publish_coupled_files

SCHEMA_VERSION = "semantic-family-v1"
MANIFEST_VERSION = "semantic-dataset-manifest-v6"
SOURCE_KIND = "codex-synthetic"
SEED = "alarmcontrol-semantic-dataset-v6"

LOCALES = ("ko", "en", "mixed")
INTENTS = (
    "MARKETING",
    "TRANSACTIONAL",
    "SECURITY",
    "DELIVERY",
    "SOCIAL",
    "OTHER",
    "AMBIGUOUS",
)
FAMILIES_PER_INTENT_BY_LOCALE = {
    "ko": 190,
    "en": 250,
    "mixed": 190,
}
SPLIT_FAMILY_COUNTS_BY_LOCALE = {
    "ko": (("train", 152), ("validation", 19), ("test", 19)),
    "en": (("train", 200), ("validation", 25), ("test", 25)),
    "mixed": (("train", 152), ("validation", 19), ("test", 19)),
}
SPLITS = ("train", "validation", "test")
CATALOG_FILENAMES = {
    "ko": (
        "ko_families.jsonl",
        "ko_augmented_families.jsonl",
        "ko_boundary_v4_families.jsonl",
        "ko_generalization_v5_families.jsonl",
        "ko_precision_v6_families.jsonl",
    ),
    "en": (
        "en_families.jsonl",
        "en_augmented_families.jsonl",
        "en_generalization_a_families.jsonl",
        "en_generalization_b_families.jsonl",
        "en_boundary_v4_families.jsonl",
        "en_generalization_v5_families.jsonl",
        "en_precision_v6_families.jsonl",
    ),
    "mixed": (
        "mixed_families.jsonl",
        "mixed_augmented_families.jsonl",
        "mixed_boundary_v4_families.jsonl",
        "mixed_generalization_v5_families.jsonl",
        "mixed_precision_v6_families.jsonl",
    ),
}
CATALOG_FIELDS = {
    "schema_version",
    "locale",
    "intent",
    "family_id",
    "variants",
    "injections",
}
VARIANT_FIELDS = {"title", "body"}
DATASET_FIELDS = {
    "id",
    "locale",
    "intent",
    "family_id",
    "pair_id",
    "split",
    "injection",
    "title",
    "body",
    "source_kind",
}
FORBIDDEN_METADATA_FIELDS = {
    "address",
    "app",
    "app_id",
    "app_name",
    "channel",
    "channel_id",
    "device_id",
    "email",
    "notification_id",
    "notification_key",
    "package",
    "package_id",
    "package_name",
    "phone",
    "phone_number",
    "posted_at",
    "sender",
    "timestamp",
    "token",
    "user_id",
}

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_CATALOG_DIR = BASE_DIR / "catalog"
DEFAULT_OUTPUT_DIR = BASE_DIR / "artifacts" / "dataset-v6"


class DatasetValidationError(ValueError):
    """Raised when a source catalog or rendered dataset violates its contract."""


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DatasetValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json_line(line: str, path: Path, line_number: int) -> dict[str, Any]:
    try:
        value = json.loads(line, object_pairs_hook=_strict_object)
    except (json.JSONDecodeError, DatasetValidationError) as error:
        raise DatasetValidationError(
            f"{path}:{line_number}: invalid JSON: {error}"
        ) from error
    if not isinstance(value, dict):
        raise DatasetValidationError(
            f"{path}:{line_number}: each line must contain one JSON object"
        )
    return value


def _normalized_field_name(field: str) -> str:
    output: list[str] = []
    for character in field:
        if character.isupper() and output:
            output.append("_")
        output.append(character.lower())
    return "".join(output).replace("-", "_")


def _reject_forbidden_metadata(value: Any, context: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if _normalized_field_name(key) in FORBIDDEN_METADATA_FIELDS:
                raise DatasetValidationError(
                    f"{context}: forbidden real-data/package metadata field: {key}"
                )
            _reject_forbidden_metadata(child, context)
    elif isinstance(value, list):
        for child in value:
            _reject_forbidden_metadata(child, context)


def _require_exact_fields(
    value: dict[str, Any],
    expected: set[str],
    context: str,
) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        raise DatasetValidationError(
            f"{context}: schema fields mismatch; "
            f"missing={missing}, unexpected={unexpected}"
        )


def _require_nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise DatasetValidationError(f"{context}: must be a nonempty string")
    return value


def _contains_wrong_label(text: str, true_intent: str) -> bool:
    return any(
        intent != true_intent
        and re.search(
            rf"(?<![A-Z_]){re.escape(intent)}(?![A-Z_])",
            text.upper(),
        )
        for intent in INTENTS
    )


def _validate_catalog_record(
    record: dict[str, Any],
    expected_locale: str,
    context: str,
) -> None:
    _reject_forbidden_metadata(record, context)
    _require_exact_fields(record, CATALOG_FIELDS, context)
    if record["schema_version"] != SCHEMA_VERSION:
        raise DatasetValidationError(
            f"{context}: schema_version must be {SCHEMA_VERSION!r}"
        )
    if record["locale"] not in LOCALES or record["locale"] != expected_locale:
        raise DatasetValidationError(
            f"{context}: locale must match catalog locale {expected_locale!r}"
        )
    if record["intent"] not in INTENTS:
        raise DatasetValidationError(f"{context}: unsupported intent")
    _require_nonempty_string(record["family_id"], f"{context}.family_id")

    variants = record["variants"]
    if not isinstance(variants, list) or len(variants) != 3:
        raise DatasetValidationError(
            f"{context}.variants: exactly three variants are required"
        )
    for index, variant in enumerate(variants):
        variant_context = f"{context}.variants[{index}]"
        if not isinstance(variant, dict):
            raise DatasetValidationError(
                f"{variant_context}: must be an object"
            )
        _reject_forbidden_metadata(variant, variant_context)
        _require_exact_fields(variant, VARIANT_FIELDS, variant_context)
        _require_nonempty_string(
            variant["title"], f"{variant_context}.title"
        )
        _require_nonempty_string(
            variant["body"], f"{variant_context}.body"
        )

    injections = record["injections"]
    if not isinstance(injections, list) or len(injections) != 3:
        raise DatasetValidationError(
            f"{context}.injections: exactly three injections are required"
        )
    for index, injection in enumerate(injections):
        _require_nonempty_string(
            injection, f"{context}.injections[{index}]"
        )
        if not _contains_wrong_label(injection, record["intent"]):
            raise DatasetValidationError(
                f"{context}.injections[{index}]: must name a different "
                "semantic intent"
            )


def _load_and_validate_catalogs_snapshot(
    catalog_dir: Path,
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    """Validate catalogs and retain provenance from the exact parsed bytes."""

    records: list[dict[str, Any]] = []
    catalog_hashes: dict[str, dict[str, Any]] = {}
    family_ids: set[str] = set()
    notification_texts: set[tuple[str, str]] = set()
    group_counts: Counter[tuple[str, str]] = Counter()

    for locale in LOCALES:
        for filename in CATALOG_FILENAMES[locale]:
            path = catalog_dir / filename
            if not path.is_file():
                raise DatasetValidationError(f"missing catalog: {path}")
            content = path.read_bytes()
            lines = content.decode("utf-8").splitlines()
            catalog_hashes[f"catalog/{filename}"] = {
                "sha256": _sha256_bytes(content),
                "families": len(content.splitlines()),
            }
            if any(not line.strip() for line in lines):
                raise DatasetValidationError(
                    f"{path}: blank JSONL lines are not allowed"
                )

            for line_number, line in enumerate(lines, start=1):
                context = f"{path}:{line_number}"
                record = _load_json_line(line, path, line_number)
                _validate_catalog_record(record, locale, context)

                family_id = record["family_id"]
                if family_id in family_ids:
                    raise DatasetValidationError(
                        f"{context}: duplicate family_id: {family_id}"
                    )
                family_ids.add(family_id)

                for variant in record["variants"]:
                    text_key = (
                        variant["title"].strip(),
                        variant["body"].strip(),
                    )
                    if text_key in notification_texts:
                        raise DatasetValidationError(
                            f"{context}: duplicate notification title/body text"
                        )
                    notification_texts.add(text_key)

                group_counts[(locale, record["intent"])] += 1
                records.append(record)

    expected_groups = {(locale, intent) for locale in LOCALES for intent in INTENTS}
    if set(group_counts) != expected_groups:
        missing = sorted(expected_groups - set(group_counts))
        unexpected = sorted(set(group_counts) - expected_groups)
        raise DatasetValidationError(
            f"intent-locale groups mismatch; missing={missing}, unexpected={unexpected}"
        )
    malformed = {
        f"{locale}/{intent}": count
        for (locale, intent), count in sorted(group_counts.items())
        if count != FAMILIES_PER_INTENT_BY_LOCALE[locale]
    }
    if malformed:
        raise DatasetValidationError(
            "intent-locale family counts do not match the contract: "
            f"{malformed}"
        )
    expected_total = (
        sum(FAMILIES_PER_INTENT_BY_LOCALE.values()) * len(INTENTS)
    )
    if len(records) != expected_total:
        raise DatasetValidationError(
            f"catalogs must contain exactly {expected_total} families, "
            f"found {len(records)}"
        )
    return records, catalog_hashes


def load_and_validate_catalogs(catalog_dir: Path) -> list[dict[str, Any]]:
    """Load all source catalogs and enforce the semantic-family-v1 contract."""

    return _load_and_validate_catalogs_snapshot(catalog_dir)[0]


def _family_order_key(record: dict[str, Any]) -> bytes:
    material = (
        f"{SEED}\0{record['locale']}\0{record['intent']}\0"
        f"{record['family_id']}"
    )
    return hashlib.sha256(material.encode("utf-8")).digest()


def assign_splits(
    records: Iterable[dict[str, Any]],
) -> dict[str, str]:
    """Assign whole families to exact deterministic train/validation/test splits."""

    grouped: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        grouped[(record["locale"], record["intent"])].append(record)

    assignments: dict[str, str] = {}
    for locale in LOCALES:
        for intent in INTENTS:
            group = sorted(grouped[(locale, intent)], key=_family_order_key)
            expected_family_count = FAMILIES_PER_INTENT_BY_LOCALE[locale]
            if len(group) != expected_family_count:
                raise DatasetValidationError(
                    f"{locale}/{intent}: expected "
                    f"{expected_family_count} families before splitting"
                )
            cursor = 0
            for split, size in SPLIT_FAMILY_COUNTS_BY_LOCALE[locale]:
                for record in group[cursor : cursor + size]:
                    family_id = record["family_id"]
                    if family_id in assignments:
                        raise DatasetValidationError(
                            f"split leakage or duplicate family: {family_id}"
                        )
                    assignments[family_id] = split
                cursor += size
            if cursor != len(group):
                raise DatasetValidationError(
                    f"{locale}/{intent}: split sizes did not consume all families"
                )
    return assignments


def render_dataset_rows(
    records: Iterable[dict[str, Any]],
    assignments: dict[str, str],
) -> list[dict[str, Any]]:
    """Render each clean variant and its paired injection twin."""

    by_group: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        by_group[(record["locale"], record["intent"])].append(record)

    rows: list[dict[str, Any]] = []
    for locale in LOCALES:
        for intent in INTENTS:
            families = sorted(
                by_group[(locale, intent)],
                key=_family_order_key,
            )
            for family in families:
                family_id = family["family_id"]
                split = assignments[family_id]
                for index, (variant, injection_text) in enumerate(
                    zip(family["variants"], family["injections"], strict=True),
                    start=1,
                ):
                    pair_id = f"{family_id}:v{index}"
                    rows.append(
                        {
                            "id": f"{pair_id}:clean",
                            "locale": locale,
                            "intent": intent,
                            "family_id": family_id,
                            "pair_id": pair_id,
                            "split": split,
                            "injection": False,
                            "title": variant["title"],
                            "body": variant["body"],
                            "source_kind": SOURCE_KIND,
                        }
                    )
                    rows.append(
                        {
                            "id": f"{pair_id}:injected",
                            "locale": locale,
                            "intent": intent,
                            "family_id": family_id,
                            "pair_id": pair_id,
                            "split": split,
                            "injection": True,
                            "title": variant["title"],
                            "body": f"{variant['body']}\n\n{injection_text}",
                            "source_kind": SOURCE_KIND,
                        }
                    )
    validate_dataset_rows(rows)
    return rows


def validate_dataset_rows(rows: list[dict[str, Any]]) -> None:
    """Validate rendered rows, pair integrity, counts, and split isolation."""

    expected_rows = (
        sum(FAMILIES_PER_INTENT_BY_LOCALE.values())
        * len(INTENTS)
        * 3
        * 2
    )
    if len(rows) != expected_rows:
        raise DatasetValidationError(
            f"dataset must contain exactly {expected_rows:,} rows, "
            f"found {len(rows)}"
        )

    ids: set[str] = set()
    texts: set[tuple[str, str]] = set()
    pairs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    family_splits: dict[str, set[str]] = defaultdict(set)
    family_groups: dict[tuple[str, str, str], set[str]] = defaultdict(set)

    for index, row in enumerate(rows, start=1):
        context = f"dataset row {index}"
        _reject_forbidden_metadata(row, context)
        _require_exact_fields(row, DATASET_FIELDS, context)
        _require_nonempty_string(row["id"], f"{context}.id")
        _require_nonempty_string(row["family_id"], f"{context}.family_id")
        _require_nonempty_string(row["pair_id"], f"{context}.pair_id")
        _require_nonempty_string(row["title"], f"{context}.title")
        _require_nonempty_string(row["body"], f"{context}.body")
        if row["locale"] not in LOCALES or row["intent"] not in INTENTS:
            raise DatasetValidationError(f"{context}: invalid locale or intent")
        if row["split"] not in SPLITS:
            raise DatasetValidationError(f"{context}: invalid split")
        if type(row["injection"]) is not bool:
            raise DatasetValidationError(
                f"{context}.injection: must be a boolean"
            )
        if row["source_kind"] != SOURCE_KIND:
            raise DatasetValidationError(
                f"{context}.source_kind: invalid source marker"
            )
        if row["id"] in ids:
            raise DatasetValidationError(f"{context}: duplicate id")
        ids.add(row["id"])
        text_key = (row["title"].strip(), row["body"].strip())
        if text_key in texts:
            raise DatasetValidationError(
                f"{context}: duplicate notification title/body text"
            )
        texts.add(text_key)
        pairs[row["pair_id"]].append(row)
        family_splits[row["family_id"]].add(row["split"])
        family_groups[(row["locale"], row["intent"], row["split"])].add(
            row["family_id"]
        )

    expected_pairs = expected_rows // 2
    if len(pairs) != expected_pairs:
        raise DatasetValidationError(
            f"dataset must contain exactly {expected_pairs:,} pairs, "
            f"found {len(pairs)}"
        )
    for pair_id, pair_rows in pairs.items():
        if len(pair_rows) != 2:
            raise DatasetValidationError(
                f"{pair_id}: each pair must contain exactly two rows"
            )
        clean = next(
            (row for row in pair_rows if not row["injection"]), None
        )
        injected = next(
            (row for row in pair_rows if row["injection"]), None
        )
        if clean is None or injected is None:
            raise DatasetValidationError(
                f"{pair_id}: pair needs one clean and one injected row"
            )
        invariant_fields = (
            "locale",
            "intent",
            "family_id",
            "pair_id",
            "split",
            "title",
            "source_kind",
        )
        if any(clean[field] != injected[field] for field in invariant_fields):
            raise DatasetValidationError(
                f"{pair_id}: injected twin changed paired metadata"
            )
        if not injected["body"].startswith(f"{clean['body']}\n\n"):
            raise DatasetValidationError(
                f"{pair_id}: injected body must append to the clean body"
            )
        if len(injected["body"]) <= len(clean["body"]) + 2:
            raise DatasetValidationError(
                f"{pair_id}: appended injection text is empty"
            )
        appended_text = injected["body"][len(clean["body"]) + 2 :]
        if not _contains_wrong_label(appended_text, clean["intent"]):
            raise DatasetValidationError(
                f"{pair_id}: appended injection must name a different "
                "semantic intent"
            )

    leaking = {
        family_id: sorted(splits)
        for family_id, splits in family_splits.items()
        if len(splits) != 1
    }
    if leaking:
        raise DatasetValidationError(f"family split leakage detected: {leaking}")

    for locale in LOCALES:
        expected_split_counts = dict(
            SPLIT_FAMILY_COUNTS_BY_LOCALE[locale]
        )
        for intent in INTENTS:
            for split, expected in expected_split_counts.items():
                actual = len(family_groups[(locale, intent, split)])
                if actual != expected:
                    raise DatasetValidationError(
                        f"{locale}/{intent}/{split}: expected "
                        f"{expected} families, found {actual}"
                    )


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _jsonl_bytes(rows: Iterable[dict[str, Any]]) -> bytes:
    text = "".join(
        json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
        for row in rows
    )
    return text.encode("utf-8")


def build_dataset(
    catalog_dir: Path = DEFAULT_CATALOG_DIR,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
) -> dict[str, Any]:
    """Validate catalogs, render the dataset, and write its manifest."""

    records, catalog_hashes = _load_and_validate_catalogs_snapshot(catalog_dir)
    assignments = assign_splits(records)
    rows = render_dataset_rows(records, assignments)
    dataset_bytes = _jsonl_bytes(rows)

    row_counts_by_locale = Counter(row["locale"] for row in rows)
    row_counts_by_intent = Counter(row["intent"] for row in rows)
    row_counts_by_split = Counter(row["split"] for row in rows)
    family_counts_by_split = Counter(assignments.values())

    manifest: dict[str, Any] = {
        "schema_version": MANIFEST_VERSION,
        "seed": SEED,
        "source_kind": SOURCE_KIND,
        "row_count": len(rows),
        "family_count": len(records),
        "pair_count": len(rows) // 2,
        "counts": {
            "rows_by_locale": {
                locale: row_counts_by_locale[locale] for locale in LOCALES
            },
            "rows_by_intent": {
                intent: row_counts_by_intent[intent] for intent in INTENTS
            },
            "rows_by_split": {
                split: row_counts_by_split[split] for split in SPLITS
            },
            "families_by_split": {
                split: family_counts_by_split[split] for split in SPLITS
            },
            "rows_by_injection": {
                "clean": sum(not row["injection"] for row in rows),
                "injected": sum(row["injection"] for row in rows),
            },
        },
        "files": {
            "dataset.jsonl": {
                "sha256": _sha256_bytes(dataset_bytes),
                "rows": len(rows),
            },
            **catalog_hashes,
        },
    }
    manifest_bytes = (
        json.dumps(
            manifest,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8")

    publish_coupled_files(
        {
            output_dir / "dataset.jsonl": dataset_bytes,
            output_dir / "manifest.json": manifest_bytes,
        },
        lock_name=".dataset.publish.lock",
    )
    return manifest


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the deterministic synthetic semantic dataset."
    )
    parser.add_argument(
        "--catalog-dir",
        type=Path,
        default=DEFAULT_CATALOG_DIR,
        help=f"source catalog directory (default: {DEFAULT_CATALOG_DIR})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"output directory (default: {DEFAULT_OUTPUT_DIR})",
    )
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    manifest = build_dataset(args.catalog_dir, args.output_dir)
    print(
        f"wrote {manifest['row_count']} rows and "
        f"{manifest['pair_count']} pairs to {args.output_dir}"
    )


if __name__ == "__main__":
    main()
