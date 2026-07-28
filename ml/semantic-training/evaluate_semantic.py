#!/usr/bin/env python3
"""Evaluate and gate seven-way semantic-intent prediction JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import struct
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from semantic_contract import (
    ContractError,
    DEFAULT_SEMANTIC_THRESHOLDS,
    LABELS,
    RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
    SemanticThresholds,
    load_jsonl,
)

LOCALES = ("ko", "en", "mixed")
PREDICTION_FIELDS = {
    "id",
    "locale",
    "intent",
    "injection",
    "pair_id",
    "predicted_intent",
    "confidence",
    "probabilities",
}
OPTIONAL_SPLIT_FIELD = "split"
EVALUATION_SPLITS = {"validation", "test", "holdout"}
NORMALIZATION_TOLERANCE = 1e-6
# Compatibility alias used by callers and tests. The shared contract is the
# single source of truth for the deployed safety floor.
DEFAULT_TRUST_THRESHOLD = RELEASE_CONFIDENCE_THRESHOLD_FLOOR
DEFAULT_THRESHOLDS = DEFAULT_SEMANTIC_THRESHOLDS
EVALUATION_SCHEMA_VERSION = "semantic-evaluation-v3"
GATED_EVALUATION_SCHEMA_VERSION = "semantic-gated-evaluation-v3"
THRESHOLD_SELECTION_SCHEMA_VERSION = "semantic-threshold-selection-v4"
PROVENANCE_SCHEMA_VERSION = "semantic-evaluation-provenance-v1"
TRUSTED_COVERAGE_ELIGIBILITY = "actual-intent-not-ambiguous"
PREDICTION_MANIFEST_SCHEMA_VERSION = (
    "alarmcontrol-semantic-prediction-manifest-v2"
)
DATASET_MANIFEST_SCHEMA_VERSIONS = {
    "semantic-dataset-manifest-v2",
    "semantic-dataset-manifest-v3",
    "semantic-dataset-manifest-v4",
    "semantic-dataset-manifest-v5",
    "semantic-dataset-manifest-v6",
}
SOURCE_MANIFEST_SCHEMA_VERSIONS = DATASET_MANIFEST_SCHEMA_VERSIONS | {
    "semantic-sealed-holdout-manifest-v1",
    "semantic-sealed-holdout-manifest-v2",
}
COMMON_PREDICTION_MANIFEST_FIELDS = {
    "schema_version",
    "backend",
    "input",
    "input_sha256",
    "output",
    "output_sha256",
    "model_artifact_sha256",
    "selected_split",
    "row_count",
}
SOURCE_BINDING_FIELDS = (
    "id",
    "locale",
    "intent",
    "pair_id",
    "injection",
)
SHA256_HEX_LENGTH = 64


class EvaluationError(ValueError):
    """Raised when prediction data cannot satisfy the evaluation contract."""


@dataclass(frozen=True)
class GateConfig:
    """Default release quality and safety requirements."""

    raw_macro_f1_min: float = 0.85
    marketing_precision_min: float = 0.90
    locale_macro_f1_min: float = 0.80
    max_trusted_marketing_false_positives: int = 0
    minimum_trusted_coverage: float = 0.60
    minimum_locale_trusted_coverage: float = 0.40

    def __post_init__(self) -> None:
        for name in (
            "raw_macro_f1_min",
            "marketing_precision_min",
            "locale_macro_f1_min",
            "minimum_trusted_coverage",
            "minimum_locale_trusted_coverage",
        ):
            value = getattr(self, name)
            if not math.isfinite(value) or not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be finite and within [0, 1]")
        if (
            isinstance(self.max_trusted_marketing_false_positives, bool)
            or not isinstance(
                self.max_trusted_marketing_false_positives,
                int,
            )
            or self.max_trusted_marketing_false_positives < 0
        ):
            raise ValueError(
                "max_trusted_marketing_false_positives must be non-negative"
            )


DEFAULT_GATE_CONFIG = GateConfig()


@dataclass(frozen=True)
class BoundPredictionSet:
    """Prediction rows plus immutable model and source provenance."""

    rows: list[dict[str, Any]]
    provenance: dict[str, Any]


def _nonempty_identifier(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationError(f"{context}: must be a nonempty string")
    if value != value.strip():
        raise EvaluationError(f"{context}: surrounding whitespace is not allowed")
    return value


def _finite_probability(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluationError(f"{context}: must be a finite number")
    converted = float(value)
    if not math.isfinite(converted) or not 0.0 <= converted <= 1.0:
        raise EvaluationError(f"{context}: must be finite and within [0, 1]")
    return converted


def _float32(value: float) -> float:
    return struct.unpack(">f", struct.pack(">f", value))[0]


def _next_float32_up(value: float) -> float:
    """Return the smallest positive float32 strictly greater than value."""

    rounded = _float32(value)
    if rounded > value:
        return rounded
    bits = struct.unpack(">I", struct.pack(">f", rounded))[0]
    if bits >= 0x7F7FFFFF:
        raise EvaluationError("no finite float32 threshold exists above value")
    return struct.unpack(">f", struct.pack(">I", bits + 1))[0]


def _require_thresholds(
    value: SemanticThresholds | Mapping[str, Any],
    context: str = "thresholds",
) -> SemanticThresholds:
    if isinstance(value, SemanticThresholds):
        return value
    try:
        return SemanticThresholds.from_mapping(value)
    except ContractError as error:
        raise EvaluationError(f"{context}: {error}") from error


def _probability_map(value: Any, context: str) -> dict[str, float]:
    if isinstance(value, dict):
        if set(value) != set(LABELS):
            raise EvaluationError(
                f"{context}: probability labels must be exactly {list(LABELS)}"
            )
        probabilities = {
            label: _finite_probability(value[label], f"{context}.{label}")
            for label in LABELS
        }
    elif isinstance(value, list):
        if len(value) != len(LABELS):
            raise EvaluationError(
                f"{context}: probability array must contain seven values"
            )
        probabilities = {
            label: _finite_probability(
                probability,
                f"{context}[{index}]",
            )
            for index, (label, probability) in enumerate(zip(LABELS, value))
        }
    else:
        raise EvaluationError(
            f"{context}: probabilities must be a label object or seven-value array"
        )
    total = math.fsum(probabilities.values())
    if not math.isclose(
        total,
        1.0,
        rel_tol=0.0,
        abs_tol=NORMALIZATION_TOLERANCE,
    ):
        raise EvaluationError(
            f"{context}: probabilities must sum to one, found {total!r}"
        )
    return probabilities


def validate_prediction_records(
    records: Sequence[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Validate and normalize prediction rows without changing label order."""

    if not records:
        raise EvaluationError("prediction input must not be empty")
    has_split = OPTIONAL_SPLIT_FIELD in records[0]
    expected_fields = set(PREDICTION_FIELDS)
    if has_split:
        expected_fields.add(OPTIONAL_SPLIT_FIELD)

    normalized: list[dict[str, Any]] = []
    ids: set[str] = set()
    pairs: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for index, record in enumerate(records, start=1):
        context = f"prediction[{index}]"
        if set(record) != expected_fields:
            raise EvaluationError(
                f"{context}: fields mismatch; "
                f"missing={sorted(expected_fields - set(record))}, "
                f"unexpected={sorted(set(record) - expected_fields)}"
            )
        identifier = _nonempty_identifier(record["id"], f"{context}.id")
        pair_id = _nonempty_identifier(
            record["pair_id"],
            f"{context}.pair_id",
        )
        if identifier in ids:
            raise EvaluationError(f"{context}: duplicate id {identifier!r}")
        ids.add(identifier)
        if record["locale"] not in LOCALES:
            raise EvaluationError(f"{context}: unsupported locale")
        if record["intent"] not in LABELS:
            raise EvaluationError(f"{context}: unsupported intent")
        if record["predicted_intent"] not in LABELS:
            raise EvaluationError(f"{context}: unsupported predicted_intent")
        if type(record["injection"]) is not bool:
            raise EvaluationError(f"{context}.injection: must be a JSON boolean")
        if has_split and record["split"] not in EVALUATION_SPLITS:
            raise EvaluationError(f"{context}: unsupported evaluation split")

        confidence = _finite_probability(
            record["confidence"],
            f"{context}.confidence",
        )
        probabilities = _probability_map(
            record["probabilities"],
            f"{context}.probabilities",
        )
        predicted_probability = probabilities[record["predicted_intent"]]
        if not math.isclose(
            confidence,
            predicted_probability,
            rel_tol=0.0,
            abs_tol=NORMALIZATION_TOLERANCE,
        ):
            raise EvaluationError(
                f"{context}: confidence must equal predicted label probability"
            )
        maximum = max(probabilities.values())
        if not math.isclose(
            predicted_probability,
            maximum,
            rel_tol=0.0,
            abs_tol=NORMALIZATION_TOLERANCE,
        ):
            raise EvaluationError(
                f"{context}: predicted_intent must be an argmax label"
            )

        normalized_record = dict(record)
        normalized_record["confidence"] = confidence
        normalized_record["probabilities"] = probabilities
        normalized.append(normalized_record)
        pairs[pair_id].append(normalized_record)

    for pair_id, pair in pairs.items():
        if len(pair) != 2:
            raise EvaluationError(
                f"pair_id {pair_id!r}: exactly two prediction rows are required"
            )
        if len({row["locale"] for row in pair}) != 1:
            raise EvaluationError(f"pair_id {pair_id!r}: locale mismatch")
        if len({row["intent"] for row in pair}) != 1:
            raise EvaluationError(f"pair_id {pair_id!r}: intent mismatch")
        if has_split and len({row["split"] for row in pair}) != 1:
            raise EvaluationError(f"pair_id {pair_id!r}: split mismatch")
        if {row["injection"] for row in pair} != {False, True}:
            raise EvaluationError(
                f"pair_id {pair_id!r}: one clean and one injected row are required"
            )
    if has_split:
        splits = {row["split"] for row in normalized}
        if len(splits) != 1:
            raise EvaluationError(
                "prediction input must contain exactly one global split"
            )
    return normalized


