#!/usr/bin/env python3
"""Verify that runtime prompts and bounded worst cases fit the task KV cache."""

from __future__ import annotations

import argparse
import os
from collections.abc import Mapping
from pathlib import Path

from contract import build_fitting_prompt, build_prompt, utf16_length
from train import load_rows

HERE = Path(__file__).resolve().parent
DEFAULT_DATASET = HERE / "artifacts" / "dataset"

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


def token_count(tokenizer: object, prompt: str) -> int:
    encoded = tokenizer.apply_chat_template(
        [{"role": "user", "content": prompt}],
        tokenize=True,
        add_generation_prompt=True,
    )
    input_ids = encoded["input_ids"] if isinstance(encoded, Mapping) else encoded
    shape = getattr(input_ids, "shape", None)
    if shape is not None:
        return int(shape[-1])
    if input_ids and isinstance(input_ids[0], (list, tuple)):
        return len(input_ids[0])
    return len(input_ids)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--context-tokens", type=int, default=4_096)
    parser.add_argument("--output-budget", type=int, default=128)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        str(args.model_dir.resolve()),
        local_files_only=True,
    )
    cases: list[tuple[str, str]] = []
    for split in ("train", "validation", "test"):
        for row in load_rows(args.dataset_dir.resolve() / f"{split}.jsonl"):
            cases.append((row["id"], row["messages"][0]["content"]))
    cases.extend(
        [
            ("worst-ascii", build_prompt("W" * 2_000)),
            ("worst-korean", build_prompt("가" * 2_000)),
            ("worst-emoji", build_prompt("🔐" * 2_000)),
            ("worst-control", build_prompt('"}\n\t' * 500)),
        ]
    )
    measured = [(name, token_count(tokenizer, prompt)) for name, prompt in cases]
    name, largest = max(measured, key=lambda item: item[1])
    prompt_budget = args.context_tokens - args.output_budget
    required = largest + args.output_budget
    print(
        f"largest={largest} prompt tokens ({name}); "
        f"required_with_output={required}; context={args.context_tokens}"
    )
    for case_name, notification in (
        ("worst-ascii", "W" * 2_000),
        ("worst-korean", "가" * 2_000),
        ("worst-emoji", "🔐" * 2_000),
        ("worst-control", '"}\n\t' * 500),
    ):
        fitted = build_fitting_prompt(
            notification,
            prompt_budget,
            lambda prompt: token_count(tokenizer, prompt),
        )
        if fitted is None:
            raise SystemExit("The fixed classifier instructions do not fit the KV-cache budget")
        fitted_prompt, retained_units = fitted
        fitted_tokens = token_count(tokenizer, fitted_prompt)
        print(
            f"{case_name}: retained_utf16={retained_units}/{utf16_length(notification)}, "
            f"prompt_tokens={fitted_tokens}"
        )
        if fitted_tokens + args.output_budget > args.context_tokens:
            raise SystemExit(f"{case_name}: token-aware runtime fit exceeded the context")


if __name__ == "__main__":
    main()
