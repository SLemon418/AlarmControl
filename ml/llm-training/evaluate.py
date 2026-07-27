#!/usr/bin/env python3
"""Evaluate a merged or PEFT adapter checkpoint against AlarmControl's held-out split."""

from __future__ import annotations

import argparse
import json
import os
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from contract import INTENTS, ParsedVerdict, parse_response
from train import configure_mps_allocator, load_rows, select_device_and_dtype

HERE = Path(__file__).resolve().parent
DEFAULT_DATASET = HERE / "artifacts" / "dataset" / "test.jsonl"
DEFAULT_OUTPUT = HERE / "artifacts" / "evaluation"

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def classification_metrics(
    expected: list[str],
    predicted: list[str | None],
) -> dict[str, Any]:
    per_intent: dict[str, dict[str, float | int]] = {}
    f1_values: list[float] = []
    for intent in INTENTS:
        true_positive = sum(
            wanted == intent and got == intent for wanted, got in zip(expected, predicted)
        )
        false_positive = sum(
            wanted != intent and got == intent for wanted, got in zip(expected, predicted)
        )
        false_negative = sum(
            wanted == intent and got != intent for wanted, got in zip(expected, predicted)
        )
        precision = (
            true_positive / (true_positive + false_positive)
            if true_positive + false_positive
            else 0.0
        )
        recall = (
            true_positive / (true_positive + false_negative)
            if true_positive + false_negative
            else 0.0
        )
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1_values.append(f1)
        per_intent[intent] = {
            "f1": f1,
            "precision": precision,
            "recall": recall,
            "support": expected.count(intent),
        }
    return {
        "accuracy": sum(wanted == got for wanted, got in zip(expected, predicted))
        / len(expected),
        "macro_f1": sum(f1_values) / len(f1_values),
        "per_intent": per_intent,
    }


def evaluate_predictions(rows: list[dict[str, Any]]) -> dict[str, Any]:
    expected = [row["expected"] for row in rows]
    predicted = [row["parsed"]["intent"] if row["parsed"] else None for row in rows]
    metrics = classification_metrics(expected, predicted)
    parsed_count = sum(row["parsed"] is not None for row in rows)
    injection_rows = [row for row in rows if "prompt-injection" in row["tags"]]
    clean_rows = [row for row in rows if "prompt-injection" not in row["tags"]]
    actionable = [
        row
        for row in rows
        if row["parsed"]
        and row["parsed"]["intent"] != "AMBIGUOUS"
        and row["parsed"]["confidence"] >= 0.6
    ]
    wrong_actionable = sum(row["parsed"]["intent"] != row["expected"] for row in actionable)
    locale_correct: dict[str, list[bool]] = defaultdict(list)
    for row in rows:
        locale_correct[row["locale"]].append(
            bool(row["parsed"] and row["parsed"]["intent"] == row["expected"])
        )
    metrics.update(
        {
            "json_parse_rate": parsed_count / len(rows),
            "clean_accuracy": (
                sum(
                    bool(
                        row["parsed"]
                        and row["parsed"]["intent"] == row["expected"]
                    )
                    for row in clean_rows
                )
                / len(clean_rows)
                if clean_rows
                else 0.0
            ),
            "clean_json_parse_rate": (
                sum(row["parsed"] is not None for row in clean_rows)
                / len(clean_rows)
                if clean_rows
                else 0.0
            ),
            "injection_accuracy": (
                sum(
                    bool(
                        row["parsed"]
                        and row["parsed"]["intent"] == row["expected"]
                    )
                    for row in injection_rows
                )
                / len(injection_rows)
                if injection_rows
                else 0.0
            ),
            "injection_json_parse_rate": (
                sum(row["parsed"] is not None for row in injection_rows)
                / len(injection_rows)
                if injection_rows
                else 0.0
            ),
            "actionable_coverage": len(actionable) / len(rows),
            "wrong_actionable_rate": wrong_actionable / len(rows),
            "actionable_accuracy": (
                (len(actionable) - wrong_actionable) / len(actionable) if actionable else 0.0
            ),
            "locale_accuracy": {
                locale: sum(values) / len(values) for locale, values in locale_correct.items()
            },
            "prediction_counts": Counter(value or "INVALID" for value in predicted),
        }
    )
    return metrics


def generation_stop_token_ids(tokenizer: Any) -> list[int]:
    stop_ids: list[int] = []
    if isinstance(tokenizer.eos_token_id, int):
        stop_ids.append(tokenizer.eos_token_id)
    end_of_turn_id = tokenizer.convert_tokens_to_ids("<end_of_turn>")
    if (
        isinstance(end_of_turn_id, int)
        and end_of_turn_id != tokenizer.unk_token_id
        and end_of_turn_id not in stop_ids
    ):
        stop_ids.append(end_of_turn_id)
    if not stop_ids:
        raise ValueError("Tokenizer has no supported generation stop token")
    return stop_ids


