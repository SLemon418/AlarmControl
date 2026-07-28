#!/usr/bin/env python3
"""Validate the sealed semantic holdout without exposing or merging examples."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

HOLDOUT_SCHEMA_VERSION = "semantic-sealed-holdout-v1"
MANIFEST_SCHEMA_VERSION = "semantic-sealed-holdout-manifest-v1"
CATALOG_SCHEMA_VERSION = "semantic-family-v1"
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
HOLDOUT_FILENAMES = {
    "ko": "ko_holdout.jsonl",
    "en": "en_holdout.jsonl",
    "mixed": "mixed_holdout.jsonl",
}
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
HOLDOUT_FIELDS = {
    "schema_version",
    "id",
    "locale",
    "intent",
    "pair_id",
    "injection",
    "title",
    "body",
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
    "profile_id",
    "sender",
    "timestamp",
    "token",
    "user",
    "user_id",
    "username",
}

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_HOLDOUT_DIR = BASE_DIR / "holdout"
DEFAULT_CATALOG_DIR = BASE_DIR / "catalog"
DEFAULT_MANIFEST_PATH = (
    BASE_DIR / "artifacts" / "sealed-holdout-v1" / "manifest.json"
)


class HoldoutValidationError(ValueError):
    """Raised when sealed holdout data violates its contract."""


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise HoldoutValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


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
                raise HoldoutValidationError(
                    f"{context}: forbidden package/channel/user metadata field: {key}"
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
        raise HoldoutValidationError(
            f"{context}: schema fields mismatch; "
            f"missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )


def _require_nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise HoldoutValidationError(f"{context}: must be a nonempty string")
    return value


def _normalized_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _read_jsonl(path: Path) -> tuple[bytes, list[dict[str, Any]]]:
    if path.is_symlink() or not path.is_file():
        raise HoldoutValidationError(f"missing regular JSONL file: {path}")
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise HoldoutValidationError(f"{path}: must be UTF-8") from error
    lines = text.splitlines()
    if not lines:
        raise HoldoutValidationError(f"{path}: file must not be empty")
    if any(not line.strip() for line in lines):
        raise HoldoutValidationError(f"{path}: blank JSONL lines are not allowed")

    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, start=1):
        try:
            value = json.loads(line, object_pairs_hook=_strict_object)
        except (json.JSONDecodeError, HoldoutValidationError) as error:
            raise HoldoutValidationError(
                f"{path}:{line_number}: invalid JSON: {error}"
            ) from error
        if not isinstance(value, dict):
            raise HoldoutValidationError(
                f"{path}:{line_number}: each line must be one JSON object"
            )
        rows.append(value)
    return raw, rows


def _validate_catalog_record(
    record: dict[str, Any],
    locale: str,
    context: str,
) -> None:
    _reject_forbidden_metadata(record, context)
    _require_exact_fields(record, CATALOG_FIELDS, context)
    if record["schema_version"] != CATALOG_SCHEMA_VERSION:
        raise HoldoutValidationError(
            f"{context}: schema_version must be {CATALOG_SCHEMA_VERSION!r}"
        )
    if record["locale"] != locale:
        raise HoldoutValidationError(
            f"{context}: locale must match catalog locale {locale!r}"
        )
    if record["intent"] not in INTENTS:
        raise HoldoutValidationError(f"{context}: unsupported catalog intent")
    _require_nonempty_string(record["family_id"], f"{context}.family_id")

    variants = record["variants"]
    if not isinstance(variants, list) or len(variants) != 3:
        raise HoldoutValidationError(
            f"{context}.variants: exactly three variants are required"
        )
    for index, variant in enumerate(variants):
        variant_context = f"{context}.variants[{index}]"
        if not isinstance(variant, dict):
            raise HoldoutValidationError(f"{variant_context}: must be an object")
        _require_exact_fields(variant, VARIANT_FIELDS, variant_context)
        _require_nonempty_string(variant["title"], f"{variant_context}.title")
        _require_nonempty_string(variant["body"], f"{variant_context}.body")

    injections = record["injections"]
    if not isinstance(injections, list) or len(injections) != 3:
        raise HoldoutValidationError(
            f"{context}.injections: exactly three strings are required"
        )
    for index, injection in enumerate(injections):
        _require_nonempty_string(
            injection,
            f"{context}.injections[{index}]",
        )


def _load_catalog_index(
    catalog_dir: Path,
) -> tuple[
    set[str],
    set[str],
    set[str],
]:
    titles: set[str] = set()
    bodies: set[str] = set()
    family_ids: set[str] = set()

    for locale in LOCALES:
        for filename in CATALOG_FILENAMES[locale]:
            path = catalog_dir / filename
            _, records = _read_jsonl(path)
            for line_number, record in enumerate(records, start=1):
                context = f"{path}:{line_number}"
                _validate_catalog_record(record, locale, context)
                family_id = record["family_id"].strip()
                if family_id in family_ids:
                    raise HoldoutValidationError(
                        f"{context}: duplicate training family_id: {family_id}"
                    )
                family_ids.add(family_id)
                for variant in record["variants"]:
                    titles.add(_normalized_text(variant["title"]))
                    bodies.add(_normalized_text(variant["body"]))
    return titles, bodies, family_ids


def _validate_holdout_record(
    record: dict[str, Any],
    locale: str,
    context: str,
) -> None:
    _reject_forbidden_metadata(record, context)
    _require_exact_fields(record, HOLDOUT_FIELDS, context)
    if record["schema_version"] != HOLDOUT_SCHEMA_VERSION:
        raise HoldoutValidationError(
            f"{context}: schema_version must be {HOLDOUT_SCHEMA_VERSION!r}"
        )
    if record["locale"] != locale:
        raise HoldoutValidationError(
            f"{context}: locale must match holdout locale {locale!r}"
        )
    if record["intent"] not in INTENTS:
        raise HoldoutValidationError(f"{context}: unsupported intent")
    _require_nonempty_string(record["id"], f"{context}.id")
    _require_nonempty_string(record["pair_id"], f"{context}.pair_id")
    _require_nonempty_string(record["title"], f"{context}.title")
    _require_nonempty_string(record["body"], f"{context}.body")
    if type(record["injection"]) is not bool:
        raise HoldoutValidationError(
            f"{context}.injection: must be a JSON boolean"
        )


def _contains_wrong_label(text: str, true_intent: str) -> bool:
    return any(
        intent != true_intent
        and re.search(
            rf"(?<![A-Z_]){re.escape(intent)}(?![A-Z_])",
            text.upper(),
        )
        for intent in INTENTS
    )


def _validate_pairs(
    pairs: Mapping[str, Sequence[dict[str, Any]]],
    training_family_ids: set[str],
) -> Counter[tuple[str, str, bool]]:
    pair_counts: Counter[tuple[str, str, bool]] = Counter()
    for pair_id, pair in pairs.items():
        if len(pair) != 2:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: exactly two rows are required"
            )
        if pair_id in training_family_ids:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: collides with a training family_id"
            )
        if len({row["locale"] for row in pair}) != 1:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: locale must match within the pair"
            )
        if len({row["intent"] for row in pair}) != 1:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: intent must match within the pair"
            )
        clean_rows = [row for row in pair if not row["injection"]]
        injected_rows = [row for row in pair if row["injection"]]
        if len(clean_rows) != 1 or len(injected_rows) != 1:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: one clean and one injected row are required"
            )
        clean = clean_rows[0]
        injected = injected_rows[0]
        if clean["title"] != injected["title"]:
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: injected title must retain the clean title"
            )
        if not injected["body"].startswith(clean["body"]):
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: injected body must retain the clean body"
            )
        added_text = injected["body"][len(clean["body"]) :]
        if not added_text.strip():
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: injected body must add instruction text"
            )
        if not _contains_wrong_label(added_text, clean["intent"]):
            raise HoldoutValidationError(
                f"pair_id {pair_id!r}: added text must force a different intent"
            )
        pair_counts[(clean["locale"], clean["intent"], False)] += 1
        pair_counts[(injected["locale"], injected["intent"], True)] += 1
    return pair_counts


def validate_holdouts(
    holdout_dir: Path,
    catalog_dir: Path,
) -> tuple[dict[str, Any], dict[str, bytes]]:
    """Validate all sealed holdouts and return aggregate-only manifest data."""

    training_titles, training_bodies, training_family_ids = (
        _load_catalog_index(catalog_dir)
    )
    all_rows: list[dict[str, Any]] = []
    raw_files: dict[str, bytes] = {}
    ids: set[str] = set()
    text_pairs: set[tuple[str, str]] = set()
    pairs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    row_counts_by_locale: Counter[str] = Counter()
    row_counts_by_locale_intent: Counter[tuple[str, str]] = Counter()

    for locale in LOCALES:
        filename = HOLDOUT_FILENAMES[locale]
        path = holdout_dir / filename
        raw, rows = _read_jsonl(path)
        if len(rows) != 140:
            raise HoldoutValidationError(
                f"{path}: expected exactly 140 rows, found {len(rows)}"
            )
        raw_files[filename] = raw
        for line_number, row in enumerate(rows, start=1):
            context = f"{path}:{line_number}"
            _validate_holdout_record(row, locale, context)
            row_id = row["id"].strip()
            pair_id = row["pair_id"].strip()
            if row_id in ids:
                raise HoldoutValidationError(
                    f"{context}: duplicate holdout id: {row_id}"
                )
            if row_id in training_family_ids:
                raise HoldoutValidationError(
                    f"{context}: holdout id collides with a training family_id"
                )
            ids.add(row_id)

            text_key = (
                _normalized_text(row["title"]),
                _normalized_text(row["body"]),
            )
            if text_key in text_pairs:
                raise HoldoutValidationError(
                    f"{context}: duplicate notification title/body text"
                )
            text_pairs.add(text_key)

            if not row["injection"]:
                if _normalized_text(row["title"]) in training_titles:
                    raise HoldoutValidationError(
                        f"{context}: clean title overlaps training catalog"
                    )
                if _normalized_text(row["body"]) in training_bodies:
                    raise HoldoutValidationError(
                        f"{context}: clean body overlaps training catalog"
                    )

            pairs[pair_id].append(row)
            row_counts_by_locale[locale] += 1
            row_counts_by_locale_intent[(locale, row["intent"])] += 1
            all_rows.append(row)

    if len(all_rows) != 420:
        raise HoldoutValidationError(
            f"sealed holdout must contain exactly 420 rows, found {len(all_rows)}"
        )
    malformed_locales = {
        locale: row_counts_by_locale[locale]
        for locale in LOCALES
        if row_counts_by_locale[locale] != 140
    }
    if malformed_locales:
        raise HoldoutValidationError(
            f"each locale must contain exactly 140 rows: {malformed_locales}"
        )
    malformed_groups = {
        f"{locale}/{intent}": row_counts_by_locale_intent[(locale, intent)]
        for locale in LOCALES
        for intent in INTENTS
        if row_counts_by_locale_intent[(locale, intent)] != 20
    }
    if malformed_groups:
        raise HoldoutValidationError(
            f"each intent-locale must contain exactly 20 rows: {malformed_groups}"
        )
    if len(pairs) != 210:
        raise HoldoutValidationError(
            f"sealed holdout must contain exactly 210 pair IDs, found {len(pairs)}"
        )

    injection_counts = _validate_pairs(pairs, training_family_ids)
    malformed_pair_groups = {
        f"{locale}/{intent}/{kind}": injection_counts[(locale, intent, injected)]
        for locale in LOCALES
        for intent in INTENTS
        for injected, kind in ((False, "clean"), (True, "injected"))
        if injection_counts[(locale, intent, injected)] != 10
    }
    if malformed_pair_groups:
        raise HoldoutValidationError(
            "each intent-locale must contain ten clean/injected pairs: "
            f"{malformed_pair_groups}"
        )

    manifest = {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "files": {
            filename: {
                "sha256": hashlib.sha256(raw_files[filename]).hexdigest(),
                "row_count": 140,
                "pair_count": 70,
                "clean_count": 70,
                "injected_count": 70,
            }
            for filename in (HOLDOUT_FILENAMES[locale] for locale in LOCALES)
        },
        "counts": {
            "row_count": 420,
            "pair_count": 210,
            "clean_count": 210,
            "injected_count": 210,
            "rows_by_locale": {locale: 140 for locale in LOCALES},
            "pairs_by_locale": {locale: 70 for locale in LOCALES},
            "rows_by_locale_intent": {
                locale: {intent: 20 for intent in INTENTS}
                for locale in LOCALES
            },
        },
    }
    return manifest, raw_files


def _write_manifest(manifest: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(
                manifest,
                stream,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def validate_and_write_manifest(
    holdout_dir: Path = DEFAULT_HOLDOUT_DIR,
    catalog_dir: Path = DEFAULT_CATALOG_DIR,
    manifest_path: Path = DEFAULT_MANIFEST_PATH,
) -> dict[str, Any]:
    """Validate all inputs before atomically writing the aggregate manifest."""

    manifest, _ = validate_holdouts(holdout_dir, catalog_dir)
    _write_manifest(manifest, manifest_path)
    return manifest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--holdout-dir",
        type=Path,
        default=DEFAULT_HOLDOUT_DIR,
    )
    parser.add_argument(
        "--catalog-dir",
        type=Path,
        default=DEFAULT_CATALOG_DIR,
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST_PATH,
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        manifest = validate_and_write_manifest(
            args.holdout_dir,
            args.catalog_dir,
            args.manifest,
        )
    except HoldoutValidationError as error:
        print(f"validate_holdout: {error}", file=__import__("sys").stderr)
        return 2
    print(
        json.dumps(
            {
                "manifest": str(args.manifest),
                "schema_version": manifest["schema_version"],
                "counts": manifest["counts"],
                "files": manifest["files"],
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
