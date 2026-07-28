#!/usr/bin/env python3
"""Merge a selected local PEFT adapter into a conversion-ready HF model."""

from __future__ import annotations

import argparse
import os
import shutil
from pathlib import Path
from typing import Any

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def validate_inputs(base_model: Path, adapter_checkpoint: Path) -> None:
    if not base_model.is_dir():
        raise SystemExit(f"Base model directory not found: {base_model}")
    if not (base_model / "config.json").is_file():
        raise SystemExit(f"Base model is missing config.json: {base_model}")
    if not any(
        (base_model / name).is_file()
        for name in ("tokenizer.json", "tokenizer.model")
    ):
        raise SystemExit(
            "Base model is missing tokenizer.json or tokenizer.model: "
            f"{base_model}"
        )
    if not any(base_model.glob("model*.safetensors")):
        raise SystemExit(f"Base model has no safetensors weights: {base_model}")

    if not adapter_checkpoint.is_dir():
        raise SystemExit(f"Adapter checkpoint directory not found: {adapter_checkpoint}")
    for required in ("adapter_config.json", "adapter_model.safetensors"):
        if not (adapter_checkpoint / required).is_file():
            raise SystemExit(
                f"Adapter checkpoint is missing {required}: {adapter_checkpoint}"
            )


def merge_adapter(
    base_model: Path,
    adapter_checkpoint: Path,
    output_dir: Path,
    *,
    auto_model_class: Any,
    auto_tokenizer_class: Any,
    peft_model_class: Any,
) -> None:
    validate_inputs(base_model, adapter_checkpoint)
    if output_dir in {base_model, adapter_checkpoint}:
        raise SystemExit("Output directory must differ from the input directories")

    tokenizer = auto_tokenizer_class.from_pretrained(
        str(base_model),
        local_files_only=True,
    )
    model = auto_model_class.from_pretrained(
        str(base_model),
        local_files_only=True,
        dtype="auto",
        low_cpu_mem_usage=True,
    )
    adapter_model = peft_model_class.from_pretrained(
        model,
        str(adapter_checkpoint),
        local_files_only=True,
    )
    merged_model = adapter_model.merge_and_unload(safe_merge=True)

    output_dir.mkdir(parents=True, exist_ok=True)
    merged_model.save_pretrained(
        str(output_dir),
        safe_serialization=True,
        max_shard_size="2GB",
    )
    tokenizer.save_pretrained(str(output_dir))
    tokenizer_model = base_model / "tokenizer.model"
    if tokenizer_model.is_file():
        shutil.copy2(
            tokenizer_model,
            output_dir / "tokenizer.model",
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--adapter-checkpoint", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    base_model = args.base_model.resolve()
    adapter_checkpoint = args.adapter_checkpoint.resolve()
    output_dir = args.output_dir.resolve()

    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    merge_adapter(
        base_model,
        adapter_checkpoint,
        output_dir,
        auto_model_class=AutoModelForCausalLM,
        auto_tokenizer_class=AutoTokenizer,
        peft_model_class=PeftModel,
    )
    print(f"Merged Hugging Face model written to {output_dir}")


if __name__ == "__main__":
    main()