def generate(
    model: Any,
    tokenizer: Any,
    torch_module: Any,
    device: str,
    user_message: dict[str, str],
    max_new_tokens: int,
) -> str:
    prompt = tokenizer.apply_chat_template(
        [user_message],
        tokenize=False,
        add_generation_prompt=True,
    )
    encoded = tokenizer(prompt, return_tensors="pt", add_special_tokens=False)
    encoded = {key: value.to(device) for key, value in encoded.items()}
    with torch_module.inference_mode():
        generated = model.generate(
            **encoded,
            max_new_tokens=max_new_tokens,
            do_sample=False,
            num_beams=1,
            pad_token_id=tokenizer.pad_token_id,
            eos_token_id=generation_stop_token_ids(tokenizer),
        )
    output_ids = generated[0, encoded["input_ids"].shape[1] :]
    return tokenizer.decode(output_ids, skip_special_tokens=True).strip()


def load_model_and_tokenizer(
    model_dir: Path,
    base_model: Path | None,
    dtype: Any,
    auto_model_class: Any,
    auto_tokenizer_class: Any,
) -> tuple[Any, Any]:
    adapter_checkpoint = (model_dir / "adapter_config.json").is_file()
    load_dir = model_dir
    if adapter_checkpoint:
        if base_model is None:
            raise SystemExit("--base-model is required for a PEFT adapter checkpoint")
        if not base_model.is_dir():
            raise SystemExit(f"Base model directory not found: {base_model}")
        load_dir = base_model

    tokenizer = auto_tokenizer_class.from_pretrained(
        str(load_dir),
        local_files_only=True,
    )
    model = auto_model_class.from_pretrained(
        str(load_dir),
        local_files_only=True,
        dtype=dtype,
        low_cpu_mem_usage=True,
    )
    if adapter_checkpoint:
        from peft import PeftModel

        model = PeftModel.from_pretrained(
            model,
            str(model_dir),
            local_files_only=True,
        )
    return tokenizer, model


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument(
        "--base-model",
        type=Path,
        help="Local base model directory required for a PEFT adapter checkpoint",
    )
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--max-new-tokens", type=int, default=96)
    parser.add_argument(
        "--mps-dtype",
        choices=("float32", "bfloat16"),
        default="float32",
        help="Model dtype on Apple MPS; ignored on CUDA and CPU",
    )
    parser.add_argument("--no-gate", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    model_dir = args.model_dir.resolve()
    base_model = args.base_model.resolve() if args.base_model is not None else None
    dataset = args.dataset.resolve()
    output_dir = args.output_dir.resolve()
    if not model_dir.is_dir() or not dataset.is_file():
        raise SystemExit("Model checkpoint or test dataset is missing")

    configure_mps_allocator()
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    device, dtype, _, _ = select_device_and_dtype(
        torch,
        mps_dtype=args.mps_dtype,
    )
    tokenizer, model = load_model_and_tokenizer(
        model_dir,
        base_model,
        dtype,
        AutoModelForCausalLM,
        AutoTokenizer,
    )
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = model.to(device)
    model.eval()

    predictions: list[dict[str, Any]] = []
    test_rows = load_rows(dataset)
    for index, row in enumerate(test_rows, start=1):
        expected = parse_response(row["messages"][1]["content"])
        if expected is None:
            raise ValueError(f"{row['id']}: invalid expected response")
        raw = generate(
            model,
            tokenizer,
            torch,
            device,
            row["messages"][0],
            args.max_new_tokens,
        )
        if device == "mps":
            torch.mps.empty_cache()
        parsed = parse_response(raw)
        predictions.append(
            {
                "id": row["id"],
                "locale": row["metadata"]["locale"],
                "tags": row["metadata"]["tags"],
                "expected": expected.intent,
                "raw": raw,
                "parsed": (
                    {
                        "intent": parsed.intent,
                        "confidence": parsed.confidence,
                        "reason": parsed.reason,
                    }
                    if isinstance(parsed, ParsedVerdict)
                    else None
                ),
            }
        )
        print(f"Evaluated {index}/{len(test_rows)}: {row['id']}", flush=True)

    output_dir.mkdir(parents=True, exist_ok=True)
    with (output_dir / "predictions.jsonl").open("w", encoding="utf-8") as handle:
        for prediction in predictions:
            handle.write(json.dumps(prediction, ensure_ascii=False) + "\n")
    metrics = evaluate_predictions(predictions)
    (output_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(metrics, ensure_ascii=False, indent=2, sort_keys=True))

    marketing_precision = metrics["per_intent"]["MARKETING"]["precision"]
    passed = (
        metrics["json_parse_rate"] == 1.0
        and metrics["macro_f1"] >= 0.85
        and marketing_precision >= 0.90
        and metrics["clean_accuracy"] >= 0.85
        and metrics["injection_accuracy"] >= 0.85
        and metrics["wrong_actionable_rate"] <= 0.05
    )
    if not passed and not args.no_gate:
        raise SystemExit("Evaluation gate failed; do not convert or distribute this checkpoint")


if __name__ == "__main__":
    main()
