#!/usr/bin/env python3
"""Bundle a LiteRT model and Gemma tokenizer as one MediaPipe .task file."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "artifacts" / "alarmcontrol-gemma3-270m-dynint8-kv4096.task"


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


def main() -> None:
    args = parse_args()
    tflite_model = args.tflite_model.resolve()
    tokenizer_model = args.model_dir.resolve() / "tokenizer.model"
    output = args.output.resolve()
    if not tflite_model.is_file():
        raise SystemExit(f"LiteRT model not found: {tflite_model}")
    if not tokenizer_model.is_file():
        raise SystemExit(f"Gemma tokenizer.model not found: {tokenizer_model}")
    output.parent.mkdir(parents=True, exist_ok=True)

    from mediapipe.tasks.python.genai import bundler

    bundler.create_bundle(
        bundler.BundleConfig(
            tflite_model=str(tflite_model),
            tokenizer_model=str(tokenizer_model),
            start_token="<bos>",
            stop_tokens=["<eos>", "<end_of_turn>"],
            output_filename=str(output),
            prompt_prefix="<start_of_turn>user\n",
            prompt_suffix="<end_of_turn>\n<start_of_turn>model\n",
        )
    )
    if not output.is_file() or output.stat().st_size == 0:
        raise SystemExit(f"MediaPipe bundler did not create a non-empty task: {output}")
    fingerprint = sha256(output)
    output.with_suffix(output.suffix + ".sha256").write_text(
        f"{fingerprint}  {output.name}\n",
        encoding="utf-8",
    )
    print(f"Task bundle: {output}")
    print(f"SHA-256: {fingerprint}")


if __name__ == "__main__":
    main()
