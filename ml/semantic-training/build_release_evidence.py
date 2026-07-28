#!/usr/bin/env python3
"""Build deterministic, aggregate-only semantic evidence and a model card."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import struct
import tempfile
from pathlib import Path
from typing import Any, Mapping, Sequence
from urllib.parse import urlsplit

from package_semantic_assets import (
    LABELS_ASSET_FILENAME,
    MINIMUM_CONFIDENCE_THRESHOLD,
    MODEL_ASSET_FILENAME,
    MODEL_MANIFEST_FILENAME,
    OUTPUT_MANIFEST_SCHEMA,
    QUANTIZATION_AUDIT_SCHEMA,
    RuntimeThresholds,
    VOCAB_ASSET_FILENAME,
)
from semantic_contract import (
    LABELS,
    RUNTIME_TEXT_FORMAT_VERSION,
    ContractError,
    model_bundle_hashes,
    sha256_file,
)

SCHEMA_VERSION = "alarmcontrol-semantic-release-evidence-v3"
PARITY_SCHEMA_VERSION = "semantic-backend-parity-v2"
UPSTREAM_SCHEMA_VERSION = "semantic-upstream-model-provenance-v1"
TRAINING_SCHEMA_VERSION = "koelectra-training-manifest-v1"
CONVERSION_SCHEMA_VERSION = "koelectra-litert-conversion-v2"
THRESHOLD_SCHEMA_VERSION = "semantic-threshold-selection-v4"
GATE_SCHEMA_VERSION = "semantic-gated-evaluation-v3"
EVALUATION_SCHEMA_VERSION = "semantic-evaluation-v3"
PROVENANCE_SCHEMA_VERSION = "semantic-evaluation-provenance-v1"
HOLDOUT_SCHEMAS = {
    "semantic-sealed-holdout-manifest-v1",
    "semantic-sealed-holdout-manifest-v2",
}
TFLITE_BACKEND = "tensorflow-lite"
UPSTREAM_FILES = {
    "config.json",
    "pytorch_model.bin",
    "tokenizer_config.json",
    "vocab.txt",
}
OUTPUT_FILES = {"evidence.json", "MODEL_CARD.md"}
RELEASE_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
REPOSITORY_ID = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
VERIFICATION_METHOD = re.compile(r"^[a-z0-9][a-z0-9._+-]{2,127}$")
SPDX_LICENSE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.+-]{1,63}$")
WINDOWS_PATH = re.compile(r"^[A-Za-z]:[\\/]")
EMBEDDED_PATH = re.compile(
    r"(?:^|[\s`'\"(])(?:/(?:[^/\s]+/)+|[A-Za-z]:[\\/]|\\\\|~/)"
)


class EvidenceError(ValueError):
    """Raised when release inputs cannot form safe checked-in evidence."""


def _load(path: Path, context: str) -> tuple[dict[str, Any], Path]:
    path = path.expanduser()
    if path.is_symlink() or not path.is_file():
        raise EvidenceError(f"{context}: must be a non-symlink regular file")
    path = path.resolve(strict=True)

    def strict(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, child in pairs:
            if key in value:
                raise EvidenceError(f"{context}: duplicate JSON key {key!r}")
            value[key] = child
        return value

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=strict,
            parse_constant=lambda item: (_ for _ in ()).throw(
                EvidenceError(f"{context}: invalid number {item}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{context}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise EvidenceError(f"{context}: must contain one JSON object")
    return value, path


def _mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{context}: must be an object")
    return value


def _require_exact_fields(
    value: Mapping[str, Any],
    expected: set[str],
    context: str,
) -> None:
    actual = set(value)
    if actual != expected:
        raise EvidenceError(
            f"{context}: fields mismatch; "
            f"missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )


def _sha(value: Any, context: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise EvidenceError(f"{context}: invalid SHA-256")
    return value


def _text(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip() or value != value.strip():
        raise EvidenceError(f"{context}: invalid nonempty string")
    return value


def _count(value: Any, context: str, *, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or value < minimum
    ):
        raise EvidenceError(f"{context}: invalid integer")
    return value


def _number(
    value: Any,
    context: str,
    *,
    minimum: float = 0.0,
    maximum: float | None = 1.0,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{context}: invalid number")
    value = float(value)
    if (
        not math.isfinite(value)
        or value < minimum
        or (maximum is not None and value > maximum)
    ):
        raise EvidenceError(f"{context}: number is outside its valid range")
    return value


def _threshold(value: Any, context: str) -> float:
    value = _number(value, context)
    if struct.unpack(">f", struct.pack(">f", value))[0] != value:
        raise EvidenceError(f"{context}: must be exactly float32")
    if value < MINIMUM_CONFIDENCE_THRESHOLD:
        raise EvidenceError(f"{context}: below the release threshold floor")
    return value


def _thresholds(
    value: Mapping[str, Any],
    context: str,
) -> RuntimeThresholds:
    general = _threshold(
        value.get("general_threshold"),
        f"{context} general threshold",
    )
    marketing = _threshold(
        value.get("marketing_threshold"),
        f"{context} MARKETING threshold",
    )
    if marketing < general:
        raise EvidenceError(
            f"{context} MARKETING threshold is below the general threshold"
        )
    return RuntimeThresholds(
        general_threshold=general,
        marketing_threshold=marketing,
    )


def _is_local_path(value: str) -> bool:
    return (
        Path(value).is_absolute()
        or value.startswith(("~/", "~\\", "\\\\", "file://"))
        or WINDOWS_PATH.match(value) is not None
        or EMBEDDED_PATH.search(value) is not None
        or any(item in value for item in ("/Users/", "/Volumes/", "/home/"))
    )


def _assert_content_free(value: Any) -> None:
    forbidden = {
        "body",
        "examples",
        "notification",
        "pair_id",
        "prompt",
        "reasoning",
        "title",
    }
    if isinstance(value, dict):
        for key, child in value.items():
            if key in forbidden:
                raise EvidenceError(f"forbidden evidence field: {key}")
            _assert_content_free(child)
    elif isinstance(value, list):
        for child in value:
            _assert_content_free(child)
    elif isinstance(value, str) and _is_local_path(value):
        raise EvidenceError("absolute paths are forbidden in evidence")


def _upstream(
    value: Mapping[str, Any],
    path: Path,
    training: Mapping[str, Any],
    vocab_sha256: str,
) -> dict[str, Any]:
    expected = {
        "schema_version",
        "repository_id",
        "revision",
        "license",
        "evidence_urls",
        "verification_method",
        "files",
    }
    if set(value) != expected or value.get(
        "schema_version"
    ) != UPSTREAM_SCHEMA_VERSION:
        raise EvidenceError("upstream provenance contract mismatch")
    repository = _text(value.get("repository_id"), "upstream repository")
    revision = _text(value.get("revision"), "upstream revision")
    license_name = _text(value.get("license"), "upstream license")
    method = _text(value.get("verification_method"), "verification method")
    if _is_local_path(license_name) or _is_local_path(method):
        raise EvidenceError("upstream metadata must not contain a path")
    if (
        REPOSITORY_ID.fullmatch(repository) is None
        or REVISION.fullmatch(revision) is None
        or SPDX_LICENSE.fullmatch(license_name) is None
        or VERIFICATION_METHOD.fullmatch(method) is None
    ):
        raise EvidenceError("invalid upstream source metadata")

    urls = _mapping(value.get("evidence_urls"), "upstream evidence URLs")
    if set(urls) != {"model_revision", "license"}:
        raise EvidenceError("upstream evidence URL fields mismatch")
    normalized_urls: dict[str, str] = {}
    for name in ("model_revision", "license"):
        url = _text(urls.get(name), f"upstream {name} URL")
        parsed = urlsplit(url)
        if (
            parsed.scheme != "https"
            or not parsed.netloc
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or _is_local_path(url)
        ):
            raise EvidenceError("upstream evidence URLs must be plain HTTPS")
        normalized_urls[name] = url
    if revision not in normalized_urls["model_revision"]:
        raise EvidenceError("model-revision URL does not bind the revision")

    files = _mapping(value.get("files"), "upstream files")
    if set(files) != UPSTREAM_FILES:
        raise EvidenceError("upstream file set mismatch")
    normalized_files: dict[str, dict[str, Any]] = {}
    base_value = _mapping(training.get("inputs"), "training inputs").get(
        "base_model"
    )
    base_dir = Path(_text(base_value, "training base model")).expanduser()
    if base_dir.is_symlink() or not base_dir.is_dir():
        raise EvidenceError("training base model is not a regular directory")
    for filename in sorted(UPSTREAM_FILES):
        entry = _mapping(files[filename], f"upstream {filename}")
        if set(entry) != {"sha256", "size_bytes"}:
            raise EvidenceError(f"upstream {filename} fields mismatch")
        digest = _sha(entry.get("sha256"), f"upstream {filename}")
        size = _count(entry.get("size_bytes"), f"upstream {filename} size")
        local = base_dir / filename
        if (
            local.is_symlink()
            or not local.is_file()
            or sha256_file(local) != digest
            or local.stat().st_size != size
        ):
            raise EvidenceError(f"upstream {filename} does not match training")
        normalized_files[filename] = {"sha256": digest, "size_bytes": size}
    if normalized_files["vocab.txt"]["sha256"] != vocab_sha256:
        raise EvidenceError("upstream vocabulary does not match conversion")
    return {
        "schema_version": UPSTREAM_SCHEMA_VERSION,
        "repository_id": repository,
        "revision": revision,
        "license": license_name,
        "evidence_urls": normalized_urls,
        "verification_method": method,
        "files": normalized_files,
        "provenance_sha256": sha256_file(path),
    }


def _conversion(
    value: Mapping[str, Any],
    path: Path,
    training_sha256: str,
) -> dict[str, Any]:
    if (
        value.get("schema_version") != CONVERSION_SCHEMA_VERSION
        or value.get("labels") != list(LABELS)
    ):
        raise EvidenceError("conversion contract mismatch")
    source = _mapping(value.get("source"), "conversion source")
    artifact = _mapping(value.get("artifact"), "conversion artifact")
    if _sha(
        source.get("training_manifest_sha256"),
        "conversion training manifest",
    ) != training_sha256:
        raise EvidenceError("conversion does not bind the training manifest")
    size = _count(artifact.get("size_bytes"), "conversion model size")
    maximum = _count(artifact.get("max_size_bytes"), "conversion model cap")
    if size > maximum:
        raise EvidenceError("converted model exceeds its recorded cap")
    tensor = _mapping(value.get("tensor_contract"), "tensor contract")
    inputs = tensor.get("inputs")
    if (
        not isinstance(inputs, list)
        or not inputs
        or not isinstance(inputs[0], dict)
        or inputs[0].get("shape") is None
        or len(inputs[0]["shape"]) != 2
    ):
        raise EvidenceError("conversion input contract is malformed")
    sequence_length = _count(
        inputs[0]["shape"][1],
        "conversion sequence length",
    )
    quantization = dict(
        _mapping(value.get("quantization"), "conversion quantization")
    )
    if quantization.get("applied") != "dynamic-int8":
        raise EvidenceError("release conversion must apply dynamic-int8")
    audit = dict(
        _mapping(
            value.get("quantization_audit"),
            "conversion quantization audit",
        )
    )
    expected_audit_fields = {
        "schema_version",
        "method",
        "tensor_count",
        "int8_tensor_count",
        "operator_count",
        "quantize_operator_count",
        "passed",
    }
    if (
        set(audit) != expected_audit_fields
        or audit.get("schema_version") != QUANTIZATION_AUDIT_SCHEMA
        or audit.get("method")
        != "litert-interpreter-tensor-and-operator-inspection"
        or audit.get("passed") is not True
    ):
        raise EvidenceError("conversion quantization audit did not pass")
    tensor_count = _count(
        audit.get("tensor_count"),
        "quantization-audit tensor count",
    )
    int8_tensor_count = _count(
        audit.get("int8_tensor_count"),
        "quantization-audit INT8 tensor count",
    )
    operator_count = _count(
        audit.get("operator_count"),
        "quantization-audit operator count",
    )
    quantize_operator_count = _count(
        audit.get("quantize_operator_count"),
        "quantization-audit QUANTIZE operator count",
    )
    if (
        int8_tensor_count > tensor_count
        or quantize_operator_count > operator_count
    ):
        raise EvidenceError("conversion quantization audit counts contradict")
    return {
        "manifest_sha256": sha256_file(path),
        "model_bundle_sha256": _sha(
            source.get("model_bundle_sha256"),
            "conversion model bundle",
        ),
        "vocab_sha256": _sha(
            source.get("vocab_sha256"),
            "conversion vocabulary",
        ),
        "model_sha256": _sha(artifact.get("sha256"), "conversion model"),
        "model_size_bytes": size,
        "model_max_size_bytes": maximum,
        "sequence_length": sequence_length,
        "quantization": quantization,
        "quantization_audit": audit,
        "tensor_contract": dict(tensor),
    }


def _selected_checkpoint(
    model_dir: Path,
    training: Mapping[str, Any],
    expected_bundle_sha256: str,
) -> dict[str, Any]:
    directory = model_dir.expanduser()
    if directory.is_symlink() or not directory.is_dir():
        raise EvidenceError("selected model must be a non-symlink directory")
    directory = directory.resolve(strict=True)
    try:
        file_hashes, bundle_sha256 = model_bundle_hashes(directory)
    except (ContractError, OSError) as error:
        raise EvidenceError(f"selected model bundle is invalid: {error}") from error
    if bundle_sha256 != expected_bundle_sha256:
        raise EvidenceError(
            "selected model bundle does not match conversion provenance"
        )

    checkpoint_path = directory / "checkpoint.json"
    checkpoint_value, _ = _load(
        checkpoint_path,
        "selected checkpoint metadata",
    )
    if set(checkpoint_value) != {"epoch", "reason", "metrics"}:
        raise EvidenceError("selected checkpoint metadata fields mismatch")
    epoch = _count(checkpoint_value.get("epoch"), "selected checkpoint epoch")
    reason = _text(
        checkpoint_value.get("reason"),
        "selected checkpoint reason",
    )
    if reason not in {"best-validation-loss", "epoch-complete"}:
        raise EvidenceError("selected checkpoint reason is not releasable")
    metrics = _mapping(
        checkpoint_value.get("metrics"),
        "selected checkpoint metrics",
    )
    expected_metric_fields = {
        "epoch",
        "train_loss",
        "validation_accuracy",
        "validation_loss",
    }
    if set(metrics) != expected_metric_fields:
        raise EvidenceError("selected checkpoint metric fields mismatch")
    normalized_metrics = {
        "epoch": _count(metrics.get("epoch"), "selected metric epoch"),
        "train_loss": _number(
            metrics.get("train_loss"),
            "selected train loss",
            maximum=None,
        ),
        "validation_accuracy": _number(
            metrics.get("validation_accuracy"),
            "selected validation accuracy",
        ),
        "validation_loss": _number(
            metrics.get("validation_loss"),
            "selected validation loss",
            maximum=None,
        ),
    }
    if normalized_metrics["epoch"] != epoch:
        raise EvidenceError("selected checkpoint epoch and metrics differ")

    epochs = training.get("epochs")
    if not isinstance(epochs, list):
        raise EvidenceError("training epochs must be an array")
    matching_epochs = [
        value
        for value in epochs
        if isinstance(value, dict) and value.get("epoch") == epoch
    ]
    if len(matching_epochs) != 1:
        raise EvidenceError(
            "selected checkpoint epoch is not unique in training manifest"
        )
    manifest_metrics = matching_epochs[0]
    if set(manifest_metrics) != expected_metric_fields:
        raise EvidenceError("training epoch metric fields mismatch")
    normalized_manifest_metrics = {
        "epoch": _count(
            manifest_metrics.get("epoch"),
            "training metric epoch",
        ),
        "train_loss": _number(
            manifest_metrics.get("train_loss"),
            "training train loss",
            maximum=None,
        ),
        "validation_accuracy": _number(
            manifest_metrics.get("validation_accuracy"),
            "training validation accuracy",
        ),
        "validation_loss": _number(
            manifest_metrics.get("validation_loss"),
            "training validation loss",
            maximum=None,
        ),
    }
    if normalized_metrics != normalized_manifest_metrics:
        raise EvidenceError(
            "selected checkpoint metrics differ from training manifest"
        )

    best = _mapping(training.get("best"), "training best")
    best_epoch = _count(best.get("epoch"), "best epoch")
    best_validation_accuracy = _number(
        best.get("validation_accuracy"),
        "best validation accuracy",
    )
    best_validation_loss = _number(
        best.get("validation_loss"),
        "best validation loss",
        maximum=None,
    )
    best_epoch_metrics = [
        value
        for value in epochs
        if isinstance(value, dict) and value.get("epoch") == best_epoch
    ]
    if len(best_epoch_metrics) != 1:
        raise EvidenceError(
            "training best epoch is not unique in training manifest"
        )
    if (
        best_epoch_metrics[0].get("validation_accuracy")
        != best_validation_accuracy
        or best_epoch_metrics[0].get("validation_loss")
        != best_validation_loss
    ):
        raise EvidenceError(
            "training best metrics differ from its epoch metrics"
        )
    if reason == "best-validation-loss" and epoch != best_epoch:
        raise EvidenceError(
            "best checkpoint does not match training manifest best epoch"
        )
    return {
        "bundle_sha256": bundle_sha256,
        "metadata_sha256": file_hashes["checkpoint.json"],
        "epoch": epoch,
        "reason": reason,
        "is_best_epoch": epoch == best_epoch,
        "train_loss": normalized_metrics["train_loss"],
        "validation_accuracy": normalized_metrics[
            "validation_accuracy"
        ],
        "validation_loss": normalized_metrics["validation_loss"],
    }


def _provenance(
    value: Any,
    context: str,
    model_sha256: str,
    vocab_sha256: str,
) -> str:
    value = _mapping(value, f"{context} provenance")
    if (
        value.get("schema_version") != PROVENANCE_SCHEMA_VERSION
        or value.get("backend") != TFLITE_BACKEND
        or _sha(value.get("model_artifact_sha256"), f"{context} model")
        != model_sha256
        or _sha(value.get("vocab_sha256"), f"{context} vocabulary")
        != vocab_sha256
    ):
        raise EvidenceError(f"{context} provenance mismatch")
    return _sha(value.get("source_manifest_sha256"), f"{context} source")


def _passed(value: Any, context: str) -> None:
    if not isinstance(value, dict) or value.get("passed") is not True:
        raise EvidenceError(f"{context} gate did not pass")


def _locale_f1(raw: Mapping[str, Any], context: str) -> dict[str, float]:
    locales = _mapping(raw.get("by_locale"), f"{context} locales")
    if set(locales) != {"ko", "en", "mixed"}:
        raise EvidenceError(f"{context} locale coverage mismatch")
    return {
        locale: _number(
            _mapping(locales[locale], f"{context} {locale}").get("macro_f1"),
            f"{context} {locale} macro-F1",
        )
        for locale in ("ko", "en", "mixed")
    }


def _gate_summary(
    value: Mapping[str, Any],
    path: Path,
    context: str,
    model_sha256: str,
    vocab_sha256: str,
    selected_thresholds: RuntimeThresholds,
) -> dict[str, Any]:
    _require_exact_fields(
        value,
        {
            "schema_version",
            "general_threshold",
            "marketing_threshold",
            "evaluation",
            "gate",
            "provenance",
        },
        context,
    )
    if value.get("schema_version") != GATE_SCHEMA_VERSION:
        raise EvidenceError(f"{context} schema mismatch")
    gate_thresholds = _thresholds(value, f"{context} gate")
    if gate_thresholds != selected_thresholds:
        raise EvidenceError(
            f"{context} gate thresholds differ from selection"
        )
    _passed(value.get("gate"), context)
    source_sha256 = _provenance(
        value.get("provenance"),
        context,
        model_sha256,
        vocab_sha256,
    )
    evaluation = _mapping(value.get("evaluation"), f"{context} evaluation")
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
        f"{context} evaluation",
    )
    if (
        evaluation.get("schema_version") != EVALUATION_SCHEMA_VERSION
        or evaluation.get("labels") != list(LABELS)
    ):
        raise EvidenceError(f"{context} evaluation contract mismatch")
    evaluation_thresholds = _thresholds(
        evaluation,
        f"{context} evaluation",
    )
    if evaluation_thresholds != gate_thresholds:
        raise EvidenceError(
            f"{context} evaluation thresholds differ from gate"
        )
    raw = _mapping(evaluation.get("raw"), f"{context} raw")
    raw_overall = _mapping(raw.get("overall"), f"{context} raw overall")
    runtime = _mapping(evaluation.get("runtime"), f"{context} runtime")
    runtime_overall = _mapping(
        runtime.get("overall"),
        f"{context} runtime overall",
    )
    coverage = _mapping(
        _mapping(
            evaluation.get("trusted_coverage"),
            f"{context} coverage",
        ).get("overall"),
        f"{context} overall coverage",
    )
    safety = _mapping(evaluation.get("safety"), f"{context} safety")
    return {
        "gate_sha256": sha256_file(path),
        "source_manifest_sha256": source_sha256,
        "general_threshold": gate_thresholds.general_threshold,
        "marketing_threshold": gate_thresholds.marketing_threshold,
        "row_count": _count(evaluation.get("row_count"), f"{context} rows"),
        "raw": {
            "accuracy": _number(
                raw_overall.get("accuracy"),
                f"{context} raw accuracy",
            ),
            "macro_f1": _number(
                raw_overall.get("macro_f1"),
                f"{context} raw macro-F1",
            ),
            "marketing_precision": _number(
                raw_overall.get("marketing_precision"),
                f"{context} MARKETING precision",
            ),
            "locale_macro_f1": _locale_f1(raw, context),
        },
        "runtime": {
            "accuracy": _number(
                runtime_overall.get("accuracy"),
                f"{context} runtime accuracy",
            ),
            "macro_f1": _number(
                runtime_overall.get("macro_f1"),
                f"{context} runtime macro-F1",
            ),
            "marketing_precision": _number(
                runtime_overall.get("marketing_precision"),
                f"{context} runtime MARKETING precision",
            ),
        },
        "trusted_coverage": _number(
            coverage.get("rate"),
            f"{context} trusted coverage",
        ),
        "trusted_marketing_false_positives": _count(
            safety.get("trusted_non_marketing_predicted_marketing_count"),
            f"{context} safety count",
            allow_zero=True,
        ),
    }


def _parity_summary(
    value: Mapping[str, Any],
    path: Path,
    *,
    conversion: Mapping[str, Any],
    source_sha256: str,
    thresholds: RuntimeThresholds,
    test: Mapping[str, Any],
) -> dict[str, Any]:
    _require_exact_fields(
        value,
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
        "parity report",
    )
    if (
        value.get("schema_version") != PARITY_SCHEMA_VERSION
        or value.get("labels") != list(LABELS)
        or value.get("split") != "test"
        or _thresholds(value, "parity") != thresholds
        or _count(value.get("row_count"), "parity rows") != test["row_count"]
    ):
        raise EvidenceError("parity contract mismatch")
    provenance = _mapping(value.get("provenance"), "parity provenance")
    expected = {
        "source_manifest_sha256": source_sha256,
        "conversion_manifest_sha256": conversion["manifest_sha256"],
        "pytorch_model_bundle_sha256": conversion["model_bundle_sha256"],
        "tflite_model_sha256": conversion["model_sha256"],
        "vocab_sha256": conversion["vocab_sha256"],
    }
    if any(
        _sha(provenance.get(key), f"parity {key}") != digest
        for key, digest in expected.items()
    ):
        raise EvidenceError("parity provenance mismatch")

    quality = _mapping(value.get("quality"), "parity quality")
    tflite = _mapping(quality.get("tflite"), "parity TFLite quality")
    tflite_raw = _mapping(tflite.get("raw"), "parity TFLite raw")
    comparisons = (
        (
            _number(tflite_raw.get("macro_f1"), "parity macro-F1"),
            test["raw"]["macro_f1"],
        ),
        (
            _number(
                tflite_raw.get("marketing_precision"),
                "parity MARKETING precision",
            ),
            test["raw"]["marketing_precision"],
        ),
        (
            _number(tflite.get("trusted_coverage"), "parity coverage"),
            test["trusted_coverage"],
        ),
    )
    if any(
        not math.isclose(first, second, rel_tol=0.0, abs_tol=1e-12)
        for first, second in comparisons
    ):
        raise EvidenceError("parity metrics do not match the test gate")
    if _count(
        tflite.get("trusted_marketing_false_positives"),
        "parity safety count",
        allow_zero=True,
    ) != test["trusted_marketing_false_positives"]:
        raise EvidenceError("parity safety does not match the test gate")

    agreement = _mapping(value.get("agreement"), "parity agreement")

    def agreement_item(name: str) -> dict[str, Any]:
        item = _mapping(agreement.get(name), f"parity {name}")
        count = _count(item.get("count"), f"parity {name} count", allow_zero=True)
        rows = _count(item.get("row_count"), f"parity {name} rows")
        rate = _number(item.get("rate"), f"parity {name} rate")
        if rows != test["row_count"] or count > rows or not math.isclose(
            rate,
            count / rows,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise EvidenceError(f"parity {name} counts mismatch")
        return {"count": count, "row_count": rows, "rate": rate}

    trusted = _mapping(
        agreement.get("trusted_marketing"),
        "parity trusted MARKETING",
    )
    trusted_summary = {
        key: _count(
            trusted.get(key),
            f"parity {key}",
            allow_zero=True,
        )
        for key in (
            "pytorch_count",
            "tflite_count",
            "disagreement_count",
            "introduced_by_tflite_count",
            "introduced_unsafe_by_tflite_count",
        )
    }
    errors = _mapping(value.get("probability_error"), "parity errors")
    if errors.get("percentile_method") != "nearest-rank":
        raise EvidenceError("parity percentile method mismatch")

    def error_item(name: str) -> dict[str, float]:
        item = _mapping(errors.get(name), f"parity {name}")
        result = {
            key: _number(item.get(key), f"parity {name} {key}")
            for key in ("mean", "p95", "maximum")
        }
        if not result["mean"] <= result["p95"] <= result["maximum"]:
            raise EvidenceError(f"parity {name} error ordering mismatch")
        return result

    delta = _mapping(
        quality.get("tflite_minus_pytorch"),
        "parity quality delta",
    )
    raw_delta = _mapping(delta.get("raw"), "parity raw delta")
    runtime_delta = _mapping(delta.get("runtime"), "parity runtime delta")
    locale_delta = _mapping(
        raw_delta.get("locale_macro_f1"),
        "parity locale delta",
    )
    safety_delta = delta.get("trusted_marketing_false_positives")
    if isinstance(safety_delta, bool) or not isinstance(safety_delta, int):
        raise EvidenceError("parity safety delta must be an integer")
    normalized_delta = {
        "raw": {
            key: _number(
                raw_delta.get(key),
                f"parity raw {key} delta",
                minimum=-1.0,
            )
            for key in ("accuracy", "macro_f1", "marketing_precision")
        },
        "runtime": {
            key: _number(
                runtime_delta.get(key),
                f"parity runtime {key} delta",
                minimum=-1.0,
            )
            for key in ("accuracy", "macro_f1", "marketing_precision")
        },
        "trusted_coverage": _number(
            delta.get("trusted_coverage"),
            "parity coverage delta",
            minimum=-1.0,
        ),
        "trusted_marketing_false_positives": safety_delta,
    }
    normalized_delta["raw"]["locale_macro_f1"] = {
        locale: _number(
            locale_delta.get(locale),
            f"parity {locale} delta",
            minimum=-1.0,
        )
        for locale in ("ko", "en", "mixed")
    }
    return {
        "report_sha256": sha256_file(path),
        "row_count": test["row_count"],
        "agreement": {
            "raw_argmax": agreement_item("raw_argmax"),
            "runtime_output": agreement_item("runtime_output"),
            "trusted_marketing": trusted_summary,
        },
        "probability_error": {
            "percentile_method": "nearest-rank",
            "absolute_per_label": error_item("absolute_per_label"),
            "confidence": error_item("confidence"),
            "row_total_variation": error_item("row_total_variation"),
        },
        "tflite_minus_pytorch": normalized_delta,
    }


def _assets(
    directory: Path,
    conversion: Mapping[str, Any],
    threshold_sha256: str,
    test_sha256: str,
    parity_sha256: str,
    holdout_sha256: str,
    thresholds: RuntimeThresholds,
) -> dict[str, Any]:
    if directory.is_symlink() or not directory.is_dir():
        raise EvidenceError("assets directory is invalid")
    manifest, manifest_path = _load(
        directory / MODEL_MANIFEST_FILENAME,
        "asset manifest",
    )
    _require_exact_fields(
        manifest,
        {
            "schema_version",
            "files",
            "labels",
            "max_sequence_length",
            "general_threshold",
            "marketing_threshold",
            "tokenizer",
            "conversion",
            "evidence",
            "evaluation_provenance",
        },
        "asset manifest",
    )
    if (
        manifest.get("schema_version") != OUTPUT_MANIFEST_SCHEMA
        or manifest.get("labels") != list(LABELS)
        or _thresholds(manifest, "asset") != thresholds
        or manifest.get("max_sequence_length") != conversion["sequence_length"]
    ):
        raise EvidenceError("asset contract mismatch")
    files = _mapping(manifest.get("files"), "asset files")
    expected_names = {
        MODEL_ASSET_FILENAME,
        VOCAB_ASSET_FILENAME,
        LABELS_ASSET_FILENAME,
    }
    if set(files) != expected_names:
        raise EvidenceError("asset file set mismatch")
    normalized: dict[str, dict[str, Any]] = {}
    for name in sorted(expected_names):
        path = directory / name
        entry = _mapping(files[name], f"asset {name}")
        if path.is_symlink() or not path.is_file():
            raise EvidenceError(f"asset {name} is invalid")
        digest, size = sha256_file(path), path.stat().st_size
        if (
            _sha(entry.get("sha256"), f"asset {name}") != digest
            or _count(entry.get("size_bytes"), f"asset {name} size") != size
        ):
            raise EvidenceError(f"asset {name} hash or size mismatch")
        normalized[name] = {"sha256": digest, "size_bytes": size}
    if (
        normalized[MODEL_ASSET_FILENAME]["sha256"]
        != conversion["model_sha256"]
        or normalized[VOCAB_ASSET_FILENAME]["sha256"]
        != conversion["vocab_sha256"]
        or (directory / LABELS_ASSET_FILENAME).read_text(encoding="utf-8")
        != "\n".join(LABELS) + "\n"
    ):
        raise EvidenceError("packaged model contract does not match conversion")
    asset_conversion = _mapping(manifest.get("conversion"), "asset conversion")
    if (
        asset_conversion.get("quantization") != conversion["quantization"]
        or asset_conversion.get("quantization_audit")
        != conversion["quantization_audit"]
        or asset_conversion.get("tensor_contract")
        != conversion["tensor_contract"]
    ):
        raise EvidenceError("asset conversion metadata mismatch")
    evidence = _mapping(manifest.get("evidence"), "asset evidence")
    expected_hashes = {
        "conversion_manifest_sha256": conversion["manifest_sha256"],
        "threshold_selection_sha256": threshold_sha256,
        "development_test_gate_sha256": test_sha256,
        "test_parity_report_sha256": parity_sha256,
        "sealed_holdout_gate_sha256": holdout_sha256,
    }
    if set(evidence) != set(expected_hashes) or any(
        _sha(evidence.get(key), f"asset evidence {key}") != digest
        for key, digest in expected_hashes.items()
    ):
        raise EvidenceError("asset evidence hashes mismatch")
    return {"files": normalized, "manifest_sha256": sha256_file(manifest_path)}


def _model_card(evidence: Mapping[str, Any]) -> str:
    test = evidence["development_test"]
    holdout = evidence["sealed_holdout"]["evaluation"]
    parity = evidence["parity"]
    model_mib = evidence["conversion"]["model_size_bytes"] / (1024**2)
    return f"""# AlarmControl semantic model card: {evidence['release_id']}

