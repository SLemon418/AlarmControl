#!/usr/bin/env python3
"""Run the converted semantic TFLite model and emit strict prediction JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import statistics
import struct
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
    CONVERSION_MODEL_FILENAME,
    CONVERSION_VOCAB_FILENAME,
    ContractError,
    LABELS,
    MAX_SEQUENCE_LENGTH,
    WordPieceTokenizer,
    load_jsonl_snapshot,
    notification_text,
    regular_file_evidence,
    resolve_conversion_bundle,
    resolve_training_model_bundle,
)

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_MODEL_DIR = BASE_DIR / "artifacts" / "fallback-conv1d"
PREDICTION_SCHEMA_VERSION = "alarmcontrol-semantic-prediction-v1"
MANIFEST_SCHEMA_VERSION = "alarmcontrol-semantic-prediction-manifest-v2"
BACKEND = "tensorflow-lite"


def _configure_process_environment() -> None:
    os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")
    os.environ.setdefault("OMP_NUM_THREADS", "2")
    os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")
    os.environ.setdefault("TF_NUM_INTEROP_THREADS", "1")
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")


def _float32(value: float) -> float:
    return struct.unpack(">f", struct.pack(">f", value))[0]


def _softmax(logits) -> list[float]:
    """Mirror Kotlin FloatArray.softmax() rounding at every Float operation."""

    float_logits = [_float32(float(value)) for value in logits]
    maximum = max(float_logits)
    exponentials = [
        _float32(
            math.exp(
                float(_float32(value - maximum))
            )
        )
        for value in float_logits
    ]
    denominator = _float32(0.0)
    for value in exponentials:
        denominator = _float32(denominator + value)
    if not math.isfinite(denominator) or denominator <= 0:
        raise ValueError("model produced invalid logits")
    return [
        _float32(value / denominator)
        for value in exponentials
    ]


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[rank]


def _atomic_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            for record in records:
                stream.write(
                    json.dumps(record, ensure_ascii=False, separators=(",", ":"))
                )
                stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
        _fsync_directory(path.parent)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
        _fsync_directory(path.parent)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def _resolve_output_path(path: Path) -> Path:
    try:
        return normalize_publication_path(path)
    except CoupledArtifactError as error:
        raise ValueError(str(error)) from error


def _resolve_regular_input(path: Path, context: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"{context} must be a non-symlink regular file: {path}")
    return path.resolve(strict=True)


def _file_evidence(
    path: Path,
    context: str,
) -> tuple[str, tuple[int, int, int, int, int]]:
    try:
        return regular_file_evidence(path)
    except ContractError as error:
        raise ValueError(f"{context}: {error}") from error


def _require_unchanged_file(
    path: Path,
    context: str,
    expected: tuple[str, tuple[int, int, int, int, int]],
) -> None:
    if _file_evidence(path, context) != expected:
        raise ValueError(f"{context} changed while predictions were produced")


def _validate_source_record(record: dict[str, Any], context: str) -> None:
    for field in ("id", "locale", "intent", "pair_id", "title", "body", "injection"):
        if field not in record:
            raise ValueError(f"{context}: missing {field}")
    if record["intent"] not in LABELS:
        raise ValueError(f"{context}: unsupported intent")
    if record["locale"] not in {"ko", "en", "mixed"}:
        raise ValueError(f"{context}: unsupported locale")
    if not isinstance(record["injection"], bool):
        raise ValueError(f"{context}: injection must be boolean")
    if not isinstance(record["title"], str) or not isinstance(record["body"], str):
        raise ValueError(f"{context}: title and body must be strings")


def _load_source_records(
    input_path: Path,
    selected_split: str | None,
) -> tuple[list[dict[str, Any]], str]:
    """Validate selected rows and hash the exact JSONL bytes that produced them."""

    source_records, input_sha256 = load_jsonl_snapshot(input_path)
    if selected_split:
        source_records = [
            record
            for record in source_records
            if record.get("split") == selected_split
        ]
    if not source_records:
        raise ValueError("no input records selected")
    for index, record in enumerate(source_records, 1):
        _validate_source_record(record, f"{input_path}:{index}")
    return source_records, input_sha256


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        type=Path,
        help="override the model inside the committed default bundle",
    )
    parser.add_argument(
        "--vocab",
        type=Path,
        help="override the vocabulary inside the committed default bundle",
    )
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--split",
        choices=("train", "validation", "test"),
        help="select one dataset split; omit for sealed holdout files",
    )
    return parser


def _resolve_model_and_vocab(
    model: Path | None,
    vocab: Path | None,
) -> tuple[Path, Path]:
    if (
        model is not None
        and vocab is not None
        and model.parent == vocab.parent
        and model.name == CONVERSION_MODEL_FILENAME
        and vocab.name == CONVERSION_VOCAB_FILENAME
    ):
        bundle = resolve_conversion_bundle(model.parent)
        return bundle / model.name, bundle / vocab.name

    default_bundle = (
        resolve_conversion_bundle(DEFAULT_MODEL_DIR)
        if model is None or vocab is None
        else DEFAULT_MODEL_DIR
    )
    model_path = model if model is not None else default_bundle / CONVERSION_MODEL_FILENAME
    vocab_path = vocab if vocab is not None else default_bundle / CONVERSION_VOCAB_FILENAME
    if vocab_path.name == "vocab.txt":
        vocab_path = resolve_training_model_bundle(vocab_path.parent) / vocab_path.name
    return model_path, vocab_path


def main(argv: Sequence[str] | None = None) -> int:
    _configure_process_environment()
    arguments = build_parser().parse_args(argv)

    import numpy as np
    from ai_edge_litert.interpreter import Interpreter

    selected_model, selected_vocab = _resolve_model_and_vocab(
        arguments.model,
        arguments.vocab,
    )
    model_path = _resolve_regular_input(selected_model, "model")
    vocab_path = _resolve_regular_input(selected_vocab, "vocabulary")
    input_path = arguments.input.resolve(strict=True)
    model_evidence = _file_evidence(model_path, "model")
    vocab_evidence = _file_evidence(vocab_path, "vocabulary")
    tokenizer = WordPieceTokenizer.from_file(vocab_path)
    source_records, input_sha256 = _load_source_records(
        input_path,
        arguments.split,
    )

    interpreter = Interpreter(model_path=str(model_path), num_threads=2)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    input_by_kind: dict[str, dict[str, Any]] = {}
    for detail in input_details:
        name = detail["name"].lower()
        if "input_ids" in name:
            input_by_kind["ids"] = detail
        elif "attention_mask" in name:
            input_by_kind["mask"] = detail
        elif "token_type_ids" in name:
            input_by_kind["types"] = detail
    if set(input_by_kind) not in ({"ids", "mask"}, {"ids", "mask", "types"}):
        raise ValueError(
            f"model inputs are incompatible: {[item['name'] for item in input_details]}"
        )
    for detail in input_by_kind.values():
        if detail["dtype"] != np.int32 or detail["shape"].tolist() != [
            1,
            MAX_SEQUENCE_LENGTH,
        ]:
            raise ValueError(
                f"incompatible input tensor: {detail['name']} "
                f"{detail['dtype']} {detail['shape'].tolist()}"
            )
    if len(output_details) != 1:
        raise ValueError("model must expose exactly one output")
    output_detail = output_details[0]
    if output_detail["dtype"] != np.float32 or output_detail["shape"].tolist() != [
        1,
        len(LABELS),
    ]:
        raise ValueError(
            f"incompatible output tensor: {output_detail['name']} "
            f"{output_detail['dtype']} {output_detail['shape'].tolist()}"
        )

    predictions: list[dict[str, Any]] = []
    inference_millis: list[float] = []
    for record in source_records:
        encoded = tokenizer.encode(notification_text(record["title"], record["body"]))
        values = {
            "ids": encoded.input_ids,
            "mask": encoded.attention_mask,
            "types": encoded.token_type_ids,
        }
        for kind, detail in input_by_kind.items():
            interpreter.set_tensor(
                detail["index"],
                np.asarray([values[kind]], dtype=np.int32),
            )
        started = time.perf_counter_ns()
        interpreter.invoke()
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        inference_millis.append(elapsed)
        logits = interpreter.get_tensor(output_detail["index"])[0].tolist()
        if len(logits) != len(LABELS) or any(
            not math.isfinite(value) for value in logits
        ):
            raise ValueError(f"{record['id']}: invalid logits")
        probabilities = _softmax(logits)
        predicted_index = max(range(len(LABELS)), key=probabilities.__getitem__)
        predictions.append(
            {
                "id": record["id"],
                "locale": record["locale"],
                "intent": record["intent"],
                "pair_id": record["pair_id"],
                "injection": record["injection"],
                "split": arguments.split or record.get("split", "holdout"),
                "predicted_intent": LABELS[predicted_index],
                "confidence": probabilities[predicted_index],
                "probabilities": {
                    label: probabilities[index] for index, label in enumerate(LABELS)
                },
            }
        )

    output_path = _resolve_output_path(arguments.output)
    prediction_bytes = jsonl_bytes(predictions)
    _require_unchanged_file(model_path, "model", model_evidence)
    _require_unchanged_file(vocab_path, "vocabulary", vocab_evidence)
    model_sha256 = model_evidence[0]
    vocab_sha256 = vocab_evidence[0]
    manifest = {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "prediction_schema_version": PREDICTION_SCHEMA_VERSION,
        "backend": BACKEND,
        "input": str(input_path),
        "input_sha256": input_sha256,
        "output": str(output_path),
        "output_sha256": hashlib.sha256(prediction_bytes).hexdigest(),
        "model_artifact_sha256": model_sha256,
        "selected_split": arguments.split,
        "row_count": len(predictions),
        "model": str(model_path),
        "model_sha256": model_sha256,
        "vocab": str(vocab_path),
        "vocab_sha256": vocab_sha256,
        "max_sequence_length": MAX_SEQUENCE_LENGTH,
        "max_threads": 2,
        "host_inference_millis": {
            "mean": statistics.fmean(inference_millis),
            "p50": _percentile(inference_millis, 0.50),
            "p95": _percentile(inference_millis, 0.95),
            "maximum": max(inference_millis),
            "note": (
                "developer-host measurement; not an Android device "
                "acceptance result"
            ),
        },
    }
    manifest_path = output_path.with_suffix(f"{output_path.suffix}.manifest.json")
    publish_coupled_files(
        {
            output_path: prediction_bytes,
            manifest_path: json_bytes(manifest),
        },
        lock_name=f".{output_path.name}.publish.lock",
    )
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
