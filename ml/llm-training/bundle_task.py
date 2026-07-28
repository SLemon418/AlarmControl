#!/usr/bin/env python3
"""Bundle a LiteRT model and Gemma tokenizer as one MediaPipe .task file."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = (
    HERE
    / "artifacts"
    / "alarmcontrol-gemma3-270m-dynint4-block128-kv4096.task"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tflite-model", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def validate_bundle_paths(
    tflite_model: Path,
    model_dir: Path,
    tokenizer_model: Path,
    output: Path,
) -> None:
    if not tflite_model.is_file() or tflite_model.stat().st_size == 0:
        raise SystemExit("LiteRT model must be an existing non-empty local file")
    if not tokenizer_model.is_file() or tokenizer_model.stat().st_size == 0:
        raise SystemExit("model-dir must contain a non-empty tokenizer.model")
    if output.suffix != ".task":
        raise SystemExit("Bundle output must use the .task suffix")
    if output == tflite_model or output == tokenizer_model:
        raise SystemExit("Bundle output must differ from its inputs")
    if model_dir == output.parent or model_dir in output.parents:
        raise SystemExit("Bundle output must not be written inside model-dir")
    checksum = output.with_suffix(output.suffix + ".sha256")
    if output.exists() or checksum.exists():
        raise SystemExit("Bundle output or checksum already exists")


def create_task_bundle(
    tflite_model: Path,
    tokenizer_model: Path,
    output: Path,
    bundler_module: Any,
) -> str:
    output.parent.mkdir(parents=True, exist_ok=True)
    config = bundler_module.BundleConfig(
        tflite_model=str(tflite_model),
        tokenizer_model=str(tokenizer_model),
        start_token="<bos>",
        stop_tokens=["<eos>", "<end_of_turn>"],
        output_filename=str(output),
        prompt_prefix_user="<start_of_turn>user\n",
        prompt_suffix_user="<end_of_turn>\n",
        prompt_prefix_model="<start_of_turn>model\n",
        prompt_suffix_model="<end_of_turn>\n",
    )
    bundler_module.create_bundle(config)
    if not output.is_file() or output.stat().st_size == 0:
        raise SystemExit(f"MediaPipe bundler did not create a non-empty task: {output}")
    fingerprint = sha256(output)
    output.with_suffix(output.suffix + ".sha256").write_text(
        f"{fingerprint}  {output.name}\n",
        encoding="utf-8",
    )
    return fingerprint


def main() -> None:
    args = parse_args()
    tflite_model = args.tflite_model.resolve()
    model_dir = args.model_dir.resolve()
    tokenizer_model = model_dir / "tokenizer.model"
    output = args.output.resolve()
    validate_bundle_paths(
        tflite_model,
        model_dir,
        tokenizer_model,
        output,
    )

    from mediapipe.tasks.python.genai import bundler

    fingerprint = create_task_bundle(
        tflite_model,
        tokenizer_model,
        output,
        bundler,
    )
    print(f"Task bundle: {output}")
    print(f"SHA-256: {fingerprint}")


if __name__ == "__main__":
    main()