## Status

This is a model-candidate evidence package. Physical-device latency, thermal,
battery, and OOM acceptance remain a separate release requirement.

## Contract

- Labels: {', '.join(LABELS)}
- Input: {evidence['contract']['max_sequence_length']} WordPiece tokens
- General threshold: {evidence['contract']['general_threshold']}
- MARKETING threshold: {evidence['contract']['marketing_threshold']}
- Quantization: dynamic INT8 weights with float32 logits
- Model: {evidence['conversion']['model_size_bytes']} bytes ({model_mib:.2f} MiB)
- Runtime: bundled, non-generative, and on-device only

## Aggregate evaluation

| Dataset | Rows | Raw macro-F1 | MARKETING precision | Trusted coverage | Trusted MARKETING false positives |
|---|---:|---:|---:|---:|---:|
| Development test | {test['row_count']} | {test['raw']['macro_f1']:.6f} | {test['raw']['marketing_precision']:.6f} | {test['trusted_coverage']:.6f} | {test['trusted_marketing_false_positives']} |
| Sealed synthetic holdout | {holdout['row_count']} | {holdout['raw']['macro_f1']:.6f} | {holdout['raw']['marketing_precision']:.6f} | {holdout['trusted_coverage']:.6f} | {holdout['trusted_marketing_false_positives']} |

