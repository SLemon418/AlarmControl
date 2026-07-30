#!/usr/bin/env python3
"""Evaluate one local KoELECTRA bundle and emit strict prediction JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import resource
import statistics
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

from atomic_generation import _fsync_directory
from coupled_artifact_publisher import (
    CoupledArtifactError,
    json_bytes,
    jsonl_bytes,
    normalize_publication_path,
    publish_coupled_files,
)
from semantic_contract import (
    ContractError,
    LABELS,
    MAX_SEQUENCE_LENGTH,
    load_jsonl_snapshot,
    model_bundle_evidence as contract_model_bundle_evidence,
    notification_text,
    resolve_training_model_bundle,
)

PREDICTION_SCHEMA_VERSION = "alarmcontrol-semantic-prediction-v1"
MANIFEST_SCHEMA_VERSION = "alarmcontrol-semantic-prediction-manifest-v2"
MAX_BATCH_SIZE = 16
MAX_THREADS = 2
GIB = 1024**3
DEFAULT_MAX_RSS_BYTES = 4 * GIB
WEIGHT_FILENAMES = (
    "model.safetensors",
    "model.safetensors.index.json",
    "pytorch_model.bin",
    "pytorch_model.bin.index.json",
)
PREDICTION_FIELDS = (
    "id",
    "locale",
    "intent",
    "injection",
    "pair_id",
    "predicted_intent",
    "confidence",
    "probabilities",
    "split",
)
OFFLINE_CPU_ENVIRONMENT = {
    "CUDA_VISIBLE_DEVICES": "",
    "HF_HUB_OFFLINE": "1",
    "TRANSFORMERS_OFFLINE": "1",
    "OMP_NUM_THREADS": str(MAX_THREADS),
    "MKL_NUM_THREADS": str(MAX_THREADS),
    "OPENBLAS_NUM_THREADS": str(MAX_THREADS),
    "VECLIB_MAXIMUM_THREADS": str(MAX_THREADS),
    "NUMEXPR_NUM_THREADS": str(MAX_THREADS),
    "RAYON_NUM_THREADS": str(MAX_THREADS),
    "TOKENIZERS_PARALLELISM": "false",
    "PYTORCH_ENABLE_MPS_FALLBACK": "0",
}


class PredictionInputError(ValueError):
    """Raised when local prediction inputs violate the strict contract."""


class ResourceLimitExceeded(RuntimeError):
    """Raised when process RSS reaches its configured ceiling."""


def configure_offline_cpu_environment() -> None:
    """Set offline and two-thread limits before importing ML dependencies."""

    for name, value in OFFLINE_CPU_ENVIRONMENT.items():
        os.environ[name] = value


def _maximum_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def enforce_memory_ceiling(limit_bytes: int, context: str) -> int:
    current = _maximum_rss_bytes()
    if current >= limit_bytes:
        raise ResourceLimitExceeded(
            f"{context}: RSS reached {current} bytes; limit is {limit_bytes}"
        )
    return current


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[rank]


def _atomic_jsonl(path: Path, records: Sequence[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            for record in records:
                stream.write(
                    json.dumps(
                        record,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    )
                )
                stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _resolve_output_path(path: Path) -> Path:
    try:
        return normalize_publication_path(path)
    except CoupledArtifactError as error:
        raise PredictionInputError(str(error)) from error


def _require_nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PredictionInputError(f"{context}: must be a nonempty string")
    return value


def validate_source_record(record: dict[str, Any], context: str) -> None:
    """Validate fields copied into the strict prediction schema."""

    for field in (
        "id",
        "locale",
        "intent",
        "pair_id",
        "title",
        "body",
        "injection",
    ):
        if field not in record:
            raise PredictionInputError(f"{context}: missing {field}")
    _require_nonempty_string(record["id"], f"{context}.id")
    _require_nonempty_string(record["pair_id"], f"{context}.pair_id")
    if record["intent"] not in LABELS:
        raise PredictionInputError(f"{context}: unsupported intent")
    if record["locale"] not in {"ko", "en", "mixed"}:
        raise PredictionInputError(f"{context}: unsupported locale")
    if not isinstance(record["injection"], bool):
        raise PredictionInputError(f"{context}: injection must be boolean")
    if not isinstance(record["title"], str) or not isinstance(
        record["body"],
        str,
    ):
        raise PredictionInputError(
            f"{context}: title and body must be strings"
        )
    if not notification_text(record["title"], record["body"]):
        raise PredictionInputError(f"{context}: notification text is empty")


def select_source_records(
    input_path: Path,
    selected_split: str | None,
) -> list[dict[str, Any]]:
    """Compatibility view returning only the selected source rows."""

    return select_source_records_snapshot(input_path, selected_split)[0]


def select_source_records_snapshot(
    input_path: Path,
    selected_split: str | None,
) -> tuple[list[dict[str, Any]], str]:
    """Select and validate records plus the hash of their source byte snapshot."""

    try:
        records, input_sha256 = load_jsonl_snapshot(input_path)
    except (ContractError, OSError, UnicodeError) as error:
        raise PredictionInputError(str(error)) from error
    if selected_split is not None:
        records = [
            record
            for record in records
            if record.get("split") == selected_split
        ]
    if not records:
        raise PredictionInputError("no input records selected")
    seen_ids: set[str] = set()
    for index, record in enumerate(records, 1):
        validate_source_record(record, f"{input_path}:{index}")
        identifier = record["id"]
        if identifier in seen_ids:
            raise PredictionInputError(
                f"{input_path}:{index}: duplicate id {identifier!r}"
            )
        seen_ids.add(identifier)
    return records, input_sha256


def _require_local_model_bundle(path: Path) -> Path:
    expanded = path.expanduser()
    if expanded.is_symlink():
        raise PredictionInputError(
            f"--model-dir must not be a symlink: {expanded}"
        )
    try:
        selected = resolve_training_model_bundle(expanded)
    except (OSError, ValueError) as error:
        raise PredictionInputError(
            f"--model-dir has no valid committed generation: {expanded}"
        ) from error
    if selected.is_symlink() or not selected.is_dir():
        raise PredictionInputError(
            f"--model-dir must be an existing local directory: {expanded}"
        )
    resolved = selected.resolve()
    if not (resolved / "config.json").is_file():
        raise PredictionInputError(
            f"local model bundle is missing config.json: {resolved}"
        )
    if not any((resolved / name).is_file() for name in WEIGHT_FILENAMES):
        raise PredictionInputError(
            f"local model bundle has no supported weight file: {resolved}"
        )
    return resolved


def model_bundle_hashes(model_dir: Path) -> tuple[dict[str, str], str]:
    """Hash all regular bundle files and derive one stable bundle digest."""

    file_hashes, digest, _ = model_bundle_evidence(model_dir)
    return file_hashes, digest


def model_bundle_evidence(
    model_dir: Path,
) -> tuple[
    dict[str, str],
    str,
    dict[str, tuple[int, int, int, int, int]],
]:
    """Capture hashes and stable identities for one local model bundle."""

    try:
        return contract_model_bundle_evidence(model_dir)
    except ContractError as error:
        raise PredictionInputError(str(error)) from error


def _require_unchanged_model_bundle(
    model_dir: Path,
    expected: tuple[
        dict[str, str],
        str,
        dict[str, tuple[int, int, int, int, int]],
    ],
) -> None:
    if model_bundle_evidence(model_dir) != expected:
        raise PredictionInputError(
            "model bundle changed while predictions were being produced"
        )


def _validate_model_labels(model: Any) -> None:
    if int(model.config.num_labels) != len(LABELS):
        raise PredictionInputError(
            f"model num_labels must be {len(LABELS)}"
        )
    actual = [
        model.config.id2label.get(
            index,
            model.config.id2label.get(str(index)),
        )
        for index in range(len(LABELS))
    ]
    if actual != list(LABELS):
        raise PredictionInputError(
            f"model label order must be {list(LABELS)}, found {actual}"
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model-dir",
        type=Path,
        required=True,
        help="Existing local trained KoELECTRA bundle directory.",
    )
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--split",
        choices=("validation", "test"),
        help="Select one dataset split; omit for sealed holdout files.",
    )
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument(
        "--max-rss-bytes",
        type=int,
        default=DEFAULT_MAX_RSS_BYTES,
        help="Hard peak-RSS ceiling in bytes (default: 4 GiB).",
    )
    return parser


def _validate_arguments(
    arguments: argparse.Namespace,
) -> argparse.Namespace:
    if arguments.batch_size not in range(1, MAX_BATCH_SIZE + 1):
        raise PredictionInputError(
            f"--batch-size must be between 1 and {MAX_BATCH_SIZE}"
        )
    if arguments.max_rss_bytes <= 0:
        raise PredictionInputError("--max-rss-bytes must be positive")
    arguments.model_dir = _require_local_model_bundle(
        arguments.model_dir
    )
    if not arguments.input.is_file():
        raise PredictionInputError(
            f"input does not exist: {arguments.input}"
        )
    arguments.input = arguments.input.resolve()
    arguments.output = _resolve_output_path(arguments.output)
    return arguments


def main(argv: Sequence[str] | None = None) -> int:
    configure_offline_cpu_environment()
    try:
        arguments = _validate_arguments(build_parser().parse_args(argv))
        source_records, input_sha256 = select_source_records_snapshot(
            arguments.input,
            arguments.split,
        )
        enforce_memory_ceiling(
            arguments.max_rss_bytes,
            "before model bundle hash",
        )
        bundle_evidence = model_bundle_evidence(arguments.model_dir)
        bundle_hashes, bundle_digest, _ = bundle_evidence
        enforce_memory_ceiling(
            arguments.max_rss_bytes,
            "before model load",
        )
        try:
            import torch
            import transformers
            from transformers import (
                AutoModelForSequenceClassification,
                AutoTokenizer,
            )
        except ImportError as error:
            raise PredictionInputError(
                "local prediction requires preinstalled torch and transformers"
            ) from error

        torch.set_num_threads(MAX_THREADS)
        try:
            torch.set_num_interop_threads(MAX_THREADS)
        except RuntimeError:
            if torch.get_num_interop_threads() > MAX_THREADS:
                raise

        tokenizer = AutoTokenizer.from_pretrained(
            str(arguments.model_dir),
            local_files_only=True,
            trust_remote_code=False,
        )
        model = AutoModelForSequenceClassification.from_pretrained(
            str(arguments.model_dir),
            local_files_only=True,
            trust_remote_code=False,
        )
        _validate_model_labels(model)
        model.to(torch.device("cpu"))
        model.eval()
        enforce_memory_ceiling(
            arguments.max_rss_bytes,
            "after model load",
        )

        predictions: list[dict[str, Any]] = []
        inference_millis: list[float] = []
        with torch.inference_mode():
            for start in range(0, len(source_records), arguments.batch_size):
                batch_records = source_records[
                    start : start + arguments.batch_size
                ]
                enforce_memory_ceiling(
                    arguments.max_rss_bytes,
                    "before prediction batch",
                )
                encoded = tokenizer(
                    [
                        notification_text(record["title"], record["body"])
                        for record in batch_records
                    ],
                    padding=True,
                    truncation=True,
                    max_length=MAX_SEQUENCE_LENGTH,
                    return_tensors="pt",
                )
                started = time.perf_counter_ns()
                logits = model(**encoded).logits
                elapsed = (time.perf_counter_ns() - started) / 1_000_000
                per_notification = elapsed / len(batch_records)
                inference_millis.extend(
                    [per_notification] * len(batch_records)
                )
                if tuple(logits.shape) != (
                    len(batch_records),
                    len(LABELS),
                ):
                    raise PredictionInputError(
                        f"model produced incompatible logits shape "
                        f"{tuple(logits.shape)}"
                    )
                if not bool(torch.isfinite(logits).all()):
                    raise PredictionInputError(
                        "model produced non-finite logits"
                    )
                batch_probabilities = torch.softmax(
                    logits.float(),
                    dim=-1,
                ).cpu()
                for record, probabilities_tensor in zip(
                    batch_records,
                    batch_probabilities,
                    strict=True,
                ):
                    probabilities = [
                        float(value) for value in probabilities_tensor
                    ]
                    predicted_index = max(
                        range(len(LABELS)),
                        key=probabilities.__getitem__,
                    )
                    prediction = {
                        "id": record["id"],
                        "locale": record["locale"],
                        "intent": record["intent"],
                        "injection": record["injection"],
                        "pair_id": record["pair_id"],
                        "predicted_intent": LABELS[predicted_index],
                        "confidence": probabilities[predicted_index],
                        "probabilities": {
                            label: probabilities[index]
                            for index, label in enumerate(LABELS)
                        },
                        "split": arguments.split or "holdout",
                    }
                    if tuple(prediction) != PREDICTION_FIELDS:
                        raise AssertionError("prediction schema drift")
                    predictions.append(prediction)
                enforce_memory_ceiling(
                    arguments.max_rss_bytes,
                    "after prediction batch",
                )

        output_path = arguments.output
        prediction_bytes = jsonl_bytes(predictions)
        _require_unchanged_model_bundle(
            arguments.model_dir,
            bundle_evidence,
        )
        manifest = {
            "schema_version": MANIFEST_SCHEMA_VERSION,
            "prediction_schema_version": PREDICTION_SCHEMA_VERSION,
            "backend": "pytorch-koelectra",
            "input": str(arguments.input),
            "input_sha256": input_sha256,
            "output": str(output_path),
            "output_sha256": hashlib.sha256(prediction_bytes).hexdigest(),
            "model_artifact_sha256": bundle_digest,
            "selected_split": arguments.split,
            "row_count": len(predictions),
            "model_bundle": str(arguments.model_dir),
            "model_bundle_sha256": bundle_digest,
            "model_files_sha256": bundle_hashes,
            "batch_size": arguments.batch_size,
            "max_sequence_length": MAX_SEQUENCE_LENGTH,
            "max_threads": MAX_THREADS,
            "max_rss_bytes": arguments.max_rss_bytes,
            "runtime": {
                "python": sys.version.split()[0],
                "torch": torch.__version__,
                "transformers": transformers.__version__,
            },
            "host_inference_millis": {
                "mean": statistics.fmean(inference_millis),
                "p50": _percentile(inference_millis, 0.50),
                "p95": _percentile(inference_millis, 0.95),
                "maximum": max(inference_millis),
                "measurement": (
                    "batch model-forward wall time divided by batch row count"
                ),
                "note": (
                    "developer-host measurement; not an Android device "
                    "acceptance result"
                ),
            },
        }
        vocab_sha256 = bundle_hashes.get("vocab.txt")
        if vocab_sha256 is not None:
            manifest["vocab_sha256"] = vocab_sha256
        manifest_path = output_path.with_suffix(
            f"{output_path.suffix}.manifest.json"
        )
        publish_coupled_files(
            {
                output_path: prediction_bytes,
                manifest_path: json_bytes(manifest),
            },
            lock_name=f".{output_path.name}.publish.lock",
        )
        print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
        return 0
    except (PredictionInputError, ResourceLimitExceeded) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
