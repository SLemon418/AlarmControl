#!/usr/bin/env python3
"""Create aggregate-only PyTorch-to-TFLite semantic parity evidence."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import statistics
import tempfile
from pathlib import Path
from typing import Any, Mapping, Sequence

from evaluate_semantic import (
    EvaluationError,
    evaluate_predictions,
    load_bound_prediction_set,
    runtime_output,
)
from semantic_contract import (
    LABELS,
    ContractError,
    SemanticThresholds,
    model_bundle_hashes,
    sha256_file,
)

SCHEMA_VERSION = "semantic-backend-parity-v2"
CONVERSION_SCHEMA_VERSION = "koelectra-litert-conversion-v2"
DATASET_SCHEMA_VERSIONS = {
    "semantic-dataset-manifest-v2",
    "semantic-dataset-manifest-v3",
    "semantic-dataset-manifest-v4",
    "semantic-dataset-manifest-v5",
    "semantic-dataset-manifest-v6",
}
PREDICTION_MANIFEST_SCHEMA = "alarmcontrol-semantic-prediction-manifest-v2"
PYTORCH_BACKEND = "pytorch-koelectra"
TFLITE_BACKEND = "tensorflow-lite"
ALLOWED_SPLITS = {"validation", "test"}
WINDOWS_PATH = re.compile(r"^[A-Za-z]:[\\/]")


class ParityError(ValueError):
    """Raised when predictions cannot form trusted parity evidence."""


def _load_json(path: Path, context: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise ParityError(f"{context}: must be a non-symlink regular file")

    def strict(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, child in pairs:
            if key in value:
                raise ParityError(f"{context}: duplicate JSON key {key!r}")
            value[key] = child
        return value

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=strict,
            parse_constant=lambda item: (_ for _ in ()).throw(
                ParityError(f"{context}: invalid number {item}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ParityError(f"{context}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ParityError(f"{context}: must contain one JSON object")
    return value


def _sha(value: Any, context: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise ParityError(f"{context}: invalid SHA-256")
    return value


def _thresholds(
    value: SemanticThresholds | Mapping[str, Any],
) -> SemanticThresholds:
    if isinstance(value, SemanticThresholds):
        return value
    try:
        return SemanticThresholds.from_mapping(value)
    except ContractError as error:
        raise ParityError(str(error)) from error


def _prediction_manifest(path: Path) -> tuple[dict[str, Any], Path]:
    manifest_path = path.with_suffix(f"{path.suffix}.manifest.json")
    manifest = _load_json(manifest_path, "prediction manifest")
    if manifest.get("schema_version") != PREDICTION_MANIFEST_SCHEMA:
        raise ParityError("unsupported prediction-manifest schema")
    return manifest, manifest_path


def _resolved_path(
    value: Any,
    manifest_path: Path,
    context: str,
) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise ParityError(f"{context}: missing path")
    path = Path(value).expanduser()
    return (path if path.is_absolute() else manifest_path.parent / path).resolve()


def _conversion_hashes(path: Path) -> dict[str, str]:
    manifest = _load_json(path, "conversion manifest")
    if (
        manifest.get("schema_version") != CONVERSION_SCHEMA_VERSION
        or manifest.get("labels") != list(LABELS)
    ):
        raise ParityError("conversion contract mismatch")
    source = manifest.get("source")
    artifact = manifest.get("artifact")
    if not isinstance(source, dict) or not isinstance(artifact, dict):
        raise ParityError("conversion provenance is missing")
    return {
        "manifest": sha256_file(path),
        "bundle": _sha(source.get("model_bundle_sha256"), "conversion bundle"),
        "vocab": _sha(source.get("vocab_sha256"), "conversion vocabulary"),
        "model": _sha(artifact.get("sha256"), "conversion model"),
    }


def _pytorch_hashes(
    manifest: Mapping[str, Any],
    manifest_path: Path,
) -> tuple[str, str]:
    if manifest.get("backend") != PYTORCH_BACKEND:
        raise ParityError("first prediction backend must be pytorch-koelectra")
    declared = _sha(
        manifest.get("model_bundle_sha256"),
        "PyTorch bundle",
    )
    if _sha(manifest.get("model_artifact_sha256"), "PyTorch model") != declared:
        raise ParityError("PyTorch model hashes differ")
    directory = _resolved_path(
        manifest.get("model_bundle"),
        manifest_path,
        "PyTorch bundle",
    )
    if directory.is_symlink() or not directory.is_dir():
        raise ParityError("PyTorch bundle is not a regular directory")
    try:
        _, actual = model_bundle_hashes(directory)
    except ContractError as error:
        raise ParityError(str(error)) from error
    if actual != declared:
        raise ParityError("PyTorch bundle SHA-256 mismatch")
    return actual, _sha(manifest.get("vocab_sha256"), "PyTorch vocabulary")


def _tflite_hashes(manifest: Mapping[str, Any]) -> tuple[str, str]:
    if manifest.get("backend") != TFLITE_BACKEND:
        raise ParityError("second prediction backend must be tensorflow-lite")
    model = _sha(manifest.get("model_artifact_sha256"), "TFLite model")
    if _sha(manifest.get("model_sha256"), "TFLite model") != model:
        raise ParityError("TFLite model hashes differ")
    return model, _sha(manifest.get("vocab_sha256"), "TFLite vocabulary")


def _nearest_rank(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def _errors(values: Sequence[float]) -> dict[str, float]:
    return {
        "mean": statistics.fmean(values),
        "p95": _nearest_rank(values, 0.95),
        "maximum": max(values),
    }


def _quality(
    rows: Sequence[dict[str, Any]],
    thresholds: SemanticThresholds,
) -> dict[str, Any]:
    result = evaluate_predictions(rows, thresholds)
    raw = result["raw"]["overall"]
    runtime = result["runtime"]["overall"]
    return {
        "raw": {
            "accuracy": raw["accuracy"],
            "macro_f1": raw["macro_f1"],
            "marketing_precision": raw["marketing_precision"],
            "locale_macro_f1": {
                locale: result["raw"]["by_locale"][locale]["macro_f1"]
                for locale in ("ko", "en", "mixed")
            },
        },
        "runtime": {
            "accuracy": runtime["accuracy"],
            "macro_f1": runtime["macro_f1"],
            "marketing_precision": runtime["marketing_precision"],
        },
        "trusted_coverage": result["trusted_coverage"]["overall"]["rate"],
        "trusted_marketing_false_positives": result["safety"][
            "trusted_non_marketing_predicted_marketing_count"
        ],
    }


def _delta(first: Mapping[str, Any], second: Mapping[str, Any]) -> dict[str, Any]:
    fields = ("accuracy", "macro_f1", "marketing_precision")
    return {
        "raw": {
            **{
                field: second["raw"][field] - first["raw"][field]
                for field in fields
            },
            "locale_macro_f1": {
                locale: (
                    second["raw"]["locale_macro_f1"][locale]
                    - first["raw"]["locale_macro_f1"][locale]
                )
                for locale in ("ko", "en", "mixed")
            },
        },
        "runtime": {
            field: second["runtime"][field] - first["runtime"][field]
            for field in fields
        },
        "trusted_coverage": (
            second["trusted_coverage"] - first["trusted_coverage"]
        ),
        "trusted_marketing_false_positives": (
            second["trusted_marketing_false_positives"]
            - first["trusted_marketing_false_positives"]
        ),
    }


def _agreement(first: Mapping[str, str], second: Mapping[str, str]) -> dict[str, Any]:
    count = sum(first[key] == second[key] for key in first)
    return {"count": count, "row_count": len(first), "rate": count / len(first)}


def _has_absolute_path(value: Any) -> bool:
    if isinstance(value, dict):
        return any(_has_absolute_path(child) for child in value.values())
    if isinstance(value, list):
        return any(_has_absolute_path(child) for child in value)
    if not isinstance(value, str):
        return False
    return (
        Path(value).is_absolute()
        or value.startswith(("~/", "~\\", "\\\\", "file://"))
        or WINDOWS_PATH.match(value) is not None
    )


def build_parity_report(
    *,
    pytorch_predictions: Path,
    tflite_predictions: Path,
    source_manifest: Path,
    conversion_manifest: Path,
    thresholds: SemanticThresholds | Mapping[str, Any],
) -> dict[str, Any]:
    """Validate exact lineage and compare only validation or test predictions."""

    selected_thresholds = _thresholds(thresholds)
    pytorch_predictions = pytorch_predictions.resolve(strict=True)
    tflite_predictions = tflite_predictions.resolve(strict=True)
    source_manifest = source_manifest.resolve(strict=True)
    conversion_manifest = conversion_manifest.resolve(strict=True)
    if _load_json(source_manifest, "source manifest").get(
        "schema_version"
    ) not in DATASET_SCHEMA_VERSIONS:
        raise ParityError(
            "parity accepts only the development dataset; "
            "sealed holdouts must not be read"
        )

    pytorch_manifest, pytorch_manifest_path = _prediction_manifest(
        pytorch_predictions
    )
    tflite_manifest, _ = _prediction_manifest(tflite_predictions)
    splits = {
        pytorch_manifest.get("selected_split"),
        tflite_manifest.get("selected_split"),
    }
    if len(splits) != 1 or next(iter(splits)) not in ALLOWED_SPLITS:
        raise ParityError("both predictions must select validation or test")
    split = str(next(iter(splits)))

    try:
        pytorch = load_bound_prediction_set(
            [pytorch_predictions],
            source_manifest,
        )
        tflite = load_bound_prediction_set(
            [tflite_predictions],
            source_manifest,
        )
    except (EvaluationError, OSError, UnicodeError) as error:
        raise ParityError(str(error)) from error
    if (
        pytorch.provenance["backend"] != PYTORCH_BACKEND
        or tflite.provenance["backend"] != TFLITE_BACKEND
    ):
        raise ParityError("prediction backends are reversed or unsupported")

    conversion = _conversion_hashes(conversion_manifest)
    pytorch_bundle, pytorch_vocab = _pytorch_hashes(
        pytorch_manifest,
        pytorch_manifest_path,
    )
    tflite_model, tflite_vocab = _tflite_hashes(tflite_manifest)
    if pytorch_bundle != conversion["bundle"]:
        raise ParityError("PyTorch bundle is not the conversion source")
    if tflite_model != conversion["model"]:
        raise ParityError("TFLite model is not the conversion artifact")
    if {pytorch_vocab, tflite_vocab, conversion["vocab"]} != {
        conversion["vocab"]
    }:
        raise ParityError("backend and conversion vocabularies differ")
    if (
        pytorch.provenance["source_manifest_sha256"]
        != tflite.provenance["source_manifest_sha256"]
    ):
        raise ParityError("source-manifest provenance differs")

    pytorch_rows = {str(row["id"]): row for row in pytorch.rows}
    tflite_rows = {str(row["id"]): row for row in tflite.rows}
    if set(pytorch_rows) != set(tflite_rows):
        raise ParityError("prediction ID coverage differs")
    bound_fields = ("locale", "intent", "injection", "pair_id", "split")
    if any(
        any(
            pytorch_rows[key][field] != tflite_rows[key][field]
            for field in bound_fields
        )
        for key in pytorch_rows
    ):
        raise ParityError("prediction metadata differs")

    raw_pytorch = {
        key: str(row["predicted_intent"]) for key, row in pytorch_rows.items()
    }
    raw_tflite = {
        key: str(row["predicted_intent"]) for key, row in tflite_rows.items()
    }
    runtime_pytorch = {
        key: runtime_output(row, selected_thresholds)
        for key, row in pytorch_rows.items()
    }
    runtime_tflite = {
        key: runtime_output(row, selected_thresholds)
        for key, row in tflite_rows.items()
    }
    probability_errors: list[float] = []
    confidence_errors: list[float] = []
    total_variation: list[float] = []
    for key, first in pytorch_rows.items():
        second = tflite_rows[key]
        row_errors = [
            abs(first["probabilities"][label] - second["probabilities"][label])
            for label in LABELS
        ]
        probability_errors.extend(row_errors)
        confidence_errors.append(abs(first["confidence"] - second["confidence"]))
        total_variation.append(0.5 * math.fsum(row_errors))

    marketing_pytorch = {
        key for key, value in runtime_pytorch.items() if value == "MARKETING"
    }
    marketing_tflite = {
        key for key, value in runtime_tflite.items() if value == "MARKETING"
    }
    pytorch_quality = _quality(pytorch.rows, selected_thresholds)
    tflite_quality = _quality(tflite.rows, selected_thresholds)
    report = {
        "schema_version": SCHEMA_VERSION,
        "labels": list(LABELS),
        "split": split,
        "general_threshold": selected_thresholds.general,
        "marketing_threshold": selected_thresholds.marketing,
        "row_count": len(pytorch_rows),
        "provenance": {
            "source_manifest_sha256": pytorch.provenance[
                "source_manifest_sha256"
            ],
            "conversion_manifest_sha256": conversion["manifest"],
            "pytorch_model_bundle_sha256": pytorch_bundle,
            "tflite_model_sha256": tflite_model,
            "vocab_sha256": conversion["vocab"],
            "pytorch_predictions_sha256": sha256_file(pytorch_predictions),
            "tflite_predictions_sha256": sha256_file(tflite_predictions),
        },
        "agreement": {
            "raw_argmax": _agreement(raw_pytorch, raw_tflite),
            "runtime_output": _agreement(runtime_pytorch, runtime_tflite),
            "trusted_marketing": {
                "pytorch_count": len(marketing_pytorch),
                "tflite_count": len(marketing_tflite),
                "disagreement_count": len(
                    marketing_pytorch ^ marketing_tflite
                ),
                "introduced_by_tflite_count": len(
                    marketing_tflite - marketing_pytorch
                ),
                "introduced_unsafe_by_tflite_count": sum(
                    tflite_rows[key]["intent"] != "MARKETING"
                    for key in marketing_tflite - marketing_pytorch
                ),
            },
        },
        "probability_error": {
            "percentile_method": "nearest-rank",
            "absolute_per_label": _errors(probability_errors),
            "confidence": _errors(confidence_errors),
            "row_total_variation": _errors(total_variation),
        },
        "quality": {
            "pytorch": pytorch_quality,
            "tflite": tflite_quality,
            "tflite_minus_pytorch": _delta(
                pytorch_quality,
                tflite_quality,
            ),
        },
    }
    if _has_absolute_path(report):
        raise ParityError("parity evidence must not contain absolute paths")
    return report


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(
                value,
                stream,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
                allow_nan=False,
            )
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pytorch-predictions", type=Path, required=True)
    parser.add_argument("--tflite-predictions", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--conversion-manifest", type=Path, required=True)
    parser.add_argument("--general-threshold", type=float, required=True)
    parser.add_argument("--marketing-threshold", type=float, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        report = build_parity_report(
            pytorch_predictions=arguments.pytorch_predictions,
            tflite_predictions=arguments.tflite_predictions,
            source_manifest=arguments.source_manifest,
            conversion_manifest=arguments.conversion_manifest,
            thresholds={
                "general": arguments.general_threshold,
                "marketing": arguments.marketing_threshold,
            },
        )
        _write_json(arguments.output, report)
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
        return 0
    except (ParityError, OSError, UnicodeError) as error:
        print(f"compare_backend_parity: {error}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