## Conversion parity

- Raw argmax agreement: {parity['agreement']['raw_argmax']['rate']:.6f}
- Runtime-output agreement: {parity['agreement']['runtime_output']['rate']:.6f}
- TFLite minus PyTorch raw macro-F1: {parity['tflite_minus_pytorch']['raw']['macro_f1']:.6f}
- Aggregate counts only; no examples or row identifiers are included.

## Provenance

- Upstream model: {evidence['upstream']['repository_id']}
- Revision: {evidence['upstream']['revision']}
- License: {evidence['upstream']['license']}
- Selected checkpoint epoch: {evidence['training']['selected_epoch']}
- Training best epoch: {evidence['training']['best_epoch']}
- Selected checkpoint reason: {evidence['training']['selected_checkpoint_reason']}
- Revision evidence: {evidence['upstream']['evidence_urls']['model_revision']}
- License evidence: {evidence['upstream']['evidence_urls']['license']}
- Trained bundle SHA-256: {evidence['provenance']['pytorch_model_bundle_sha256']}
- Checkpoint metadata SHA-256: {evidence['training']['selected_checkpoint_metadata_sha256']}
- TFLite model SHA-256: {evidence['provenance']['tflite_model_sha256']}
- Vocabulary SHA-256: {evidence['provenance']['vocab_sha256']}
- Dataset manifest SHA-256: {evidence['provenance']['dataset_source_manifest_sha256']}
- Holdout manifest SHA-256: {evidence['provenance']['sealed_source_manifest_sha256']}

