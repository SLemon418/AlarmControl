#!/usr/bin/env python3
"""Run the converted semantic TFLite model and emit strict prediction JSONL."""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import struct
import tempfile
import time
from pathlib import Path
from typing import Any, Sequence

from semantic_contract import (
    LABELS,
    MAX_SEQUENCE_LENGTH,
    WordPieceTokenizer,
    load_jsonl,
    notification_text,
    sha256_file,
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
        os.replace(temporary_name, path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        type=Path,
        default=DEFAULT_MODEL_DIR / "semantic_notification_classifier.tflite",
    )
    parser.add_argument(
        "--vocab",
        type=Path,
        default=DEFAULT_MODEL_DIR / "semantic_vocab.txt",
    )
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--split",
        choices=("train", "validation", "test"),
        help="select one dataset split; omit for sealed holdout files",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    _configure_process_environment()
    arguments = build_parser().parse_args(argv)

    import numpy as np
    from ai_edge_litert.interpreter import Interpreter

    model_path = arguments.model.resolve(strict=True)
    vocab_path = arguments.vocab.resolve(strict=True)
    input_path = arguments.input.resolve(strict=True)
    tokenizer = WordPieceTokenizer.from_file(vocab_path)
    source_records = load_jsonl(input_path)
    if arguments.split:
        source_records = [
            record
            for record in source_records
            if record.get("split") == arguments.split
        ]
    if not source_records:
        raise ValueError("no input records selected")
    for index, record in enumerate(source_records, 1):
        _validate_source_record(record, f"{input_path}:{index}")

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

    output_path = arguments.output.resolve()
    _atomic_jsonl(output_path, predictions)
    manifest = {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "prediction_schema_version": PREDICTION_SCHEMA_VERSION,
        "backend": BACKEND,
        "input": str(input_path),
        "input_sha256": sha256_file(input_path),
        "output": str(output_path),
        "output_sha256": sha256_file(output_path),
        "model_artifact_sha256": sha256_file(model_path),
        "selected_split": arguments.split,
        "row_count": len(predictions),
        "model": str(model_path),
        "model_sha256": sha256_file(model_path),
        "vocab": str(vocab_path),
        "vocab_sha256": sha256_file(vocab_path),
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
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{manifest_path.name}.",
        suffix=".tmp",
        dir=manifest_path.parent,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary_name, manifest_path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
