#!/usr/bin/env python3
"""Validate and atomically package the four Android semantic-model assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import struct
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from semantic_contract import (
    ContractError,
    LABELS,
    MAX_SEQUENCE_LENGTH,
    RELEASE_CONFIDENCE_THRESHOLD_FLOOR,
    WordPieceTokenizer,
    model_bundle_hashes,
    resolve_training_model_bundle,
)

MIB = 1024**2
MAX_MODEL_BYTES = 45 * MIB
MAX_VOCAB_BYTES = 5 * MIB
MAX_MANIFEST_BYTES = 64 * 1024
CONVERSION_MANIFEST_SCHEMA = "koelectra-litert-conversion-v2"
QUANTIZATION_AUDIT_SCHEMA = "koelectra-dynamic-int8-audit-v1"
THRESHOLD_SELECTION_SCHEMA = "semantic-threshold-selection-v4"
GATED_EVALUATION_SCHEMA = "semantic-gated-evaluation-v3"
PARITY_REPORT_SCHEMA = "semantic-backend-parity-v2"
EVALUATION_PROVENANCE_SCHEMA = "semantic-evaluation-provenance-v1"
EVALUATION_SCHEMA = "semantic-evaluation-v3"
OUTPUT_MANIFEST_SCHEMA = "alarmcontrol-semantic-model-manifest-v2"

CONVERSION_MODEL_FILENAME = "semantic_classifier.tflite"
CONVERSION_MANIFEST_FILENAME = "conversion_manifest.json"
SOURCE_VOCAB_FILENAME = "vocab.txt"

MODEL_ASSET_FILENAME = "semantic_notification_classifier.tflite"
VOCAB_ASSET_FILENAME = "semantic_vocab.txt"
LABELS_ASSET_FILENAME = "semantic_labels.txt"
MODEL_MANIFEST_FILENAME = "semantic_model_manifest.json"
OUTPUT_FILENAMES = {
    MODEL_ASSET_FILENAME,
    VOCAB_ASSET_FILENAME,
    LABELS_ASSET_FILENAME,
    MODEL_MANIFEST_FILENAME,
}
MINIMUM_CONFIDENCE_THRESHOLD = RELEASE_CONFIDENCE_THRESHOLD_FLOOR
MINIMUM_MACRO_F1 = 0.85
MINIMUM_MARKETING_PRECISION = 0.90
MINIMUM_LOCALE_MACRO_F1 = 0.80
MINIMUM_TRUSTED_COVERAGE = 0.60
MINIMUM_LOCALE_TRUSTED_COVERAGE = 0.40
MAXIMUM_TRUSTED_MARKETING_FALSE_POSITIVES = 0
EXPECTED_VALIDATION_ACTIONABLE_ROWS = 2268
EXPECTED_DEVELOPMENT_TEST_ROWS = 2646
EXPECTED_VALIDATION_ACTIONABLE_ROWS_BY_LOCALE = {
    "ko": 684,
    "en": 900,
    "mixed": 684,
}
EXPECTED_SEALED_HOLDOUT_ROWS = 420
EXPECTED_HOLDOUT_ACTIONABLE_ROWS = 360
LOCALES = ("ko", "en", "mixed")
EXPECTED_HOLDOUT_ACTIONABLE_ROWS_BY_LOCALE = {
    locale: 120 for locale in LOCALES
}
TRUSTED_COVERAGE_ELIGIBILITY = "actual-intent-not-ambiguous"

TOKENIZER_CONTRACT = {
    "type": "bert-wordpiece",
    "normalization": "nfc",
    "lowercase": False,
}


class PackagingError(ValueError):
    """Raised when release evidence cannot produce a valid asset package."""


@dataclass(frozen=True)
class PackageOptions:
    """Paths required to assemble one release-candidate asset directory."""

    conversion_dir: Path
    model_dir: Path
    threshold_selection: Path
    development_test_gate: Path
    test_parity_report: Path
    sealed_holdout_gate: Path
    output_dir: Path


@dataclass(frozen=True)
class RuntimeThresholds:
    """Exact deployment thresholds keyed by the predicted semantic intent."""

    general_threshold: float
    marketing_threshold: float


@dataclass(frozen=True)
class ValidatedInputs:
    """Validated immutable inputs used by the atomic writer."""

    model_path: Path
    vocab_path: Path
    model_size: int
    model_sha256: str
    vocab_size: int
    vocab_sha256: str
    thresholds: RuntimeThresholds
    quantization: dict[str, Any]
    quantization_audit: dict[str, Any]
    tensor_contract: dict[str, Any]
    evidence_sha256: dict[str, str]
    evaluation_provenance: dict[str, dict[str, Any]]


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PackagingError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise PackagingError(f"invalid JSON numeric constant: {value}")


def _load_json(path: Path, context: str) -> dict[str, Any]:
    _require_regular_file(path, context)
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_object,
            parse_constant=_reject_json_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PackagingError(f"{context}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise PackagingError(f"{context}: must contain one JSON object")
    return value


def _require_directory(path: Path, context: str) -> Path:
    expanded = path.expanduser()
    if expanded.is_symlink() or not expanded.is_dir():
        raise PackagingError(
            f"{context}: must be an existing non-symlink directory"
        )
    return expanded.resolve(strict=True)


def _require_regular_file(path: Path, context: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise PackagingError(
            f"{context}: must be an existing non-symlink regular file"
        )
    return path


def _require_exact_fields(
    value: Mapping[str, Any],
    expected: set[str],
    context: str,
) -> None:
    actual = set(value)
    if actual != expected:
        raise PackagingError(
            f"{context}: fields mismatch; "
            f"missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )


def _require_nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PackagingError(f"{context}: must be a nonempty string")
    if value != value.strip():
        raise PackagingError(
            f"{context}: surrounding whitespace is not allowed"
        )
    return value


def _require_sha256(value: Any, context: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise PackagingError(
            f"{context}: must be a lowercase SHA-256 digest"
        )
    return value


def _require_positive_int(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise PackagingError(f"{context}: must be a positive integer")
    return value


def _require_nonnegative_int(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PackagingError(f"{context}: must be a nonnegative integer")
    return value


def _require_finite_threshold(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PackagingError(f"{context}: must be a finite number")
    threshold = float(value)
    if not math.isfinite(threshold) or not 0.0 <= threshold <= 1.0:
        raise PackagingError(
            f"{context}: must be finite and within [0, 1]"
        )
    return threshold


def _require_float32_threshold(value: Any, context: str) -> float:
    threshold = _require_finite_threshold(value, context)
    try:
        roundtrip = struct.unpack(">f", struct.pack(">f", threshold))[0]
    except (OverflowError, struct.error) as error:
        raise PackagingError(
            f"{context}: cannot be represented as float32"
        ) from error
    if roundtrip != threshold:
        raise PackagingError(
            f"{context}: must be exactly representable as float32"
        )
    return threshold


def _require_runtime_thresholds(
    value: Mapping[str, Any],
    context: str,
) -> RuntimeThresholds:
    general = _require_float32_threshold(
        value.get("general_threshold"),
        f"{context}.general_threshold",
    )
    marketing = _require_float32_threshold(
        value.get("marketing_threshold"),
        f"{context}.marketing_threshold",
    )
    if general < MINIMUM_CONFIDENCE_THRESHOLD:
        raise PackagingError(
            f"{context}.general_threshold is below the runtime safety floor"
        )
    if marketing < MINIMUM_CONFIDENCE_THRESHOLD:
        raise PackagingError(
            f"{context}.marketing_threshold is below the runtime safety floor"
        )
    if marketing < general:
        raise PackagingError(
            f"{context}.marketing_threshold must be at least general_threshold"
        )
    return RuntimeThresholds(
        general_threshold=general,
        marketing_threshold=marketing,
    )


def _require_finite_metric(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise PackagingError(f"{context}: must be a finite number")
    metric = float(value)
    if not math.isfinite(metric):
        raise PackagingError(f"{context}: must be a finite number")
    return metric


def _require_mapping(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PackagingError(f"{context}: must be an object")
    return value


def _validate_coverage(
    value: Any,
    context: str,
    *,
    minimum_rate: float,
    expected_rows: int | None = None,
) -> dict[str, Any]:
    coverage = _require_mapping(value, context)
    _require_exact_fields(
        coverage,
        {"trusted_count", "row_count", "rate"},
        context,
    )
    trusted_count = _require_nonnegative_int(
        coverage["trusted_count"],
        f"{context}.trusted_count",
    )
    row_count = _require_positive_int(
        coverage["row_count"],
        f"{context}.row_count",
    )
    if expected_rows is not None and row_count != expected_rows:
        raise PackagingError(
            f"{context}.row_count must equal {expected_rows}"
        )
    if trusted_count > row_count:
        raise PackagingError(f"{context}: trusted_count exceeds row_count")
    rate = _require_finite_metric(coverage["rate"], f"{context}.rate")
    if not math.isclose(
        rate,
        trusted_count / row_count,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise PackagingError(f"{context}.rate contradicts its counts")
    if rate < minimum_rate:
        raise PackagingError(
            f"{context}.rate must be at least {minimum_rate}"
        )
    return coverage


def _validate_actionable_coverage(
    value: Any,
    context: str,
    *,
    expected_rows: int,
    expected_rows_by_locale: Mapping[str, int],
) -> dict[str, Any]:
    coverage = _require_mapping(value, context)
    _require_exact_fields(
        coverage,
        {"eligibility", "overall", "by_locale"},
        context,
    )
    if coverage["eligibility"] != TRUSTED_COVERAGE_ELIGIBILITY:
        raise PackagingError(
            f"{context}.eligibility must be "
            f"{TRUSTED_COVERAGE_ELIGIBILITY!r}"
        )
    overall = _validate_coverage(
        coverage["overall"],
        f"{context}.overall",
        minimum_rate=MINIMUM_TRUSTED_COVERAGE,
        expected_rows=expected_rows,
    )
    by_locale = _require_mapping(
        coverage["by_locale"],
        f"{context}.by_locale",
    )
    if set(by_locale) != set(LOCALES):
        raise PackagingError(f"{context}.by_locale has wrong locales")
    if set(expected_rows_by_locale) != set(LOCALES):
        raise PackagingError(
            f"{context}: expected locale-row contract has wrong locales"
        )
    locale_coverages = {
        locale: _validate_coverage(
            by_locale[locale],
            f"{context}.by_locale.{locale}",
            minimum_rate=MINIMUM_LOCALE_TRUSTED_COVERAGE,
            expected_rows=expected_rows_by_locale[locale],
        )
        for locale in LOCALES
    }
    if overall["trusted_count"] != sum(
        item["trusted_count"]
        for item in locale_coverages.values()
    ):
        raise PackagingError(
            f"{context}.overall.trusted_count contradicts by_locale"
        )
    if overall["row_count"] != sum(
        item["row_count"]
        for item in locale_coverages.values()
    ):
        raise PackagingError(
            f"{context}.overall.row_count contradicts by_locale"
        )
    return coverage


def _validate_gate_checks(value: Any, context: str) -> dict[str, Any]:
    gate = _require_mapping(value, context)
    _require_exact_fields(gate, {"passed", "checks"}, context)
    if gate["passed"] is not True:
        raise PackagingError(f"{context}.passed must be true")
    checks = _require_mapping(gate["checks"], f"{context}.checks")
    required_checks = {
        "raw_macro_f1",
        "raw_marketing_precision",
        "raw_locale_macro_f1",
        "trusted_marketing_false_positives",
        "minimum_trusted_coverage",
        "minimum_locale_trusted_coverage",
    }
    if set(checks) != required_checks:
        raise PackagingError(
            f"{context}.checks: expected exactly {sorted(required_checks)}"
        )

    def require_minimum_check(
        name: str,
        required_minimum: float,
    ) -> None:
        check = _require_mapping(
            checks[name],
            f"{context}.checks.{name}",
        )
        _require_exact_fields(
            check,
            {"actual", "minimum", "passed"},
            f"{context}.checks.{name}",
        )
        actual = _require_finite_metric(
            check["actual"],
            f"{context}.checks.{name}.actual",
        )
        minimum = _require_finite_metric(
            check["minimum"],
            f"{context}.checks.{name}.minimum",
        )
        if (
            check["passed"] is not True
            or minimum < required_minimum
            or actual < required_minimum
            or actual < minimum
        ):
            raise PackagingError(
                f"{context}.checks.{name} does not enforce the release floor"
            )

    require_minimum_check("raw_macro_f1", MINIMUM_MACRO_F1)
    require_minimum_check(
        "raw_marketing_precision",
        MINIMUM_MARKETING_PRECISION,
    )
    require_minimum_check(
        "minimum_trusted_coverage",
        MINIMUM_TRUSTED_COVERAGE,
    )

    locale_checks = _require_mapping(
        checks["raw_locale_macro_f1"],
        f"{context}.checks.raw_locale_macro_f1",
    )
    if set(locale_checks) != set(LOCALES):
        raise PackagingError(
            f"{context}.checks.raw_locale_macro_f1 has wrong locales"
        )
    for locale in LOCALES:
        check = _require_mapping(
            locale_checks[locale],
            f"{context}.checks.raw_locale_macro_f1.{locale}",
        )
        _require_exact_fields(
            check,
            {"actual", "minimum", "passed"},
            f"{context}.checks.raw_locale_macro_f1.{locale}",
        )
        actual = _require_finite_metric(
            check["actual"],
            f"{context}.checks.raw_locale_macro_f1.{locale}.actual",
        )
        minimum = _require_finite_metric(
            check["minimum"],
            f"{context}.checks.raw_locale_macro_f1.{locale}.minimum",
        )
        if (
            check["passed"] is not True
            or minimum < MINIMUM_LOCALE_MACRO_F1
            or actual < MINIMUM_LOCALE_MACRO_F1
            or actual < minimum
        ):
            raise PackagingError(
                f"{context}: locale {locale} does not meet release floor"
            )

    locale_coverage_checks = _require_mapping(
        checks["minimum_locale_trusted_coverage"],
        f"{context}.checks.minimum_locale_trusted_coverage",
    )
    if set(locale_coverage_checks) != set(LOCALES):
        raise PackagingError(
            f"{context}.checks.minimum_locale_trusted_coverage "
            "has wrong locales"
        )
    for locale in LOCALES:
        check = _require_mapping(
            locale_coverage_checks[locale],
            f"{context}.checks.minimum_locale_trusted_coverage.{locale}",
        )
        _require_exact_fields(
            check,
            {"actual", "minimum", "passed"},
            f"{context}.checks.minimum_locale_trusted_coverage.{locale}",
        )
        actual = _require_finite_metric(
            check["actual"],
            f"{context}.checks.minimum_locale_trusted_coverage."
            f"{locale}.actual",
        )
        minimum = _require_finite_metric(
            check["minimum"],
            f"{context}.checks.minimum_locale_trusted_coverage."
            f"{locale}.minimum",
        )
        if (
            check["passed"] is not True
            or minimum < MINIMUM_LOCALE_TRUSTED_COVERAGE
            or actual < MINIMUM_LOCALE_TRUSTED_COVERAGE
            or actual < minimum
        ):
            raise PackagingError(
                f"{context}: locale {locale} trusted coverage "
                "does not meet release floor"
            )

    safety = _require_mapping(
        checks["trusted_marketing_false_positives"],
        f"{context}.checks.trusted_marketing_false_positives",
    )
    _require_exact_fields(
        safety,
        {"actual", "maximum", "passed"},
        f"{context}.checks.trusted_marketing_false_positives",
    )
    actual = _require_nonnegative_int(
        safety["actual"],
        f"{context}.checks.trusted_marketing_false_positives.actual",
    )
    maximum = _require_nonnegative_int(
        safety["maximum"],
        f"{context}.checks.trusted_marketing_false_positives.maximum",
    )
    if (
        safety["passed"] is not True
        or maximum > MAXIMUM_TRUSTED_MARKETING_FALSE_POSITIVES
        or actual > MAXIMUM_TRUSTED_MARKETING_FALSE_POSITIVES
        or actual > maximum
    ):
        raise PackagingError(
            f"{context}: trusted MARKETING false positives must be zero"
        )
    return gate


def _validate_gate_coverage_consistency(
    gate: Mapping[str, Any],
    coverage: Mapping[str, Any],
    context: str,
) -> None:
    checks = gate["checks"]
    overall_rate = coverage["overall"]["rate"]
    gated_overall_rate = checks["minimum_trusted_coverage"]["actual"]
    if not math.isclose(
        overall_rate,
        gated_overall_rate,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise PackagingError(
            f"{context}: overall trusted coverage contradicts gate"
        )
    for locale in LOCALES:
        locale_rate = coverage["by_locale"][locale]["rate"]
        gated_locale_rate = checks[
            "minimum_locale_trusted_coverage"
        ][locale]["actual"]
        if not math.isclose(
            locale_rate,
            gated_locale_rate,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise PackagingError(
                f"{context}: {locale} trusted coverage contradicts gate"
            )


def _validate_quantization(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PackagingError("conversion.quantization must be an object")
    expected_fields = {
        "requested",
        "applied",
        "calibration_used",
        "experimental_backend",
        "fallback_reason",
    }
    _require_exact_fields(
        value,
        expected_fields,
        "conversion.quantization",
    )
    requested = value["requested"]
    applied = value["applied"]
    if requested not in {"auto", "dynamic-int8", "float32"}:
        raise PackagingError("conversion.quantization.requested is unsupported")
    if applied != "dynamic-int8":
        raise PackagingError(
            "deployable conversion must apply dynamic-int8 quantization"
        )
    if requested != "auto" and requested != applied:
        raise PackagingError(
            "conversion quantization requested/applied values contradict"
        )
    if value["calibration_used"] is not False:
        raise PackagingError(
            "conversion quantization must not use calibration data"
        )
    if value["experimental_backend"] is not (applied == "dynamic-int8"):
        raise PackagingError(
            "conversion quantization experimental_backend is inconsistent"
        )
    fallback_reason = value["fallback_reason"]
    if requested == "auto" and applied == "float32":
        _require_nonempty_string(
            fallback_reason,
            "conversion.quantization.fallback_reason",
        )
    elif fallback_reason is not None:
        raise PackagingError(
            "conversion.quantization.fallback_reason must be null"
        )
    return dict(value)


def _validate_quantization_audit(value: Any) -> dict[str, Any]:
    audit = _require_mapping(value, "conversion.quantization_audit")
    _require_exact_fields(
        audit,
        {
            "schema_version",
            "method",
            "tensor_count",
            "int8_tensor_count",
            "operator_count",
            "quantize_operator_count",
            "passed",
        },
        "conversion.quantization_audit",
    )
    if audit["schema_version"] != QUANTIZATION_AUDIT_SCHEMA:
        raise PackagingError("unsupported quantization-audit schema")
    if (
        audit["method"]
        != "litert-interpreter-tensor-and-operator-inspection"
    ):
        raise PackagingError("unsupported quantization-audit method")
    tensor_count = _require_positive_int(
        audit["tensor_count"],
        "conversion.quantization_audit.tensor_count",
    )
    int8_tensor_count = _require_positive_int(
        audit["int8_tensor_count"],
        "conversion.quantization_audit.int8_tensor_count",
    )
    operator_count = _require_positive_int(
        audit["operator_count"],
        "conversion.quantization_audit.operator_count",
    )
    quantize_operator_count = _require_positive_int(
        audit["quantize_operator_count"],
        "conversion.quantization_audit.quantize_operator_count",
    )
    if int8_tensor_count > tensor_count:
        raise PackagingError(
            "conversion quantization audit has more INT8 tensors than tensors"
        )
    if quantize_operator_count > operator_count:
        raise PackagingError(
            "conversion quantization audit has more QUANTIZE ops than ops"
        )
    if audit["passed"] is not True:
        raise PackagingError("conversion quantization audit must pass")
    return dict(audit)


def _validate_tensor_contract(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PackagingError("conversion.tensor_contract must be an object")
    _require_exact_fields(
        value,
        {"inputs", "output"},
        "conversion.tensor_contract",
    )
    inputs = value["inputs"]
    if not isinstance(inputs, list):
        raise PackagingError("conversion.tensor_contract.inputs must be a list")
    expected_names = ["input_ids", "attention_mask"]
    if len(inputs) == 3:
        expected_names.append("token_type_ids")
    if len(inputs) != len(expected_names):
        raise PackagingError(
            "conversion tensor inputs must contain two or three tensors"
        )
    normalized_inputs: list[dict[str, Any]] = []
    for index, (tensor, expected_name) in enumerate(
        zip(inputs, expected_names, strict=True)
    ):
        context = f"conversion.tensor_contract.inputs[{index}]"
        if not isinstance(tensor, dict):
            raise PackagingError(f"{context}: must be an object")
        _require_exact_fields(
            tensor,
            {"name", "dtype", "shape"},
            context,
        )
        if tensor["name"] != expected_name:
            raise PackagingError(
                f"{context}.name must be {expected_name!r}"
            )
        if tensor["dtype"] != "int32":
            raise PackagingError(f"{context}.dtype must be 'int32'")
        if tensor["shape"] != [1, MAX_SEQUENCE_LENGTH]:
            raise PackagingError(f"{context}.shape must be [1, 128]")
        normalized_inputs.append(dict(tensor))

    output = value["output"]
    if not isinstance(output, dict):
        raise PackagingError(
            "conversion.tensor_contract.output must be an object"
        )
    _require_exact_fields(
        output,
        {"name", "dtype", "shape"},
        "conversion.tensor_contract.output",
    )
    _require_nonempty_string(
        output["name"],
        "conversion.tensor_contract.output.name",
    )
    if output["dtype"] != "float32":
        raise PackagingError(
            "conversion.tensor_contract.output.dtype must be 'float32'"
        )
    if output["shape"] != [1, len(LABELS)]:
        raise PackagingError(
            "conversion.tensor_contract.output.shape must be [1, 7]"
        )
    return {
        "inputs": normalized_inputs,
        "output": dict(output),
    }


def _validate_conversion(
    conversion_dir: Path,
    *,
    max_model_bytes: int = MAX_MODEL_BYTES,
) -> tuple[
    Path,
    int,
    str,
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    str,
    str,
    str,
]:
    directory = _require_directory(conversion_dir, "--conversion-dir")
    if {path.name for path in directory.iterdir()} != {
        CONVERSION_MODEL_FILENAME,
        CONVERSION_MANIFEST_FILENAME,
    }:
        raise PackagingError(
            "--conversion-dir must contain exactly the model and manifest"
        )
    model_path = _require_regular_file(
        directory / CONVERSION_MODEL_FILENAME,
        "converted model",
    )
    manifest_path = directory / CONVERSION_MANIFEST_FILENAME
    manifest = _load_json(manifest_path, "conversion manifest")
    if manifest.get("schema_version") != CONVERSION_MANIFEST_SCHEMA:
        raise PackagingError("unsupported conversion manifest schema")
    if manifest.get("labels") != list(LABELS):
        raise PackagingError(
            "conversion labels must match the exact semantic label order"
        )
    source = _require_mapping(
        manifest.get("source"),
        "conversion.source",
    )
    conversion_vocab_sha256 = _require_sha256(
        source.get("vocab_sha256"),
        "conversion.source.vocab_sha256",
    )
    conversion_model_bundle_sha256 = _require_sha256(
        source.get("model_bundle_sha256"),
        "conversion.source.model_bundle_sha256",
    )
    if max_model_bytes <= 0 or max_model_bytes > MAX_MODEL_BYTES:
        raise PackagingError("invalid internal model-size ceiling")
    size = model_path.stat().st_size
    if size <= 0 or size > max_model_bytes:
        raise PackagingError(
            f"converted model size must be within 1..{max_model_bytes} bytes"
        )
    artifact = manifest.get("artifact")
    if not isinstance(artifact, dict):
        raise PackagingError("conversion.artifact must be an object")
    _require_exact_fields(
        artifact,
        {"file", "size_bytes", "max_size_bytes", "sha256"},
        "conversion.artifact",
    )
    if artifact["file"] != CONVERSION_MODEL_FILENAME:
        raise PackagingError("conversion artifact filename is incompatible")
    if _require_positive_int(
        artifact["size_bytes"],
        "conversion.artifact.size_bytes",
    ) != size:
        raise PackagingError("conversion artifact size does not match model")
    declared_maximum = _require_positive_int(
        artifact["max_size_bytes"],
        "conversion.artifact.max_size_bytes",
    )
    if declared_maximum > MAX_MODEL_BYTES or size > declared_maximum:
        raise PackagingError("conversion artifact exceeds its size ceiling")
    model_sha256 = _sha256_file(model_path)
    if _require_sha256(
        artifact["sha256"],
        "conversion.artifact.sha256",
    ) != model_sha256:
        raise PackagingError("conversion artifact SHA-256 does not match model")
    quantization = _validate_quantization(manifest.get("quantization"))
    quantization_audit = _validate_quantization_audit(
        manifest.get("quantization_audit")
    )
    tensor_contract = _validate_tensor_contract(
        manifest.get("tensor_contract")
    )
    return (
        model_path,
        size,
        model_sha256,
        quantization,
        quantization_audit,
        tensor_contract,
        _sha256_file(manifest_path),
        conversion_vocab_sha256,
        conversion_model_bundle_sha256,
    )


def _validate_vocab(model_dir: Path) -> tuple[Path, int, str]:
    if model_dir.expanduser().is_symlink():
        raise PackagingError("--model-dir must not be a symlink")
    try:
        selected = resolve_training_model_bundle(model_dir)
    except (OSError, ValueError) as error:
        raise PackagingError(
            f"--model-dir has no valid committed generation: {model_dir}"
        ) from error
    directory = _require_directory(selected, "--model-dir")
    vocab_path = _require_regular_file(
        directory / SOURCE_VOCAB_FILENAME,
        "trained WordPiece vocabulary",
    )
    try:
        raw = vocab_path.read_bytes()
        vocabulary = raw.decode("utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise PackagingError("vocab.txt must be valid UTF-8") from error
    if not raw or len(raw) > MAX_VOCAB_BYTES:
        raise PackagingError(
            f"vocab.txt size must be within 1..{MAX_VOCAB_BYTES} bytes"
        )
    if not vocabulary or any(not token for token in vocabulary):
        raise PackagingError("vocab.txt must not contain empty tokens")
    try:
        WordPieceTokenizer(
            vocabulary,
            max_sequence_length=MAX_SEQUENCE_LENGTH,
            lowercase=False,
        )
    except ValueError as error:
        raise PackagingError(f"invalid WordPiece vocabulary: {error}") from error
    return vocab_path, len(raw), _sha256_bytes(raw)


def _validate_evaluation_provenance(
    value: Any,
    context: str,
    *,
    model_sha256: str,
    vocab_sha256: str,
) -> dict[str, Any]:
    provenance = _require_mapping(value, context)
    _require_exact_fields(
        provenance,
        {
            "schema_version",
            "source_manifest_sha256",
            "backend",
            "model_artifact_sha256",
            "vocab_sha256",
        },
        context,
    )
    if provenance["schema_version"] != EVALUATION_PROVENANCE_SCHEMA:
        raise PackagingError(f"{context}: unsupported schema")
    if provenance["backend"] != "tensorflow-lite":
        raise PackagingError(f"{context}: backend must be tensorflow-lite")
    _require_sha256(
        provenance["source_manifest_sha256"],
        f"{context}.source_manifest_sha256",
    )
    if _require_sha256(
        provenance["model_artifact_sha256"],
        f"{context}.model_artifact_sha256",
    ) != model_sha256:
        raise PackagingError(f"{context}: model SHA-256 mismatch")
    if _require_sha256(
        provenance["vocab_sha256"],
        f"{context}.vocab_sha256",
    ) != vocab_sha256:
        raise PackagingError(f"{context}: vocabulary SHA-256 mismatch")
    return dict(provenance)


def _validate_threshold_selection(
    path: Path,
    *,
    model_sha256: str,
    vocab_sha256: str,
) -> tuple[RuntimeThresholds, str, dict[str, Any]]:
    selection = _load_json(path, "threshold selection")
    _require_exact_fields(
        selection,
        {
            "schema_version",
            "status",
            "validation_only",
            "general_threshold",
            "marketing_threshold",
            "trusted_coverage",
            "gate",
            "provenance",
        },
        "threshold selection",
    )
    if selection.get("schema_version") != THRESHOLD_SELECTION_SCHEMA:
        raise PackagingError("unsupported threshold-selection schema")
    if selection.get("status") != "selected":
        raise PackagingError("threshold selection status must be 'selected'")
    if selection.get("validation_only") is not True:
        raise PackagingError("threshold selection must be validation-only")
    coverage = _validate_actionable_coverage(
        selection.get("trusted_coverage"),
        "threshold selection.trusted_coverage",
        expected_rows=EXPECTED_VALIDATION_ACTIONABLE_ROWS,
        expected_rows_by_locale=EXPECTED_VALIDATION_ACTIONABLE_ROWS_BY_LOCALE,
    )
    gate = _validate_gate_checks(
        selection.get("gate"),
        "threshold selection.gate",
    )
    _validate_gate_coverage_consistency(
        gate,
        coverage,
        "threshold selection",
    )
    thresholds = _require_runtime_thresholds(
        selection,
        "threshold selection",
    )
    provenance = _validate_evaluation_provenance(
        selection.get("provenance"),
        "threshold selection.provenance",
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    return thresholds, _sha256_file(path), provenance


def _validate_gated_evaluation(
    path: Path,
    selected_thresholds: RuntimeThresholds,
    *,
    context: str,
    expected_total_rows: int,
    expected_actionable_rows: int,
    expected_actionable_rows_by_locale: Mapping[str, int],
    model_sha256: str,
    vocab_sha256: str,
) -> tuple[str, dict[str, Any], dict[str, Any]]:
    result = _load_json(path, f"{context} gate")
    _require_exact_fields(
        result,
        {
            "schema_version",
            "general_threshold",
            "marketing_threshold",
            "evaluation",
            "gate",
            "provenance",
        },
        f"{context} result",
    )
    if result["schema_version"] != GATED_EVALUATION_SCHEMA:
        raise PackagingError(f"unsupported {context} gate schema")
    gated_thresholds = _require_runtime_thresholds(
        result,
        f"{context} result",
    )
    if gated_thresholds != selected_thresholds:
        raise PackagingError(
            f"{context} gate thresholds do not equal "
            "the selected thresholds"
        )
    gate = _validate_gate_checks(
        result.get("gate"),
        f"{context}.gate",
    )
    evaluation = _require_mapping(
        result.get("evaluation"),
        f"{context}.evaluation",
    )
    _require_exact_fields(
        evaluation,
        {
            "schema_version",
            "labels",
            "general_threshold",
            "marketing_threshold",
            "row_count",
            "raw",
            "runtime",
            "pair_consistency",
            "trusted_coverage",
            "safety",
        },
        f"{context}.evaluation",
    )
    if evaluation.get("schema_version") != EVALUATION_SCHEMA:
        raise PackagingError(f"unsupported {context} evaluation schema")
    if evaluation.get("labels") != list(LABELS):
        raise PackagingError(
            f"{context} labels must match the exact semantic label order"
        )
    evaluated_thresholds = _require_runtime_thresholds(
        evaluation,
        f"{context}.evaluation",
    )
    if evaluated_thresholds != gated_thresholds:
        raise PackagingError(
            f"{context} evaluation thresholds do not equal "
            "the gate thresholds"
        )
    row_count = _require_positive_int(
        evaluation.get("row_count"),
        f"{context}.evaluation.row_count",
    )
    if row_count != expected_total_rows:
        raise PackagingError(
            f"{context} must contain exactly {expected_total_rows} rows"
        )
    raw = _require_mapping(
        evaluation.get("raw"),
        f"{context}.evaluation.raw",
    )
    overall = _require_mapping(
        raw.get("overall"),
        f"{context}.evaluation.raw.overall",
    )
    macro_f1 = _require_finite_metric(
        overall.get("macro_f1"),
        f"{context}.evaluation.raw.overall.macro_f1",
    )
    marketing_precision = _require_finite_metric(
        overall.get("marketing_precision"),
        f"{context}.evaluation.raw.overall.marketing_precision",
    )
    if macro_f1 < MINIMUM_MACRO_F1:
        raise PackagingError(f"{context} macro-F1 is below release floor")
    if marketing_precision < MINIMUM_MARKETING_PRECISION:
        raise PackagingError(
            f"{context} MARKETING precision is below release floor"
        )
    by_locale = _require_mapping(
        raw.get("by_locale"),
        f"{context}.evaluation.raw.by_locale",
    )
    if set(by_locale) != set(LOCALES):
        raise PackagingError(f"{context} has wrong locale coverage")
    for locale in LOCALES:
        locale_metrics = _require_mapping(
            by_locale[locale],
            f"{context}.evaluation.raw.by_locale.{locale}",
        )
        locale_macro_f1 = _require_finite_metric(
            locale_metrics.get("macro_f1"),
            f"{context}.evaluation.raw.by_locale.{locale}.macro_f1",
        )
        if locale_macro_f1 < MINIMUM_LOCALE_MACRO_F1:
            raise PackagingError(
                f"{context} {locale} macro-F1 is below release floor"
            )
    coverage = _validate_actionable_coverage(
        evaluation.get("trusted_coverage"),
        f"{context}.evaluation.trusted_coverage",
        expected_rows=expected_actionable_rows,
        expected_rows_by_locale=expected_actionable_rows_by_locale,
    )
    _validate_gate_coverage_consistency(
        gate,
        coverage,
        context,
    )
    safety = _require_mapping(
        evaluation.get("safety"),
        f"{context}.evaluation.safety",
    )
    false_positives = _require_nonnegative_int(
        safety.get("trusted_non_marketing_predicted_marketing_count"),
        f"{context}.evaluation.safety."
        "trusted_non_marketing_predicted_marketing_count",
    )
    if false_positives > MAXIMUM_TRUSTED_MARKETING_FALSE_POSITIVES:
        raise PackagingError(
            f"{context} trusted MARKETING false positives must be zero"
        )
    provenance = _validate_evaluation_provenance(
        result.get("provenance"),
        f"{context}.provenance",
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    return _sha256_file(path), provenance, dict(evaluation)


def _validate_development_test_gate(
    path: Path,
    selected_thresholds: RuntimeThresholds,
    *,
    model_sha256: str,
    vocab_sha256: str,
) -> tuple[str, dict[str, Any], dict[str, Any]]:
    return _validate_gated_evaluation(
        path,
        selected_thresholds,
        context="development test",
        expected_total_rows=EXPECTED_DEVELOPMENT_TEST_ROWS,
        expected_actionable_rows=EXPECTED_VALIDATION_ACTIONABLE_ROWS,
        expected_actionable_rows_by_locale=(
            EXPECTED_VALIDATION_ACTIONABLE_ROWS_BY_LOCALE
        ),
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )


def _validate_sealed_holdout_gate(
    path: Path,
    selected_thresholds: RuntimeThresholds,
    *,
    model_sha256: str,
    vocab_sha256: str,
) -> tuple[str, dict[str, Any]]:
    digest, provenance, _ = _validate_gated_evaluation(
        path,
        selected_thresholds,
        context="sealed holdout",
        expected_total_rows=EXPECTED_SEALED_HOLDOUT_ROWS,
        expected_actionable_rows=EXPECTED_HOLDOUT_ACTIONABLE_ROWS,
        expected_actionable_rows_by_locale=(
            EXPECTED_HOLDOUT_ACTIONABLE_ROWS_BY_LOCALE
        ),
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    return digest, provenance


def _require_matching_metric(
    actual: Any,
    expected: Any,
    context: str,
) -> None:
    actual_number = _require_finite_metric(actual, context)
    expected_number = _require_finite_metric(expected, f"{context} expected")
    if not math.isclose(
        actual_number,
        expected_number,
        rel_tol=0.0,
        abs_tol=1e-12,
    ):
        raise PackagingError(f"{context} does not match development test")


def _require_unit_metric(value: Any, context: str) -> float:
    metric = _require_finite_metric(value, context)
    if not 0.0 <= metric <= 1.0:
        raise PackagingError(f"{context} must be within [0, 1]")
    return metric


def _validate_parity_quality_summary(
    value: Any,
    context: str,
) -> dict[str, Any]:
    summary = _require_mapping(value, context)
    _require_exact_fields(
        summary,
        {
            "raw",
            "runtime",
            "trusted_coverage",
            "trusted_marketing_false_positives",
        },
        context,
    )
    raw = _require_mapping(summary["raw"], f"{context}.raw")
    metric_fields = {"accuracy", "macro_f1", "marketing_precision"}
    _require_exact_fields(
        raw,
        metric_fields | {"locale_macro_f1"},
        f"{context}.raw",
    )
    runtime = _require_mapping(summary["runtime"], f"{context}.runtime")
    _require_exact_fields(runtime, metric_fields, f"{context}.runtime")
    raw_metrics = {
        field: _require_unit_metric(
            raw[field],
            f"{context}.raw.{field}",
        )
        for field in metric_fields
    }
    runtime_metrics = {
        field: _require_unit_metric(
            runtime[field],
            f"{context}.runtime.{field}",
        )
        for field in metric_fields
    }
    locales = _require_mapping(
        raw["locale_macro_f1"],
        f"{context}.raw.locale_macro_f1",
    )
    if set(locales) != set(LOCALES):
        raise PackagingError(f"{context} has incomplete locale quality")
    return {
        "raw": {
            **raw_metrics,
            "locale_macro_f1": {
                locale: _require_unit_metric(
                    locales[locale],
                    f"{context}.raw.locale_macro_f1.{locale}",
                )
                for locale in LOCALES
            },
        },
        "runtime": runtime_metrics,
        "trusted_coverage": _require_unit_metric(
            summary["trusted_coverage"],
            f"{context}.trusted_coverage",
        ),
        "trusted_marketing_false_positives": _require_nonnegative_int(
            summary["trusted_marketing_false_positives"],
            f"{context}.trusted_marketing_false_positives",
        ),
    }


def _validate_parity_delta(
    value: Any,
    *,
    pytorch: Mapping[str, Any],
    tflite: Mapping[str, Any],
) -> None:
    context = "test parity quality.tflite_minus_pytorch"
    delta = _require_mapping(value, context)
    _require_exact_fields(
        delta,
        {
            "raw",
            "runtime",
            "trusted_coverage",
            "trusted_marketing_false_positives",
        },
        context,
    )
    raw = _require_mapping(delta["raw"], f"{context}.raw")
    runtime = _require_mapping(delta["runtime"], f"{context}.runtime")
    metric_fields = {"accuracy", "macro_f1", "marketing_precision"}
    _require_exact_fields(
        raw,
        metric_fields | {"locale_macro_f1"},
        f"{context}.raw",
    )
    _require_exact_fields(runtime, metric_fields, f"{context}.runtime")

    def require_delta(
        actual: Any,
        expected: float,
        metric_context: str,
    ) -> None:
        metric = _require_finite_metric(actual, metric_context)
        if (
            not -1.0 <= metric <= 1.0
            or not math.isclose(
                metric,
                expected,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
        ):
            raise PackagingError(f"{metric_context} is inconsistent")

    for group_name, group in (("raw", raw), ("runtime", runtime)):
        for field in metric_fields:
            require_delta(
                group[field],
                tflite[group_name][field] - pytorch[group_name][field],
                f"{context}.{group_name}.{field}",
            )
    locale_delta = _require_mapping(
        raw["locale_macro_f1"],
        f"{context}.raw.locale_macro_f1",
    )
    if set(locale_delta) != set(LOCALES):
        raise PackagingError(f"{context} has incomplete locale deltas")
    for locale in LOCALES:
        require_delta(
            locale_delta[locale],
            (
                tflite["raw"]["locale_macro_f1"][locale]
                - pytorch["raw"]["locale_macro_f1"][locale]
            ),
            f"{context}.raw.locale_macro_f1.{locale}",
        )
    require_delta(
        delta["trusted_coverage"],
        tflite["trusted_coverage"] - pytorch["trusted_coverage"],
        f"{context}.trusted_coverage",
    )
    safety_delta = delta["trusted_marketing_false_positives"]
    if (
        isinstance(safety_delta, bool)
        or not isinstance(safety_delta, int)
        or safety_delta
        != (
            tflite["trusted_marketing_false_positives"]
            - pytorch["trusted_marketing_false_positives"]
        )
    ):
        raise PackagingError(
            f"{context}.trusted_marketing_false_positives is inconsistent"
        )


def _validate_parity_probability_error(value: Any) -> None:
    context = "test parity probability_error"
    errors = _require_mapping(value, context)
    _require_exact_fields(
        errors,
        {
            "percentile_method",
            "absolute_per_label",
            "confidence",
            "row_total_variation",
        },
        context,
    )
    if errors["percentile_method"] != "nearest-rank":
        raise PackagingError("test parity percentile method is unsupported")
    for name in (
        "absolute_per_label",
        "confidence",
        "row_total_variation",
    ):
        item = _require_mapping(errors[name], f"{context}.{name}")
        _require_exact_fields(
            item,
            {"mean", "p95", "maximum"},
            f"{context}.{name}",
        )
        mean = _require_unit_metric(item["mean"], f"{context}.{name}.mean")
        p95 = _require_unit_metric(item["p95"], f"{context}.{name}.p95")
        maximum = _require_unit_metric(
            item["maximum"],
            f"{context}.{name}.maximum",
        )
        if not mean <= p95 <= maximum:
            raise PackagingError(
                f"{context}.{name} percentile ordering is inconsistent"
            )


def _validate_test_parity_report(
    path: Path,
    selected_thresholds: RuntimeThresholds,
    *,
    conversion_manifest_sha256: str,
    model_bundle_sha256: str,
    model_sha256: str,
    vocab_sha256: str,
    source_manifest_sha256: str,
    development_evaluation: Mapping[str, Any],
) -> str:
    report = _load_json(path, "test parity report")
    _require_exact_fields(
        report,
        {
            "schema_version",
            "labels",
            "split",
            "general_threshold",
            "marketing_threshold",
            "row_count",
            "provenance",
            "agreement",
            "probability_error",
            "quality",
        },
        "test parity report",
    )
    if report["schema_version"] != PARITY_REPORT_SCHEMA:
        raise PackagingError("unsupported test-parity schema")
    if report["labels"] != list(LABELS) or report["split"] != "test":
        raise PackagingError("test parity must cover the exact test label set")
    if (
        _require_runtime_thresholds(report, "test parity")
        != selected_thresholds
    ):
        raise PackagingError("test parity thresholds differ from deployment")
    if (
        _require_positive_int(
            report["row_count"],
            "test parity row_count",
        )
        != EXPECTED_DEVELOPMENT_TEST_ROWS
    ):
        raise PackagingError(
            "test parity must cover the complete development test split"
        )

    provenance = _require_mapping(
        report["provenance"],
        "test parity provenance",
    )
    _require_exact_fields(
        provenance,
        {
            "source_manifest_sha256",
            "conversion_manifest_sha256",
            "pytorch_model_bundle_sha256",
            "tflite_model_sha256",
            "vocab_sha256",
            "pytorch_predictions_sha256",
            "tflite_predictions_sha256",
        },
        "test parity provenance",
    )
    expected_hashes = {
        "source_manifest_sha256": source_manifest_sha256,
        "conversion_manifest_sha256": conversion_manifest_sha256,
        "pytorch_model_bundle_sha256": model_bundle_sha256,
        "tflite_model_sha256": model_sha256,
        "vocab_sha256": vocab_sha256,
    }
    for field, expected in expected_hashes.items():
        if _require_sha256(
            provenance[field],
            f"test parity provenance.{field}",
        ) != expected:
            raise PackagingError(f"test parity provenance {field} mismatch")
    for field in (
        "pytorch_predictions_sha256",
        "tflite_predictions_sha256",
    ):
        _require_sha256(
            provenance[field],
            f"test parity provenance.{field}",
        )

    agreement = _require_mapping(report["agreement"], "test parity agreement")
    _require_exact_fields(
        agreement,
        {"raw_argmax", "runtime_output", "trusted_marketing"},
        "test parity agreement",
    )
    for name in ("raw_argmax", "runtime_output"):
        item = _require_mapping(
            agreement[name],
            f"test parity agreement.{name}",
        )
        _require_exact_fields(
            item,
            {"count", "row_count", "rate"},
            f"test parity agreement.{name}",
        )
        count = _require_nonnegative_int(
            item["count"],
            f"test parity agreement.{name}.count",
        )
        rows = _require_positive_int(
            item["row_count"],
            f"test parity agreement.{name}.row_count",
        )
        rate = _require_finite_metric(
            item["rate"],
            f"test parity agreement.{name}.rate",
        )
        if (
            rows != EXPECTED_DEVELOPMENT_TEST_ROWS
            or count > rows
            or not math.isclose(
                rate,
                count / rows,
                rel_tol=0.0,
                abs_tol=1e-12,
            )
        ):
            raise PackagingError(f"test parity agreement.{name} is inconsistent")
    trusted_marketing = _require_mapping(
        agreement["trusted_marketing"],
        "test parity agreement.trusted_marketing",
    )
    trusted_fields = {
        "pytorch_count",
        "tflite_count",
        "disagreement_count",
        "introduced_by_tflite_count",
        "introduced_unsafe_by_tflite_count",
    }
    _require_exact_fields(
        trusted_marketing,
        trusted_fields,
        "test parity agreement.trusted_marketing",
    )
    trusted_counts = {
        field: _require_nonnegative_int(
            trusted_marketing[field],
            f"test parity agreement.trusted_marketing.{field}",
        )
        for field in trusted_fields
    }
    if any(
        count > EXPECTED_DEVELOPMENT_TEST_ROWS
        for count in trusted_counts.values()
    ):
        raise PackagingError("test parity trusted MARKETING count is invalid")
    introduced_count = trusted_counts["introduced_by_tflite_count"]
    if (
        introduced_count > trusted_counts["tflite_count"]
        or trusted_counts["introduced_unsafe_by_tflite_count"]
        > introduced_count
        or (
            2 * introduced_count
            != (
                trusted_counts["disagreement_count"]
                + trusted_counts["tflite_count"]
                - trusted_counts["pytorch_count"]
            )
        )
    ):
        raise PackagingError(
            "test parity trusted MARKETING counts contradict"
        )
    if trusted_counts["introduced_unsafe_by_tflite_count"] != 0:
        raise PackagingError(
            "TFLite parity introduced an unsafe trusted MARKETING result"
        )

    quality = _require_mapping(report["quality"], "test parity quality")
    _require_exact_fields(
        quality,
        {"pytorch", "tflite", "tflite_minus_pytorch"},
        "test parity quality",
    )
    pytorch = _validate_parity_quality_summary(
        quality["pytorch"],
        "test parity PyTorch quality",
    )
    tflite = _validate_parity_quality_summary(
        quality["tflite"],
        "test parity TFLite quality",
    )
    _validate_parity_delta(
        quality["tflite_minus_pytorch"],
        pytorch=pytorch,
        tflite=tflite,
    )
    development_raw = _require_mapping(
        development_evaluation["raw"],
        "development test raw",
    )
    development_runtime = _require_mapping(
        development_evaluation["runtime"],
        "development test runtime",
    )
    parity_raw = _require_mapping(tflite["raw"], "test parity TFLite raw")
    parity_runtime = _require_mapping(
        tflite["runtime"],
        "test parity TFLite runtime",
    )
    for metric in ("accuracy", "macro_f1", "marketing_precision"):
        _require_matching_metric(
            parity_raw.get(metric),
            _require_mapping(
                development_raw["overall"],
                "development test raw overall",
            ).get(metric),
            f"test parity TFLite raw.{metric}",
        )
        _require_matching_metric(
            parity_runtime.get(metric),
            _require_mapping(
                development_runtime["overall"],
                "development test runtime overall",
            ).get(metric),
            f"test parity TFLite runtime.{metric}",
        )
    parity_locales = _require_mapping(
        parity_raw.get("locale_macro_f1"),
        "test parity TFLite raw.locale_macro_f1",
    )
    development_locales = _require_mapping(
        development_raw["by_locale"],
        "development test raw by_locale",
    )
    if set(parity_locales) != set(LOCALES):
        raise PackagingError("test parity TFLite locales are incomplete")
    for locale in LOCALES:
        _require_matching_metric(
            parity_locales[locale],
            _require_mapping(
                development_locales[locale],
                f"development test raw {locale}",
            ).get("macro_f1"),
            f"test parity TFLite raw.locale_macro_f1.{locale}",
        )
    _require_matching_metric(
        tflite["trusted_coverage"],
        _require_mapping(
            development_evaluation["trusted_coverage"],
            "development test trusted coverage",
        )["overall"]["rate"],
        "test parity TFLite trusted_coverage",
    )
    parity_false_positives = _require_nonnegative_int(
        tflite["trusted_marketing_false_positives"],
        "test parity TFLite trusted_marketing_false_positives",
    )
    development_false_positives = _require_nonnegative_int(
        _require_mapping(
            development_evaluation["safety"],
            "development test safety",
        )["trusted_non_marketing_predicted_marketing_count"],
        "development test trusted MARKETING false positives",
    )
    if parity_false_positives != development_false_positives:
        raise PackagingError(
            "test parity safety does not match development test"
        )

    _validate_parity_probability_error(report["probability_error"])
    return _sha256_file(path)


def validate_inputs(options: PackageOptions) -> tuple[PackageOptions, ValidatedInputs]:
    """Validate every release input before creating a temporary output."""

    output = options.output_dir.expanduser()
    if output.is_symlink() or output.exists():
        raise PackagingError("--output-dir must not already exist")
    output = output.resolve(strict=False)
    (
        model_path,
        model_size,
        model_sha256,
        quantization,
        quantization_audit,
        tensor_contract,
        conversion_manifest_sha256,
        conversion_vocab_sha256,
        conversion_model_bundle_sha256,
    ) = _validate_conversion(options.conversion_dir)
    vocab_path, vocab_size, vocab_sha256 = _validate_vocab(options.model_dir)
    if conversion_vocab_sha256 != vocab_sha256:
        raise PackagingError(
            "trained vocabulary does not match conversion provenance"
        )
    try:
        _, model_bundle_sha256 = model_bundle_hashes(vocab_path.parent)
    except ContractError as error:
        raise PackagingError(str(error)) from error
    if conversion_model_bundle_sha256 != model_bundle_sha256:
        raise PackagingError(
            "trained model bundle does not match conversion provenance"
        )
    threshold_selection = _require_regular_file(
        options.threshold_selection.expanduser(),
        "--threshold-selection",
    ).resolve(strict=True)
    (
        selected_thresholds,
        threshold_selection_sha256,
        threshold_provenance,
    ) = _validate_threshold_selection(
        threshold_selection,
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    development_test_gate = _require_regular_file(
        options.development_test_gate.expanduser(),
        "--development-test-gate",
    ).resolve(strict=True)
    (
        development_test_gate_sha256,
        development_test_provenance,
        development_evaluation,
    ) = _validate_development_test_gate(
        development_test_gate,
        selected_thresholds,
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    if (
        development_test_provenance["source_manifest_sha256"]
        != threshold_provenance["source_manifest_sha256"]
    ):
        raise PackagingError(
            "development test and threshold selection use different datasets"
        )
    test_parity_report = _require_regular_file(
        options.test_parity_report.expanduser(),
        "--test-parity-report",
    ).resolve(strict=True)
    test_parity_report_sha256 = _validate_test_parity_report(
        test_parity_report,
        selected_thresholds,
        conversion_manifest_sha256=conversion_manifest_sha256,
        model_bundle_sha256=model_bundle_sha256,
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
        source_manifest_sha256=threshold_provenance[
            "source_manifest_sha256"
        ],
        development_evaluation=development_evaluation,
    )
    sealed_holdout_gate = _require_regular_file(
        options.sealed_holdout_gate.expanduser(),
        "--sealed-holdout-gate",
    ).resolve(strict=True)
    holdout_gate_sha256, holdout_provenance = _validate_sealed_holdout_gate(
        sealed_holdout_gate,
        selected_thresholds,
        model_sha256=model_sha256,
        vocab_sha256=vocab_sha256,
    )
    normalized = PackageOptions(
        conversion_dir=model_path.parent,
        model_dir=vocab_path.parent,
        threshold_selection=threshold_selection,
        development_test_gate=development_test_gate,
        test_parity_report=test_parity_report,
        sealed_holdout_gate=sealed_holdout_gate,
        output_dir=output,
    )
    validated = ValidatedInputs(
        model_path=model_path,
        vocab_path=vocab_path,
        model_size=model_size,
        model_sha256=model_sha256,
        vocab_size=vocab_size,
        vocab_sha256=vocab_sha256,
        thresholds=selected_thresholds,
        quantization=quantization,
        quantization_audit=quantization_audit,
        tensor_contract=tensor_contract,
        evidence_sha256={
            "conversion_manifest_sha256": conversion_manifest_sha256,
            "threshold_selection_sha256": threshold_selection_sha256,
            "development_test_gate_sha256": (
                development_test_gate_sha256
            ),
            "test_parity_report_sha256": test_parity_report_sha256,
            "sealed_holdout_gate_sha256": holdout_gate_sha256,
        },
        evaluation_provenance={
            "threshold_selection": threshold_provenance,
            "development_test": development_test_provenance,
            "sealed_holdout": holdout_provenance,
        },
    )
    return normalized, validated


def _copy_regular_file(source: Path, destination: Path) -> None:
    with source.open("rb") as input_stream:
        with destination.open("xb") as output_stream:
            shutil.copyfileobj(input_stream, output_stream, length=1024 * 1024)
            output_stream.flush()
            os.fsync(output_stream.fileno())


def _write_bytes(path: Path, value: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    content = (
        json.dumps(
            value,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")
    _write_bytes(path, content)


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_output_directory(
    output_dir: Path,
    writer: Callable[[Path], Any],
) -> Any:
    """Commit a complete sibling directory with one atomic rename."""

    if output_dir.is_symlink() or output_dir.exists():
        raise PackagingError(f"output already exists: {output_dir}")
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(
            prefix=f".{output_dir.name}.",
            suffix=".tmp",
            dir=output_dir.parent,
        )
    )
    try:
        result = writer(temporary)
        if {path.name for path in temporary.iterdir()} != OUTPUT_FILENAMES:
            raise PackagingError("asset package must contain exactly four files")
        if any(
            path.is_symlink() or not path.is_file()
            for path in temporary.iterdir()
        ):
            raise PackagingError("asset package entries must be regular files")
        _fsync_directory(temporary)
        os.replace(temporary, output_dir)
        _fsync_directory(output_dir.parent)
        return result
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def package_assets(options: PackageOptions) -> dict[str, Any]:
    """Validate and atomically create one deployable semantic asset directory."""

    normalized, inputs = validate_inputs(options)
    labels_bytes = ("\n".join(LABELS) + "\n").encode("utf-8")

    def writer(directory: Path) -> dict[str, Any]:
        model_output = directory / MODEL_ASSET_FILENAME
        vocab_output = directory / VOCAB_ASSET_FILENAME
        labels_output = directory / LABELS_ASSET_FILENAME
        _copy_regular_file(inputs.model_path, model_output)
        _copy_regular_file(inputs.vocab_path, vocab_output)
        _write_bytes(labels_output, labels_bytes)

        payloads = {
            MODEL_ASSET_FILENAME: {
                "sha256": _sha256_file(model_output),
                "size_bytes": model_output.stat().st_size,
            },
            VOCAB_ASSET_FILENAME: {
                "sha256": _sha256_file(vocab_output),
                "size_bytes": vocab_output.stat().st_size,
            },
            LABELS_ASSET_FILENAME: {
                "sha256": _sha256_file(labels_output),
                "size_bytes": labels_output.stat().st_size,
            },
        }
        if payloads[MODEL_ASSET_FILENAME] != {
            "sha256": inputs.model_sha256,
            "size_bytes": inputs.model_size,
        }:
            raise PackagingError("packaged model changed while copying")
        if payloads[VOCAB_ASSET_FILENAME] != {
            "sha256": inputs.vocab_sha256,
            "size_bytes": inputs.vocab_size,
        }:
            raise PackagingError("packaged vocabulary changed while copying")

        manifest = {
            "schema_version": OUTPUT_MANIFEST_SCHEMA,
            "files": payloads,
            "labels": list(LABELS),
            "max_sequence_length": MAX_SEQUENCE_LENGTH,
            "general_threshold": inputs.thresholds.general_threshold,
            "marketing_threshold": inputs.thresholds.marketing_threshold,
            "tokenizer": TOKENIZER_CONTRACT,
            "conversion": {
                "quantization": inputs.quantization,
                "quantization_audit": inputs.quantization_audit,
                "tensor_contract": inputs.tensor_contract,
            },
            "evidence": inputs.evidence_sha256,
            "evaluation_provenance": inputs.evaluation_provenance,
        }
        manifest_path = directory / MODEL_MANIFEST_FILENAME
        _write_json(manifest_path, manifest)
        if manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
            raise PackagingError(
                f"model manifest exceeds {MAX_MANIFEST_BYTES} bytes"
            )
        return manifest

    return atomic_output_directory(normalized.output_dir, writer)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--conversion-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--threshold-selection", type=Path, required=True)
    parser.add_argument("--development-test-gate", type=Path, required=True)
    parser.add_argument("--test-parity-report", type=Path, required=True)
    parser.add_argument("--sealed-holdout-gate", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        manifest = package_assets(
            PackageOptions(
                conversion_dir=args.conversion_dir,
                model_dir=args.model_dir,
                threshold_selection=args.threshold_selection,
                development_test_gate=args.development_test_gate,
                test_parity_report=args.test_parity_report,
                sealed_holdout_gate=args.sealed_holdout_gate,
                output_dir=args.output_dir,
            )
        )
    except (PackagingError, OSError) as error:
        print(f"package_semantic_assets: {error}", file=__import__("sys").stderr)
        return 2
    print(
        json.dumps(
            {
                "output_dir": str(args.output_dir),
                "schema_version": manifest["schema_version"],
                "files": manifest["files"],
                "general_threshold": manifest["general_threshold"],
                "marketing_threshold": manifest["marketing_threshold"],
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