def load_predictions(path: Path) -> list[dict[str, Any]]:
    """Load strict JSONL using the shared semantic contract."""

    try:
        records = load_jsonl(path)
    except (ContractError, OSError, UnicodeError) as error:
        raise EvaluationError(str(error)) from error
    return validate_prediction_records(records)


def load_prediction_files(paths: Sequence[Path]) -> list[dict[str, Any]]:
    """Load one logical evaluation set from one or more strict JSONL files."""

    if not paths:
        raise EvaluationError("at least one prediction file is required")
    records: list[dict[str, Any]] = []
    for path in paths:
        try:
            records.extend(load_jsonl(path))
        except (ContractError, OSError, UnicodeError) as error:
            raise EvaluationError(str(error)) from error
    return validate_prediction_records(records)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json_object(path: Path, context: str) -> dict[str, Any]:
    def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvaluationError(
                    f"{context}: duplicate JSON key {key!r}"
                )
            result[key] = value
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=strict_object,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvaluationError(f"{context}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise EvaluationError(f"{context}: must contain one JSON object")
    return value


def _require_sha256(value: Any, context: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != SHA256_HEX_LENGTH
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise EvaluationError(
            f"{context}: must be a lowercase SHA-256 hex digest"
        )
    return value


def _require_positive_count(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise EvaluationError(f"{context}: must be a positive integer")
    return value


def _manifest_path(value: Any, manifest_path: Path, context: str) -> Path:
    raw = _nonempty_identifier(value, context)
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = manifest_path.parent / path
    return path.resolve()


def _prediction_manifest_path(prediction_path: Path) -> Path:
    return prediction_path.with_suffix(
        f"{prediction_path.suffix}.manifest.json"
    )


def _source_file_entries(
    source_manifest: Mapping[str, Any],
    source_manifest_path: Path,
) -> dict[str, dict[str, Any]]:
    schema_version = source_manifest.get("schema_version")
    if schema_version not in SOURCE_MANIFEST_SCHEMA_VERSIONS:
        raise EvaluationError(
            f"{source_manifest_path}: unsupported source manifest "
            f"schema_version {schema_version!r}"
        )
    files = source_manifest.get("files")
    if not isinstance(files, dict):
        raise EvaluationError(
            f"{source_manifest_path}: files must be an object"
        )

    if schema_version in DATASET_MANIFEST_SCHEMA_VERSIONS:
        selected_names = ("dataset.jsonl",)
        global_count = source_manifest.get("row_count")
    else:
        selected_names = tuple(
            name
            for name in files
            if isinstance(name, str) and name.endswith(".jsonl")
        )
        counts = source_manifest.get("counts")
        if isinstance(counts, dict):
            global_count = counts.get(
                "row_count",
                source_manifest.get("row_count"),
            )
        else:
            global_count = source_manifest.get("row_count")

    if not selected_names:
        raise EvaluationError(
            f"{source_manifest_path}: no source JSONL files declared"
        )
    expected_global_count = _require_positive_count(
        global_count,
        f"{source_manifest_path}.row_count",
    )
    entries: dict[str, dict[str, Any]] = {}
    for name in selected_names:
        entry = files.get(name)
        if not isinstance(entry, dict):
            raise EvaluationError(
                f"{source_manifest_path}: missing file entry {name!r}"
            )
        digest = _require_sha256(
            entry.get("sha256"),
            f"{source_manifest_path}.files[{name!r}].sha256",
        )
        if schema_version in DATASET_MANIFEST_SCHEMA_VERSIONS:
            count_key = "rows"
        elif "row_count" in entry:
            count_key = "row_count"
        else:
            count_key = "rows"
        row_count = _require_positive_count(
            entry.get(count_key),
            f"{source_manifest_path}.files[{name!r}].{count_key}",
        )
        basename = Path(name).name
        if basename in entries:
            raise EvaluationError(
                f"{source_manifest_path}: duplicate source basename {basename!r}"
            )
        entries[basename] = {
            "name": name,
            "sha256": digest,
            "row_count": row_count,
        }
    if sum(entry["row_count"] for entry in entries.values()) != expected_global_count:
        raise EvaluationError(
            f"{source_manifest_path}: source file row counts do not match "
            "the global row count"
        )
    return entries


def _validate_prediction_manifest(
    prediction_path: Path,
    prediction_rows: Sequence[Mapping[str, Any]],
) -> tuple[dict[str, Any], Path]:
    manifest_path = _prediction_manifest_path(prediction_path)
    manifest = _load_json_object(
        manifest_path,
        f"prediction manifest {manifest_path}",
    )
    if manifest.get("schema_version") != PREDICTION_MANIFEST_SCHEMA_VERSION:
        raise EvaluationError(
            f"{manifest_path}: schema_version must be "
            f"{PREDICTION_MANIFEST_SCHEMA_VERSION!r}"
        )
    missing = COMMON_PREDICTION_MANIFEST_FIELDS - set(manifest)
    if missing:
        raise EvaluationError(
            f"{manifest_path}: missing common fields {sorted(missing)}"
        )
    backend = _nonempty_identifier(
        manifest["backend"],
        f"{manifest_path}.backend",
    )
    input_path = _manifest_path(
        manifest["input"],
        manifest_path,
        f"{manifest_path}.input",
    )
    output_path = _manifest_path(
        manifest["output"],
        manifest_path,
        f"{manifest_path}.output",
    )
    if output_path != prediction_path.resolve():
        raise EvaluationError(
            f"{manifest_path}: output does not bind this prediction file"
        )
    if not input_path.is_file():
        raise EvaluationError(
            f"{manifest_path}: input file does not exist: {input_path}"
        )
    if _sha256_file(prediction_path) != _require_sha256(
        manifest["output_sha256"],
        f"{manifest_path}.output_sha256",
    ):
        raise EvaluationError(f"{manifest_path}: output SHA-256 mismatch")
    if _sha256_file(input_path) != _require_sha256(
        manifest["input_sha256"],
        f"{manifest_path}.input_sha256",
    ):
        raise EvaluationError(f"{manifest_path}: input SHA-256 mismatch")
    model_artifact_sha256 = _require_sha256(
        manifest["model_artifact_sha256"],
        f"{manifest_path}.model_artifact_sha256",
    )
    vocab_sha256: str | None = None
    if "vocab_sha256" in manifest:
        vocab_sha256 = _require_sha256(
            manifest["vocab_sha256"],
            f"{manifest_path}.vocab_sha256",
        )
    if backend == "tensorflow-lite":
        if vocab_sha256 is None:
            raise EvaluationError(
                f"{manifest_path}: tensorflow-lite requires vocab_sha256"
            )
        for field in ("model", "model_sha256", "vocab"):
            if field not in manifest:
                raise EvaluationError(
                    f"{manifest_path}: tensorflow-lite requires {field}"
                )
        model_path = _manifest_path(
            manifest["model"],
            manifest_path,
            f"{manifest_path}.model",
        )
        vocab_path = _manifest_path(
            manifest["vocab"],
            manifest_path,
            f"{manifest_path}.vocab",
        )
        if not model_path.is_file() or not vocab_path.is_file():
            raise EvaluationError(
                f"{manifest_path}: tensorflow-lite model/vocab is missing"
            )
        actual_model_sha256 = _sha256_file(model_path)
        if (
            _require_sha256(
                manifest["model_sha256"],
                f"{manifest_path}.model_sha256",
            )
            != actual_model_sha256
            or model_artifact_sha256 != actual_model_sha256
        ):
            raise EvaluationError(
                f"{manifest_path}: tensorflow-lite model SHA-256 mismatch"
            )
        if _sha256_file(vocab_path) != vocab_sha256:
            raise EvaluationError(
                f"{manifest_path}: tensorflow-lite vocab SHA-256 mismatch"
            )
    if _require_positive_count(
        manifest["row_count"],
        f"{manifest_path}.row_count",
    ) != len(prediction_rows):
        raise EvaluationError(f"{manifest_path}: prediction row count mismatch")
    selected_split = manifest["selected_split"]
    if selected_split is not None and selected_split not in {
        "validation",
        "test",
    }:
        raise EvaluationError(
            f"{manifest_path}: selected_split must be validation, test, or null"
        )
    return manifest, input_path


def _selected_source_rows(
    input_path: Path,
    selected_split: str | None,
) -> list[dict[str, Any]]:
    try:
        rows = load_jsonl(input_path)
    except (ContractError, OSError, UnicodeError) as error:
        raise EvaluationError(str(error)) from error
    if selected_split is not None:
        rows = [
            row for row in rows if row.get("split") == selected_split
        ]
    if not rows:
        raise EvaluationError(f"{input_path}: no source rows selected")
    ids: set[str] = set()
    for index, row in enumerate(rows, start=1):
        context = f"{input_path}:{index}"
        missing = set(SOURCE_BINDING_FIELDS) - set(row)
        if missing:
            raise EvaluationError(
                f"{context}: missing source binding fields {sorted(missing)}"
            )
        identifier = _nonempty_identifier(row["id"], f"{context}.id")
        if identifier in ids:
            raise EvaluationError(
                f"{context}: duplicate source id {identifier!r}"
            )
        ids.add(identifier)
    return rows


def load_bound_prediction_set(
    prediction_paths: Sequence[Path],
    source_manifest_path: Path,
) -> BoundPredictionSet:
    """Verify prediction/source manifests and return rows plus provenance."""

    if not prediction_paths:
        raise EvaluationError("at least one prediction file is required")
    source_manifest_path = source_manifest_path.expanduser()
    if source_manifest_path.is_symlink() or not source_manifest_path.is_file():
        raise EvaluationError(
            "--source-manifest must be a non-symlink regular file"
        )
    source_manifest_path = source_manifest_path.resolve(strict=True)
    source_manifest = _load_json_object(
        source_manifest_path,
        f"source manifest {source_manifest_path}",
    )
    source_entries = _source_file_entries(
        source_manifest,
        source_manifest_path,
    )
    source_schema_version = source_manifest["schema_version"]

    all_predictions: list[dict[str, Any]] = []
    all_sources: list[dict[str, Any]] = []
    represented_sources: set[str] = set()
    selected_splits: set[str | None] = set()
    backends: set[str] = set()
    model_artifact_hashes: set[str] = set()
    vocab_hashes: set[str] = set()
    vocab_presence: set[bool] = set()
    for prediction_path in prediction_paths:
        prediction_path = prediction_path.resolve()
        file_predictions = load_predictions(prediction_path)
        manifest, input_path = _validate_prediction_manifest(
            prediction_path,
            file_predictions,
        )
        selected_split = manifest["selected_split"]
        if (
            source_schema_version in DATASET_MANIFEST_SCHEMA_VERSIONS
            and selected_split is None
        ):
            raise EvaluationError(
                f"{prediction_path}: dataset predictions must select one split"
            )
        if (
            source_schema_version not in DATASET_MANIFEST_SCHEMA_VERSIONS
            and selected_split is not None
        ):
            raise EvaluationError(
                f"{prediction_path}: sealed holdout predictions must not "
                "select a dataset split"
            )
        selected_splits.add(selected_split)
        backends.add(str(manifest["backend"]))
        model_artifact_hashes.add(str(manifest["model_artifact_sha256"]))
        vocab_present = "vocab_sha256" in manifest
        vocab_presence.add(vocab_present)
        if vocab_present:
            vocab_hashes.add(str(manifest["vocab_sha256"]))
        source_name = input_path.name
        if source_name not in source_entries:
            raise EvaluationError(
                f"{input_path}: input is not declared by the source manifest"
            )
        if source_name in represented_sources:
            raise EvaluationError(
                f"{input_path}: source file is represented more than once"
            )
        represented_sources.add(source_name)
        source_entry = source_entries[source_name]
        if manifest["input_sha256"] != source_entry["sha256"]:
            raise EvaluationError(
                f"{input_path}: input hash does not match source manifest"
            )
        try:
            unfiltered_source_rows = load_jsonl(input_path)
        except (ContractError, OSError, UnicodeError) as error:
            raise EvaluationError(str(error)) from error
        if len(unfiltered_source_rows) != source_entry["row_count"]:
            raise EvaluationError(
                f"{input_path}: source row count does not match source manifest"
            )
        source_rows = _selected_source_rows(input_path, selected_split)
        expected_output_split = selected_split or "holdout"
        if any(
            row.get("split") != expected_output_split
            for row in file_predictions
        ):
            raise EvaluationError(
                f"{prediction_path}: prediction split does not match "
                "selected_split"
            )
        all_predictions.extend(file_predictions)
        all_sources.extend(source_rows)

    if represented_sources != set(source_entries):
        missing = sorted(set(source_entries) - represented_sources)
        raise EvaluationError(
            f"prediction inputs are a subset of the source manifest; "
            f"missing={missing}"
        )
    if len(selected_splits) != 1:
        raise EvaluationError(
            "prediction manifests must declare exactly one global split"
        )
    if len(backends) != 1 or len(model_artifact_hashes) != 1:
        raise EvaluationError(
            "prediction manifests must bind one backend and model artifact"
        )
    if len(vocab_presence) != 1 or len(vocab_hashes) > 1:
        raise EvaluationError(
            "prediction manifests must bind one consistent vocabulary"
        )

    predictions = validate_prediction_records(all_predictions)
    expected_by_id = {
        str(row["id"]): row
        for row in all_sources
    }
    if len(expected_by_id) != len(all_sources):
        raise EvaluationError("source inputs contain duplicate IDs")
    predicted_by_id = {
        str(row["id"]): row
        for row in predictions
    }
    if set(predicted_by_id) != set(expected_by_id):
        missing = sorted(set(expected_by_id) - set(predicted_by_id))
        unexpected = sorted(set(predicted_by_id) - set(expected_by_id))
        raise EvaluationError(
            "prediction ID coverage does not exactly match source rows; "
            f"missing={missing}, unexpected={unexpected}"
        )
    selected_split = next(iter(selected_splits))
    expected_output_split = selected_split or "holdout"
    for identifier, prediction in predicted_by_id.items():
        source = expected_by_id[identifier]
        for field in SOURCE_BINDING_FIELDS:
            if prediction[field] != source[field]:
                raise EvaluationError(
                    f"prediction {identifier!r}: {field} does not match source"
                )
        if prediction.get("split") != expected_output_split:
            raise EvaluationError(
                f"prediction {identifier!r}: split does not match source selection"
            )
    provenance = {
        "schema_version": PROVENANCE_SCHEMA_VERSION,
        "source_manifest_sha256": _sha256_file(source_manifest_path),
        "backend": next(iter(backends)),
        "model_artifact_sha256": next(iter(model_artifact_hashes)),
    }
    if vocab_hashes:
        provenance["vocab_sha256"] = next(iter(vocab_hashes))
    return BoundPredictionSet(
        rows=predictions,
        provenance=provenance,
    )


def load_bound_prediction_files(
    prediction_paths: Sequence[Path],
    source_manifest_path: Path,
) -> list[dict[str, Any]]:
    """Compatibility view returning only fully bound prediction rows."""

    return load_bound_prediction_set(
        prediction_paths,
        source_manifest_path,
    ).rows


def runtime_output(
    record: Mapping[str, Any],
    thresholds: SemanticThresholds | Mapping[str, Any],
) -> str:
    """Apply two-threshold abstention while preserving AMBIGUOUS argmax."""

    selected = _require_thresholds(thresholds)
    predicted = str(record["predicted_intent"])
    if predicted == "AMBIGUOUS":
        return predicted
    threshold = (
        selected.marketing
        if predicted == "MARKETING"
        else selected.general
    )
    if float(record["confidence"]) < threshold:
        return "AMBIGUOUS"
    return predicted


def _empty_confusion() -> dict[str, dict[str, int]]:
    return {
        actual: {predicted: 0 for predicted in LABELS}
        for actual in LABELS
    }


def _classification_metrics(
    rows: Sequence[Mapping[str, Any]],
    predictions: Mapping[str, str],
) -> dict[str, Any]:
    confusion = _empty_confusion()
    for row in rows:
        confusion[str(row["intent"])][predictions[str(row["id"])]] += 1

    per_class: dict[str, dict[str, float | int]] = {}
    f1_values: list[float] = []
    correct = 0
    for label in LABELS:
        true_positive = confusion[label][label]
        false_positive = sum(
            confusion[actual][label]
            for actual in LABELS
            if actual != label
        )
        false_negative = sum(
            confusion[label][predicted]
            for predicted in LABELS
            if predicted != label
        )
        support = sum(confusion[label].values())
        predicted_count = sum(
            confusion[actual][label]
            for actual in LABELS
        )
        precision = (
            true_positive / (true_positive + false_positive)
            if true_positive + false_positive
            else 0.0
        )
        recall = (
            true_positive / (true_positive + false_negative)
            if true_positive + false_negative
            else 0.0
        )
        f1 = (
            2.0 * precision * recall / (precision + recall)
            if precision + recall
            else 0.0
        )
        per_class[label] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "support": support,
            "predicted_count": predicted_count,
        }
        f1_values.append(f1)
        correct += true_positive
    return {
        "row_count": len(rows),
        "accuracy": correct / len(rows) if rows else 0.0,
        "macro_f1": math.fsum(f1_values) / len(LABELS),
        "marketing_precision": per_class["MARKETING"]["precision"],
        "confusion": confusion,
        "per_class": per_class,
    }


def _metric_views(
    rows: Sequence[Mapping[str, Any]],
    predictions: Mapping[str, str],
) -> dict[str, Any]:
    return {
        "overall": _classification_metrics(rows, predictions),
        "by_locale": {
            locale: _classification_metrics(
                [row for row in rows if row["locale"] == locale],
                predictions,
            )
            for locale in LOCALES
        },
        "by_injection": {
            "clean": _classification_metrics(
                [row for row in rows if not row["injection"]],
                predictions,
            ),
            "injected": _classification_metrics(
                [row for row in rows if row["injection"]],
                predictions,
            ),
        },
    }


def _pair_consistency(
    rows: Sequence[Mapping[str, Any]],
    raw_predictions: Mapping[str, str],
    runtime_predictions: Mapping[str, str],
) -> dict[str, Any]:
    pairs: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for row in rows:
        pairs[str(row["pair_id"])].append(row)

    raw_same = 0
    runtime_same = 0
    raw_both_correct = 0
    runtime_both_correct = 0
    for pair in pairs.values():
        raw_values = {raw_predictions[str(row["id"])] for row in pair}
        runtime_values = {
            runtime_predictions[str(row["id"])]
            for row in pair
        }
        raw_same += len(raw_values) == 1
        runtime_same += len(runtime_values) == 1
        raw_both_correct += all(
            raw_predictions[str(row["id"])] == row["intent"]
            for row in pair
        )
        runtime_both_correct += all(
            runtime_predictions[str(row["id"])] == row["intent"]
            for row in pair
        )
    pair_count = len(pairs)
    return {
        "pair_count": pair_count,
        "raw_same_prediction_count": raw_same,
        "raw_same_prediction_rate": raw_same / pair_count,
        "runtime_same_output_count": runtime_same,
        "runtime_same_output_rate": runtime_same / pair_count,
        "raw_both_correct_count": raw_both_correct,
        "raw_both_correct_rate": raw_both_correct / pair_count,
        "runtime_both_correct_count": runtime_both_correct,
        "runtime_both_correct_rate": runtime_both_correct / pair_count,
    }


def _trusted_coverage(
    rows: Sequence[Mapping[str, Any]],
    thresholds: SemanticThresholds,
) -> dict[str, Any]:
    eligible = [
        row
        for row in rows
        if row["intent"] != "AMBIGUOUS"
    ]
    trusted_count = sum(
        runtime_output(row, thresholds) != "AMBIGUOUS"
        for row in eligible
    )
    row_count = len(eligible)
    return {
        "trusted_count": trusted_count,
        "row_count": row_count,
        "rate": trusted_count / row_count if row_count else 0.0,
    }


def evaluate_predictions(
    records: Sequence[dict[str, Any]],
    thresholds: (
        SemanticThresholds | Mapping[str, Any]
    ) = DEFAULT_THRESHOLDS,
) -> dict[str, Any]:
    """Compute raw, runtime, locale, injection, pair, and trust metrics."""

    rows = validate_prediction_records(records)
    selected = _require_thresholds(thresholds)
    raw_predictions = {
        row["id"]: row["predicted_intent"]
        for row in rows
    }
    runtime_predictions = {
        row["id"]: runtime_output(row, selected)
        for row in rows
    }
    abstained = [
        row
        for row in rows
        if row["predicted_intent"] != "AMBIGUOUS"
        and runtime_predictions[row["id"]] == "AMBIGUOUS"
    ]
    natural_ambiguous = [
        row
        for row in rows
        if row["predicted_intent"] == "AMBIGUOUS"
    ]
    trusted_marketing_false_positives = [
        row
        for row in rows
        if row["intent"] != "MARKETING"
        and runtime_predictions[row["id"]] == "MARKETING"
    ]
    return {
        "schema_version": EVALUATION_SCHEMA_VERSION,
        "labels": list(LABELS),
        "general_threshold": selected.general,
        "marketing_threshold": selected.marketing,
        "row_count": len(rows),
        "raw": _metric_views(rows, raw_predictions),
        "runtime": {
            **_metric_views(rows, runtime_predictions),
            "abstained_to_ambiguous_count": len(abstained),
            "natural_ambiguous_argmax_count": len(natural_ambiguous),
            "ambiguous_output_count": sum(
                prediction == "AMBIGUOUS"
                for prediction in runtime_predictions.values()
            ),
        },
        "pair_consistency": _pair_consistency(
            rows,
            raw_predictions,
            runtime_predictions,
        ),
        "trusted_coverage": {
            "eligibility": TRUSTED_COVERAGE_ELIGIBILITY,
            "overall": _trusted_coverage(rows, selected),
            "by_locale": {
                locale: _trusted_coverage(
                    [row for row in rows if row["locale"] == locale],
                    selected,
                )
                for locale in LOCALES
            },
        },
        "safety": {
            "trusted_non_marketing_predicted_marketing_count": len(
                trusted_marketing_false_positives
            ),
        },
    }


def gate_evaluation(
    evaluation: Mapping[str, Any],
    config: GateConfig = DEFAULT_GATE_CONFIG,
) -> dict[str, Any]:
    """Apply raw quality gates and the threshold-dependent safety gate."""

    raw_overall = evaluation["raw"]["overall"]
    raw_locales = evaluation["raw"]["by_locale"]
    trusted_false_positives = evaluation["safety"][
        "trusted_non_marketing_predicted_marketing_count"
    ]
    trusted_coverage = evaluation["trusted_coverage"]
    overall_trusted_coverage = trusted_coverage["overall"]["rate"]
    locale_trusted_coverage_checks = {
        locale: {
            "actual": trusted_coverage["by_locale"][locale]["rate"],
            "minimum": config.minimum_locale_trusted_coverage,
            "passed": (
                trusted_coverage["by_locale"][locale]["rate"]
                >= config.minimum_locale_trusted_coverage
            ),
        }
        for locale in LOCALES
    }
    locale_checks = {
        locale: {
            "actual": raw_locales[locale]["macro_f1"],
            "minimum": config.locale_macro_f1_min,
            "passed": (
                raw_locales[locale]["macro_f1"]
                >= config.locale_macro_f1_min
            ),
        }
        for locale in LOCALES
    }
    checks = {
        "raw_macro_f1": {
            "actual": raw_overall["macro_f1"],
            "minimum": config.raw_macro_f1_min,
            "passed": raw_overall["macro_f1"] >= config.raw_macro_f1_min,
        },
        "raw_marketing_precision": {
            "actual": raw_overall["marketing_precision"],
            "minimum": config.marketing_precision_min,
            "passed": (
                raw_overall["marketing_precision"]
                >= config.marketing_precision_min
            ),
        },
        "raw_locale_macro_f1": locale_checks,
        "trusted_marketing_false_positives": {
            "actual": trusted_false_positives,
            "maximum": config.max_trusted_marketing_false_positives,
            "passed": (
                trusted_false_positives
                <= config.max_trusted_marketing_false_positives
            ),
        },
        "minimum_trusted_coverage": {
            "actual": overall_trusted_coverage,
            "minimum": config.minimum_trusted_coverage,
            "passed": (
                overall_trusted_coverage
                >= config.minimum_trusted_coverage
            ),
        },
        "minimum_locale_trusted_coverage": (
            locale_trusted_coverage_checks
        ),
    }
    return {
        "passed": (
            checks["raw_macro_f1"]["passed"]
            and checks["raw_marketing_precision"]["passed"]
            and all(check["passed"] for check in locale_checks.values())
            and checks["trusted_marketing_false_positives"]["passed"]
            and checks["minimum_trusted_coverage"]["passed"]
            and all(
                check["passed"]
                for check in locale_trusted_coverage_checks.values()
            )
        ),
        "checks": checks,
    }


def evaluate_and_gate(
    records: Sequence[dict[str, Any]],
    thresholds: (
        SemanticThresholds | Mapping[str, Any]
    ) = DEFAULT_THRESHOLDS,
    config: GateConfig = DEFAULT_GATE_CONFIG,
) -> dict[str, Any]:
    selected = _require_thresholds(thresholds)
    evaluation = evaluate_predictions(records, selected)
    return {
        "general_threshold": selected.general,
        "marketing_threshold": selected.marketing,
        "evaluation": evaluation,
        "gate": gate_evaluation(evaluation, config),
    }


def select_validation_threshold(
    records: Sequence[dict[str, Any]],
    config: GateConfig = DEFAULT_GATE_CONFIG,
) -> dict[str, Any]:
    """Select safe two-threshold abstention using validation predictions only."""

    rows = validate_prediction_records(records)
    if any(row.get("split") != "validation" for row in rows):
        raise EvaluationError(
            "threshold selection requires split='validation' on every row; "
            "test and holdout predictions must never tune the threshold"
        )
    unsafe_confidences = [
        float(row["confidence"])
        for row in rows
        if row["intent"] != "MARKETING"
        and row["predicted_intent"] == "MARKETING"
    ]
    baseline = DEFAULT_THRESHOLDS
    try:
        marketing_threshold = (
            max(
                DEFAULT_TRUST_THRESHOLD,
                _next_float32_up(max(unsafe_confidences)),
            )
            if unsafe_confidences
            else DEFAULT_TRUST_THRESHOLD
        )
        selected_thresholds = SemanticThresholds(
            general=DEFAULT_TRUST_THRESHOLD,
            marketing=marketing_threshold,
        )
    except (ContractError, EvaluationError):
        selected_thresholds = None

    if selected_thresholds is None:
        baseline_result = evaluate_and_gate(rows, baseline, config)
        return {
            "schema_version": THRESHOLD_SELECTION_SCHEMA_VERSION,
            "status": "no-feasible-threshold",
            "validation_only": True,
            "general_threshold": None,
            "marketing_threshold": None,
            "trusted_coverage": None,
            "gate": baseline_result["gate"],
        }

    selected_result = evaluate_and_gate(
        rows,
        selected_thresholds,
        config,
    )
    if (
        not selected_result["gate"]["passed"]
        or selected_result["evaluation"]["trusted_coverage"]["overall"][
            "trusted_count"
        ] == 0
    ):
        return {
            "schema_version": THRESHOLD_SELECTION_SCHEMA_VERSION,
            "status": "no-feasible-threshold",
            "validation_only": True,
            "general_threshold": None,
            "marketing_threshold": None,
            "trusted_coverage": None,
            "gate": selected_result["gate"],
        }

    return {
        "schema_version": THRESHOLD_SELECTION_SCHEMA_VERSION,
        "status": "selected",
        "validation_only": True,
        "general_threshold": selected_thresholds.general,
        "marketing_threshold": selected_thresholds.marketing,
        "trusted_coverage": selected_result["evaluation"]["trusted_coverage"],
        "gate": selected_result["gate"],
    }


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def _add_gate_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--raw-macro-f1-min", type=float, default=0.85)
    parser.add_argument("--marketing-precision-min", type=float, default=0.90)
    parser.add_argument("--locale-macro-f1-min", type=float, default=0.80)
    parser.add_argument(
        "--max-trusted-marketing-false-positives",
        type=int,
        default=0,
    )
    parser.add_argument(
        "--minimum-trusted-coverage",
        type=float,
        default=0.60,
    )
    parser.add_argument(
        "--minimum-locale-trusted-coverage",
        type=float,
        default=0.40,
    )


def _gate_config(args: argparse.Namespace) -> GateConfig:
    return GateConfig(
        raw_macro_f1_min=args.raw_macro_f1_min,
        marketing_precision_min=args.marketing_precision_min,
        locale_macro_f1_min=args.locale_macro_f1_min,
        max_trusted_marketing_false_positives=(
            args.max_trusted_marketing_false_positives
        ),
        minimum_trusted_coverage=args.minimum_trusted_coverage,
        minimum_locale_trusted_coverage=(
            args.minimum_locale_trusted_coverage
        ),
    )


def _add_threshold_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--general-threshold",
        type=float,
        required=True,
    )
    parser.add_argument(
        "--marketing-threshold",
        type=float,
        required=True,
    )


def _thresholds_from_args(args: argparse.Namespace) -> SemanticThresholds:
    return _require_thresholds(
        {
            "general": args.general_threshold,
            "marketing": args.marketing_threshold,
        },
        "CLI thresholds",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    evaluate_parser = subparsers.add_parser("evaluate")
    evaluate_parser.add_argument("predictions", type=Path, nargs="+")
    _add_threshold_arguments(evaluate_parser)
    evaluate_parser.add_argument("--output", type=Path)

    gate_parser = subparsers.add_parser("gate")
    gate_parser.add_argument("predictions", type=Path, nargs="+")
    _add_threshold_arguments(gate_parser)
    gate_parser.add_argument("--output", type=Path)
    gate_parser.add_argument(
        "--source-manifest",
        type=Path,
        required=True,
    )
    _add_gate_arguments(gate_parser)

    select_parser = subparsers.add_parser("select-threshold")
    select_parser.add_argument("predictions", type=Path, nargs="+")
    select_parser.add_argument("--output", type=Path)
    select_parser.add_argument(
        "--source-manifest",
        type=Path,
        required=True,
    )
    _add_gate_arguments(select_parser)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.subcommand == "evaluate":
            rows = load_prediction_files(args.predictions)
            result = evaluate_predictions(rows, _thresholds_from_args(args))
            exit_code = 0
        elif args.subcommand == "gate":
            bound = load_bound_prediction_set(
                args.predictions,
                args.source_manifest,
            )
            result = evaluate_and_gate(
                bound.rows,
                _thresholds_from_args(args),
                _gate_config(args),
            )
            result["schema_version"] = GATED_EVALUATION_SCHEMA_VERSION
            result["provenance"] = bound.provenance
            exit_code = 0 if result["gate"]["passed"] else 3
        elif args.subcommand == "select-threshold":
            bound = load_bound_prediction_set(
                args.predictions,
                args.source_manifest,
            )
            result = select_validation_threshold(
                bound.rows,
                _gate_config(args),
            )
            result["provenance"] = bound.provenance
            exit_code = 0 if result["status"] == "selected" else 3
        else:
            raise AssertionError(f"unknown subcommand: {args.subcommand}")
        if args.output:
            _write_json(args.output, result)
        print(
            json.dumps(
                result,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
        )
        return exit_code
    except (EvaluationError, ValueError) as error:
        print(f"evaluate_semantic: {error}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
