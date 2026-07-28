#!/usr/bin/env python3
"""Fine-tune a local Gemma checkpoint for AlarmControl's JSON classifier."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import random
from collections.abc import Mapping
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
DEFAULT_DATASET = HERE / "artifacts" / "dataset"
DEFAULT_OUTPUT = HERE / "artifacts" / "training"
SEED = 20260727
LORA_TARGET_PROFILES = {
    "attention": ("q_proj", "k_proj", "v_proj", "o_proj"),
    "attention-mlp": (
        "q_proj",
        "k_proj",
        "v_proj",
        "o_proj",
        "gate_proj",
        "up_proj",
        "down_proj",
    ),
}

# Model access is deliberately local-only. Accepting the Gemma terms and obtaining
# the base weights is a user action, never an implicit trainer side effect.
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("WANDB_DISABLED", "true")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def load_rows(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def chat_template_input_ids(
    tokenizer: Any,
    messages: list[dict[str, str]],
    *,
    add_generation_prompt: bool,
) -> list[int]:
    encoded = tokenizer.apply_chat_template(
        messages,
        tokenize=True,
        add_generation_prompt=add_generation_prompt,
    )
    input_ids = encoded["input_ids"] if isinstance(encoded, Mapping) else encoded
    if input_ids and isinstance(input_ids[0], (list, tuple)):
        if len(input_ids) != 1:
            raise ValueError("Expected one chat conversation")
        input_ids = input_ids[0]
    return list(input_ids)


def encode_rows(
    rows: list[dict[str, Any]],
    tokenizer: Any,
    max_length: int,
    *,
    loss_scope: str = "full",
) -> list[dict[str, list[int]]]:
    if loss_scope not in {"full", "intent"}:
        raise ValueError(f"Unsupported loss scope: {loss_scope}")

    encoded: list[dict[str, list[int]]] = []
    for row in rows:
        messages = row["messages"]
        if len(messages) < 2 or messages[-1].get("role") != "assistant":
            raise ValueError(f"{row['id']}: final chat message must be the assistant response")
        prompt_ids = chat_template_input_ids(
            tokenizer,
            messages[:-1],
            add_generation_prompt=True,
        )
        full_ids = chat_template_input_ids(
            tokenizer,
            messages,
            add_generation_prompt=False,
        )
        if full_ids[: len(prompt_ids)] != prompt_ids:
            raise ValueError(f"{row['id']}: tokenizer chat template changed before assistant output")
        if len(full_ids) > max_length:
            raise ValueError(
                f"{row['id']}: {len(full_ids)} tokens exceeds --max-length={max_length}"
            )
        if loss_scope == "intent":
            try:
                intent = json.loads(messages[-1]["content"])["intent"]
            except (KeyError, TypeError, json.JSONDecodeError) as error:
                raise ValueError(
                    f"{row['id']}: assistant response has no JSON intent"
                ) from error
            intent_ids = list(
                tokenizer.encode(
                    intent,
                    add_special_tokens=False,
                )
            )
            assistant_ids = full_ids[len(prompt_ids) :]
            matches = [
                index
                for index in range(len(assistant_ids) - len(intent_ids) + 1)
                if assistant_ids[index : index + len(intent_ids)] == intent_ids
            ]
            if len(matches) != 1:
                raise ValueError(
                    f"{row['id']}: expected one tokenized intent, found {len(matches)}"
                )
            labels = [-100] * len(full_ids)
            start = len(prompt_ids) + matches[0]
            labels[start : start + len(intent_ids)] = intent_ids
        else:
            labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids) :]
        if not any(label != -100 for label in labels):
            raise ValueError(f"{row['id']}: assistant response has no trainable tokens")
        encoded.append({"input_ids": full_ids, "labels": labels})
    return encoded


class TokenizedDataset:
    """Minimal Trainer-compatible dataset with no third-party dataset dependency."""

    def __init__(self, rows: list[dict[str, list[int]]]) -> None:
        self.rows = rows

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int) -> dict[str, list[int]]:
        return self.rows[index]


class CausalLmCollator:
    """Right-pad inputs while masking prompt and padding labels."""

    def __init__(self, torch_module: Any, pad_token_id: int) -> None:
        self.torch = torch_module
        self.pad_token_id = pad_token_id

    def __call__(self, features: list[dict[str, list[int]]]) -> dict[str, Any]:
        longest = max(len(feature["input_ids"]) for feature in features)
        batch_size = len(features)
        input_ids = self.torch.full(
            (batch_size, longest),
            self.pad_token_id,
            dtype=self.torch.long,
        )
        attention_mask = self.torch.zeros((batch_size, longest), dtype=self.torch.long)
        labels = self.torch.full((batch_size, longest), -100, dtype=self.torch.long)
        for index, feature in enumerate(features):
            length = len(feature["input_ids"])
            input_ids[index, :length] = self.torch.tensor(feature["input_ids"])
            attention_mask[index, :length] = 1
            labels[index, :length] = self.torch.tensor(feature["labels"])
        return {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "labels": labels,
        }


def select_device_and_dtype(
    torch_module: Any,
    *,
    mps_dtype: str = "float32",
) -> tuple[str, Any, bool, bool]:
    if torch_module.cuda.is_available():
        bf16 = bool(torch_module.cuda.is_bf16_supported())
        return (
            "cuda",
            torch_module.bfloat16 if bf16 else torch_module.float16,
            not bf16,
            bf16,
        )
    if torch_module.backends.mps.is_available():
        dtype = (
            torch_module.bfloat16
            if mps_dtype == "bfloat16"
            else torch_module.float32
        )
        # The model itself may use bfloat16 on MPS, but Trainer mixed-precision
        # flags stay disabled because Accelerate treats them as CUDA controls.
        return "mps", dtype, False, False
    return "cpu", torch_module.float32, False, False


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tokenizer_artifact_paths(model_dir: Path) -> list[Path]:
    """Return the local tokenizer artifacts accepted by Transformers."""

    return [
        path
        for path in (
            model_dir / "tokenizer.json",
            model_dir / "tokenizer.model",
        )
        if path.is_file()
    ]


def validate_initial_adapter(
    method: str,
    initial_adapter: Path | None,
) -> Path | None:
    """Validate an optional trainable PEFT adapter used as the starting point."""

    if initial_adapter is None:
        return None
    if method != "lora":
        raise SystemExit("--initial-adapter is supported only with --method=lora")
    resolved = initial_adapter.resolve()
    if not resolved.is_dir():
        raise SystemExit(f"Initial adapter directory not found: {resolved}")
    for required in ("adapter_config.json", "adapter_model.safetensors"):
        if not (resolved / required).is_file():
            raise SystemExit(f"Initial adapter is missing {required}: {resolved}")
    return resolved


def validate_lora_target_profile(
    method: str,
    profile: str,
) -> tuple[str, ...]:
    """Return a fixed LoRA target profile after validating the method."""

    try:
        targets = LORA_TARGET_PROFILES[profile]
    except KeyError as error:
        raise ValueError(f"Unsupported LoRA target profile: {profile}") from error
    if method != "lora" and profile != "attention":
        raise SystemExit(
            "--lora-target-profile is supported only with --method=lora"
        )
    return targets


def validate_loaded_adapter_contract(
    configs: Mapping[str, Any],
    *,
    expected_rank: int,
    expected_targets: tuple[str, ...],
) -> None:
    """Require a continued adapter to match the requested LoRA contract."""

    loaded_ranks = {config.r for config in configs.values()}
    if loaded_ranks != {expected_rank}:
        raise SystemExit(
            "Initial adapter rank does not match --lora-rank: "
            f"{sorted(loaded_ranks)} != [{expected_rank}]"
        )
    expected_target_set = frozenset(expected_targets)
    loaded_target_sets = {
        frozenset(
            (config.target_modules,)
            if isinstance(config.target_modules, str)
            else config.target_modules
        )
        for config in configs.values()
    }
    if loaded_target_sets != {expected_target_set}:
        raise SystemExit(
            "Initial adapter target modules do not match "
            "--lora-target-profile"
        )


def configure_mps_allocator() -> None:
    """Bound unified-memory use unless the caller explicitly overrides it."""
    os.environ.setdefault("PYTORCH_MPS_HIGH_WATERMARK_RATIO", "1.1")
    os.environ.setdefault("PYTORCH_MPS_LOW_WATERMARK_RATIO", "0.9")


def trainer_evaluation_enabled(
    *,
    smoke_test: bool,
    skip_trainer_evaluation: bool,
) -> bool:
    """Return whether Trainer should run its memory-heavy loss evaluation."""

    return not smoke_test and not skip_trainer_evaluation


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-model",
        type=Path,
        required=True,
        help="Local google/gemma-3-270m-it or google/gemma-3-1b-it directory",
    )
    parser.add_argument(
        "--base-revision",
        required=True,
        help="Exact upstream commit/revision accepted and downloaded by the user",
    )
    parser.add_argument("--dataset-dir", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--method", choices=("lora", "full"), default="lora")
    parser.add_argument("--epochs", type=float, default=8.0)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=8)
    parser.add_argument("--learning-rate", type=float)
    parser.add_argument("--max-length", type=int, default=1_024)
    parser.add_argument(
        "--loss-scope",
        choices=("full", "intent"),
        default="full",
        help=(
            "Train the full assistant JSON or only its intent-label tokens; "
            "intent is for bounded follow-up adaptation of a format-stable model"
        ),
    )
    parser.add_argument("--lora-rank", type=int, default=16)
    parser.add_argument(
        "--lora-target-profile",
        choices=tuple(LORA_TARGET_PROFILES),
        default="attention",
    )
    parser.add_argument("--save-total-limit", type=int, default=4)
    parser.add_argument(
        "--checkpoint-steps",
        type=int,
        default=0,
        help="Save every N optimizer steps; zero saves at each epoch",
    )
    parser.add_argument(
        "--mps-dtype",
        choices=("float32", "bfloat16"),
        default="float32",
        help="Model dtype on Apple MPS; ignored on CUDA and CPU",
    )
    parser.add_argument(
        "--max-steps",
        type=int,
        default=-1,
        help="Override epoch length; use 1 for a local smoke test",
    )
    parser.add_argument(
        "--smoke-test",
        action="store_true",
        help="Run bounded training without evaluation, checkpoints, or final artifacts",
    )
    parser.add_argument(
        "--skip-trainer-evaluation",
        action="store_true",
        help=(
            "Skip Trainer loss evaluation on memory-limited hosts; run evaluate.py "
            "against every saved epoch checkpoint before selecting a model"
        ),
    )
    parser.add_argument("--gradient-checkpointing", action="store_true")
    parser.add_argument(
        "--initial-adapter",
        type=Path,
        help=(
            "Existing local PEFT adapter to continue with a fresh optimizer "
            "and scheduler; valid only for --method=lora"
        ),
    )
    parser.add_argument("--resume-from-checkpoint", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.save_total_limit < 1:
        raise SystemExit("--save-total-limit must be at least 1")
    if args.checkpoint_steps < 0:
        raise SystemExit("--checkpoint-steps cannot be negative")
    if args.max_steps == 0 or args.max_steps < -1:
        raise SystemExit("--max-steps must be -1 or a positive integer")
    if args.smoke_test and args.max_steps < 1:
        raise SystemExit("--smoke-test requires a positive --max-steps")
    base_model = args.base_model.resolve()
    dataset_dir = args.dataset_dir.resolve()
    output_dir = args.output_dir.resolve()
    lora_target_modules = validate_lora_target_profile(
        args.method,
        args.lora_target_profile,
    )
    initial_adapter = validate_initial_adapter(
        args.method,
        args.initial_adapter,
    )
    if not base_model.is_dir():
        raise SystemExit(f"Base model directory not found: {base_model}")
    if not (base_model / "config.json").is_file():
        raise SystemExit(f"Base model is missing config.json: {base_model}")
    tokenizer_files = tokenizer_artifact_paths(base_model)
    if not tokenizer_files:
        raise SystemExit(
            "Base model is missing tokenizer.json or tokenizer.model: "
            f"{base_model}"
        )
    base_weight_files = sorted(base_model.glob("*.safetensors"))
    if not base_weight_files:
        raise SystemExit(f"Base model has no safetensors weights: {base_model}")
    for split in ("train", "validation"):
        if not (dataset_dir / f"{split}.jsonl").is_file():
            raise SystemExit(
                f"Prepared dataset missing. Run prepare_dataset.py first: {dataset_dir}"
            )

    configure_mps_allocator()
    import torch
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        Trainer,
        TrainerCallback,
        TrainingArguments,
        set_seed,
    )

    random.seed(SEED)
    set_seed(SEED)
    device, dtype, use_fp16, use_bf16 = select_device_and_dtype(
        torch,
        mps_dtype=args.mps_dtype,
    )
    print(f"Training device={device}, dtype={dtype}, method={args.method}")

    tokenizer = AutoTokenizer.from_pretrained(
        str(base_model),
        local_files_only=True,
    )
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        str(base_model),
        local_files_only=True,
        dtype=dtype,
        low_cpu_mem_usage=True,
    )
    model.config.use_cache = False
    if args.gradient_checkpointing:
        model.gradient_checkpointing_enable()

    if args.method == "lora":
        if initial_adapter is not None:
            from peft import PeftModel

            model = PeftModel.from_pretrained(
                model,
                str(initial_adapter),
                is_trainable=True,
                local_files_only=True,
            )
            validate_loaded_adapter_contract(
                model.peft_config,
                expected_rank=args.lora_rank,
                expected_targets=lora_target_modules,
            )
        else:
            from peft import LoraConfig, get_peft_model

            model = get_peft_model(
                model,
                LoraConfig(
                    r=args.lora_rank,
                    lora_alpha=args.lora_rank * 2,
                    lora_dropout=0.05,
                    bias="none",
                    task_type="CAUSAL_LM",
                    target_modules=list(lora_target_modules),
                ),
            )
        model.print_trainable_parameters()

    train_rows = encode_rows(
        load_rows(dataset_dir / "train.jsonl"),
        tokenizer,
        args.max_length,
        loss_scope=args.loss_scope,
    )
    validation_rows = encode_rows(
        load_rows(dataset_dir / "validation.jsonl"),
        tokenizer,
        args.max_length,
        loss_scope=args.loss_scope,
    )
    checkpoints_dir = output_dir / "checkpoints"
    checkpoints_dir.mkdir(parents=True, exist_ok=True)
    learning_rate = args.learning_rate or (2e-4 if args.method == "lora" else 5e-5)
    run_trainer_evaluation = trainer_evaluation_enabled(
        smoke_test=args.smoke_test,
        skip_trainer_evaluation=args.skip_trainer_evaluation,
    )
    training_args = TrainingArguments(
        output_dir=str(checkpoints_dir),
        num_train_epochs=args.epochs,
        max_steps=args.max_steps,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        learning_rate=learning_rate,
        warmup_ratio=0.1,
        weight_decay=0.01,
        logging_steps=1,
        eval_strategy="epoch" if run_trainer_evaluation else "no",
        save_strategy=(
            "no"
            if args.smoke_test
            else ("steps" if args.checkpoint_steps else "epoch")
        ),
        save_steps=args.checkpoint_steps or 500,
        save_total_limit=args.save_total_limit,
        load_best_model_at_end=run_trainer_evaluation,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        fp16=use_fp16,
        bf16=use_bf16,
        gradient_checkpointing=args.gradient_checkpointing,
        report_to="none",
        seed=SEED,
        data_seed=SEED,
        remove_unused_columns=False,
        dataloader_num_workers=0,
        dataloader_pin_memory=False,
    )

    class MpsCacheCallback(TrainerCallback):
        def on_step_end(self, args: Any, state: Any, control: Any, **kwargs: Any) -> Any:
            del args, state, kwargs
            torch.mps.empty_cache()
            return control

        def on_evaluate(
            self,
            args: Any,
            state: Any,
            control: Any,
            **kwargs: Any,
        ) -> Any:
            del args, state, kwargs
            torch.mps.empty_cache()
            return control

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=TokenizedDataset(train_rows),
        eval_dataset=TokenizedDataset(validation_rows),
        data_collator=CausalLmCollator(torch, tokenizer.pad_token_id),
        callbacks=[MpsCacheCallback()] if device == "mps" else None,
    )
    trainer.train(
        resume_from_checkpoint=(
            str(args.resume_from_checkpoint.resolve())
            if args.resume_from_checkpoint is not None
            else None
        )
    )
    if args.smoke_test:
        if device == "mps":
            current_gib = torch.mps.current_allocated_memory() / (1024**3)
            driver_gib = torch.mps.driver_allocated_memory() / (1024**3)
            recommended_gib = torch.mps.recommended_max_memory() / (1024**3)
            print(
                "MPS memory after cache release: "
                f"tensors={current_gib:.2f} GiB, "
                f"driver={driver_gib:.2f} GiB, "
                f"recommended={recommended_gib:.2f} GiB"
            )
        print(f"Smoke test completed after {args.max_steps} training step(s)")
        return

    trained_model = trainer.model
    if args.method == "lora":
        adapter_dir = output_dir / "adapter"
        trained_model.save_pretrained(str(adapter_dir), safe_serialization=True)
        merged_model = trained_model.merge_and_unload(safe_merge=True)
    else:
        merged_model = trained_model
    merged_model.config.use_cache = True
    merged_dir = output_dir / "merged"
    merged_model.save_pretrained(
        str(merged_dir),
        safe_serialization=True,
        max_shard_size="2GB",
    )
    tokenizer.save_pretrained(str(merged_dir))

    metadata = {
        "base_model_directory_name": base_model.name,
        "base_model_revision": args.base_revision,
        "base_model_file_sha256": {
            path.name: file_sha256(path)
            for path in [
                base_model / "config.json",
                *tokenizer_files,
                *base_weight_files,
            ]
        },
        "checkpoint_steps": args.checkpoint_steps or None,
        "dataset_manifest_sha256": file_sha256(dataset_dir / "manifest.json"),
        "dependency_versions": {
            dependency: importlib.metadata.version(dependency)
            for dependency in (
                "accelerate",
                "peft",
                "safetensors",
                "sentencepiece",
                "torch",
                "transformers",
            )
        },
        "epochs": args.epochs,
        "learning_rate": learning_rate,
        "loss_scope": args.loss_scope,
        "lora_rank": args.lora_rank if args.method == "lora" else None,
        "lora_target_modules": (
            list(lora_target_modules) if args.method == "lora" else None
        ),
        "lora_target_profile": (
            args.lora_target_profile if args.method == "lora" else None
        ),
        "initial_adapter": (
            {
                "directory_name": initial_adapter.name,
                "file_sha256": {
                    name: file_sha256(initial_adapter / name)
                    for name in (
                        "adapter_config.json",
                        "adapter_model.safetensors",
                    )
                },
            }
            if initial_adapter is not None
            else None
        ),
        "method": args.method,
        "seed": SEED,
        "trainer_evaluation_enabled": run_trainer_evaluation,
        "training_device": device,
        "training_dtype": str(dtype),
        "mps_allocator_high_watermark_ratio": os.environ[
            "PYTORCH_MPS_HIGH_WATERMARK_RATIO"
        ],
        "mps_allocator_low_watermark_ratio": os.environ[
            "PYTORCH_MPS_LOW_WATERMARK_RATIO"
        ],
    }
    (output_dir / "training_metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"Merged Hugging Face model written to {merged_dir}")


if __name__ == "__main__":
    main()