## Privacy and limitations

- Inputs are fictional synthetic data; evidence contains aggregate metrics only.
- It contains no notification text, identifiers, package/channel metadata,
  reasoning, timestamps, or absolute filesystem paths.
- Low-confidence and AMBIGUOUS results fail open in the rule-first path.
- Coverage is limited to Korean, English, and mixed-language synthetic patterns.
"""


def build_release_evidence(
    *,
    release_id: str,
    upstream_provenance: Path,
    training_manifest: Path,
    model_dir: Path,
    conversion_manifest: Path,
    threshold_selection: Path,
    test_gate: Path,
    sealed_holdout_manifest: Path,
    sealed_holdout_gate: Path,
    parity_report: Path,
    assets_dir: Path,
) -> tuple[dict[str, Any], str]:
    """Validate the hash chain without reading any raw holdout rows."""

    if RELEASE_ID.fullmatch(release_id) is None:
        raise EvidenceError("release ID must be a lowercase path-free slug")
    training_value, training_path = _load(
        training_manifest,
        "training manifest",
    )
    conversion_value, conversion_path = _load(
        conversion_manifest,
        "conversion manifest",
    )
    conversion = _conversion(
        conversion_value,
        conversion_path,
        sha256_file(training_path),
    )
    if (
        training_value.get("schema_version") != TRAINING_SCHEMA_VERSION
        or training_value.get("status") != "completed"
        or training_value.get("labels") != list(LABELS)
    ):
        raise EvidenceError("training contract mismatch")
    training_text_format = _mapping(
        training_value.get("text_format"),
        "training text format",
    )
    if training_text_format.get("version") != RUNTIME_TEXT_FORMAT_VERSION:
        raise EvidenceError("training text-format contract mismatch")
    training_inputs = _mapping(training_value.get("inputs"), "training inputs")
    if _sha(
        training_inputs.get("base_vocab_sha256"),
        "training base vocabulary",
    ) != conversion["vocab_sha256"]:
        raise EvidenceError("training vocabulary does not match conversion")
    rows = _mapping(training_inputs.get("rows_by_split"), "training rows")
    best = _mapping(training_value.get("best"), "training best")
    selected = _selected_checkpoint(
        model_dir,
        training_value,
        conversion["model_bundle_sha256"],
    )
    training = {
        "manifest_sha256": sha256_file(training_path),
        "dataset_sha256": _sha(
            training_inputs.get("dataset_sha256"),
            "training dataset",
        ),
        "train_rows": _count(rows.get("train"), "training rows"),
        "validation_rows": _count(
            rows.get("validation"),
            "validation rows",
        ),
        "best_epoch": _count(best.get("epoch"), "best epoch"),
        "selected_epoch": selected["epoch"],
        "selected_checkpoint_reason": selected["reason"],
        "selected_is_best_epoch": selected["is_best_epoch"],
        "selected_checkpoint_metadata_sha256": selected[
            "metadata_sha256"
        ],
        "selected_train_loss": selected["train_loss"],
        "selected_validation_accuracy": selected[
            "validation_accuracy"
        ],
        "selected_validation_loss": selected["validation_loss"],
        "best_validation_accuracy": _number(
            best.get("validation_accuracy"),
            "best validation accuracy",
        ),
        "best_validation_loss": _number(
            best.get("validation_loss"),
            "best validation loss",
            maximum=None,
        ),
        "model_bundle_sha256": conversion["model_bundle_sha256"],
    }

    upstream_value, upstream_path = _load(
        upstream_provenance,
        "upstream provenance",
    )
    upstream = _upstream(
        upstream_value,
        upstream_path,
        training_value,
        conversion["vocab_sha256"],
    )
    threshold_value, threshold_path = _load(
        threshold_selection,
        "threshold selection",
    )
    _require_exact_fields(
        threshold_value,
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
    if (
        threshold_value.get("schema_version") != THRESHOLD_SCHEMA_VERSION
        or threshold_value.get("status") != "selected"
        or threshold_value.get("validation_only") is not True
    ):
        raise EvidenceError("threshold-selection contract mismatch")
    _passed(threshold_value.get("gate"), "threshold selection")
    dataset_source_sha256 = _provenance(
        threshold_value.get("provenance"),
        "threshold selection",
        conversion["model_sha256"],
        conversion["vocab_sha256"],
    )
    selected_thresholds = _thresholds(
        threshold_value,
        "selected",
    )
    coverage = _mapping(
        _mapping(
            threshold_value.get("trusted_coverage"),
            "threshold coverage",
        ).get("overall"),
        "threshold overall coverage",
    )
    threshold_summary = {
        "selection_sha256": sha256_file(threshold_path),
        "source_manifest_sha256": dataset_source_sha256,
        "general_threshold": selected_thresholds.general_threshold,
        "marketing_threshold": selected_thresholds.marketing_threshold,
        "trusted_coverage": _number(
            coverage.get("rate"),
            "threshold coverage",
        ),
    }

    test_value, test_path = _load(test_gate, "development test gate")
    test = _gate_summary(
        test_value,
        test_path,
        "development test",
        conversion["model_sha256"],
        conversion["vocab_sha256"],
        selected_thresholds,
    )
    if (
        test["general_threshold"]
        != selected_thresholds.general_threshold
        or test["marketing_threshold"]
        != selected_thresholds.marketing_threshold
        or test["source_manifest_sha256"] != dataset_source_sha256
    ):
        raise EvidenceError("test and threshold provenance differ")
    holdout_value, holdout_path = _load(
        sealed_holdout_gate,
        "sealed holdout gate",
    )
    holdout_evaluation = _gate_summary(
        holdout_value,
        holdout_path,
        "sealed holdout",
        conversion["model_sha256"],
        conversion["vocab_sha256"],
        selected_thresholds,
    )
    if (
        holdout_evaluation["general_threshold"]
        != selected_thresholds.general_threshold
        or holdout_evaluation["marketing_threshold"]
        != selected_thresholds.marketing_threshold
    ):
        raise EvidenceError("holdout thresholds differ from deployment")
    holdout_manifest_value, holdout_manifest_path = _load(
        sealed_holdout_manifest,
        "sealed holdout manifest",
    )
    if (
        holdout_manifest_value.get("schema_version") not in HOLDOUT_SCHEMAS
        or sha256_file(holdout_manifest_path)
        != holdout_evaluation["source_manifest_sha256"]
    ):
        raise EvidenceError("holdout manifest provenance mismatch")
    holdout_counts = _mapping(
        holdout_manifest_value.get("counts"),
        "holdout counts",
    )
    holdout = {
        "manifest_sha256": sha256_file(holdout_manifest_path),
        "row_count": _count(holdout_counts.get("row_count"), "holdout rows"),
        "pair_count": _count(holdout_counts.get("pair_count"), "holdout pairs"),
        "evaluation": holdout_evaluation,
    }
    if holdout["row_count"] != holdout_evaluation["row_count"]:
        raise EvidenceError("holdout row counts differ")

    parity_value, parity_path = _load(parity_report, "parity report")
    parity = _parity_summary(
        parity_value,
        parity_path,
        conversion=conversion,
        source_sha256=dataset_source_sha256,
        thresholds=selected_thresholds,
        test=test,
    )
    assets = _assets(
        assets_dir,
        conversion,
        threshold_summary["selection_sha256"],
        test["gate_sha256"],
        parity["report_sha256"],
        holdout_evaluation["gate_sha256"],
        selected_thresholds,
    )
    evidence = {
        "schema_version": SCHEMA_VERSION,
        "release_id": release_id,
        "status": "model-candidate",
        "privacy": {
            "content_free": True,
            "synthetic_data_only": True,
            "contains_notification_text": False,
            "contains_row_identifiers": False,
            "contains_absolute_paths": False,
        },
        "upstream": upstream,
        "contract": {
            "labels": list(LABELS),
            "max_sequence_length": conversion["sequence_length"],
            "general_threshold": selected_thresholds.general_threshold,
            "marketing_threshold": selected_thresholds.marketing_threshold,
        },
        "artifacts": assets,
        "training": training,
        "conversion": {
            key: value
            for key, value in conversion.items()
            if key != "sequence_length"
        },
        "threshold_selection": threshold_summary,
        "development_test": test,
        "sealed_holdout": holdout,
        "parity": parity,
        "provenance": {
            "dataset_source_manifest_sha256": dataset_source_sha256,
            "sealed_source_manifest_sha256": holdout[
                "manifest_sha256"
            ],
            "pytorch_model_bundle_sha256": conversion[
                "model_bundle_sha256"
            ],
            "tflite_model_sha256": conversion["model_sha256"],
            "vocab_sha256": conversion["vocab_sha256"],
        },
    }
    _assert_content_free(evidence)
    card = _model_card(evidence)
    if _is_local_path(card):
        raise EvidenceError("generated model card contains a local path")
    return evidence, card


def write_release_evidence(
    output_dir: Path,
    evidence: Mapping[str, Any],
    model_card: str,
) -> None:
    """Atomically replace only an exact two-file evidence directory."""

    output = output_dir.expanduser()
    if output.is_symlink():
        raise EvidenceError("output directory must not be a symlink")
    if output.exists():
        entries = list(output.iterdir()) if output.is_dir() else []
        if (
            {entry.name for entry in entries} != OUTPUT_FILES
            or any(entry.is_symlink() or not entry.is_file() for entry in entries)
        ):
            raise EvidenceError("existing output is not an evidence directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
        )
    )
    backup = output.with_name(f".{output.name}.previous")
    try:
        (temporary / "evidence.json").write_text(
            json.dumps(
                evidence,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
                allow_nan=False,
            )
            + "\n",
            encoding="utf-8",
        )
        (temporary / "MODEL_CARD.md").write_text(
            model_card,
            encoding="utf-8",
        )
        if backup.exists():
            raise EvidenceError("stale evidence backup exists")
        if output.exists():
            os.replace(output, backup)
        os.replace(temporary, output)
        if backup.exists():
            shutil.rmtree(backup)
    except BaseException:
        if backup.exists() and not output.exists():
            os.replace(backup, output)
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--upstream-provenance", type=Path, required=True)
    parser.add_argument("--training-manifest", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--conversion-manifest", type=Path, required=True)
    parser.add_argument("--threshold-selection", type=Path, required=True)
    parser.add_argument("--test-gate", type=Path, required=True)
    parser.add_argument("--sealed-holdout-manifest", type=Path, required=True)
    parser.add_argument("--sealed-holdout-gate", type=Path, required=True)
    parser.add_argument("--parity-report", type=Path, required=True)
    parser.add_argument("--assets-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        evidence, card = build_release_evidence(
            release_id=arguments.release_id,
            upstream_provenance=arguments.upstream_provenance,
            training_manifest=arguments.training_manifest,
            model_dir=arguments.model_dir,
            conversion_manifest=arguments.conversion_manifest,
            threshold_selection=arguments.threshold_selection,
            test_gate=arguments.test_gate,
            sealed_holdout_manifest=arguments.sealed_holdout_manifest,
            sealed_holdout_gate=arguments.sealed_holdout_gate,
            parity_report=arguments.parity_report,
            assets_dir=arguments.assets_dir,
        )
        write_release_evidence(arguments.output_dir, evidence, card)
        print(
            json.dumps(
                {
                    "schema_version": SCHEMA_VERSION,
                    "release_id": evidence["release_id"],
                    "output_files": sorted(OUTPUT_FILES),
                },
                sort_keys=True,
            )
        )
        return 0
    except (EvidenceError, OSError, UnicodeError, ValueError, TypeError) as error:
        print(f"build_release_evidence: {error}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
