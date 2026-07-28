#!/usr/bin/env python3
"""Train the seven-way AlarmControl semantic classifier on CPU only.

The script never resolves a model name or downloads artifacts. ``--base-model``
must point to a complete local Hugging Face-compatible KoELECTRA directory.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import resource
import shutil
import signal
import sys
import tempfile
import threading
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Sequence

from semantic_contract import (
    ContractError,
    LABELS,
    MAX_SEQUENCE_LENGTH,
    RUNTIME_TEXT_FORMAT_VERSION,
    notification_text,
    sha256_file,
    validated_training_rows,
)

INTENTS = LABELS
LABEL_TO_ID = {label: index for index, label in enumerate(INTENTS)}
ID_TO_LABEL = {index: label for label, index in LABEL_TO_ID.items()}
TEXT_FORMAT_VERSION = RUNTIME_TEXT_FORMAT_VERSION
TRAINING_MANIFEST_VERSION = "koelectra-training-manifest-v1"
MAX_TOKENS = MAX_SEQUENCE_LENGTH
MAX_THREADS = 2
PROGRESS_BATCH_INTERVAL = 100
GIB = 1024**3
DEFAULT_MAX_RSS_BYTES = 4 * GIB
DEFAULT_SEED = 20_260_728
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


class TrainingInputError(ValueError):
    """Raised when local model or dataset inputs violate the training contract."""


class ResourceLimitExceeded(RuntimeError):
    """Raised when process RSS reaches its configured ceiling."""


@dataclass(frozen=True)
class TrainingExample:
    """One validated train or validation notification."""

    example_id: str
    split: str
    intent: str
    title: str
    body: str

    @property
    def label_id(self) -> int:
        return LABEL_TO_ID[self.intent]

    @property
    def text(self) -> str:
        return format_notification(self.title, self.body)


@dataclass(frozen=True)
class TrainingOptions:
    """Validated command-line options used by the training loop."""

    base_model: Path
    dataset: Path
    output: Path
    epochs: int
    batch_size: int
    learning_rate: float
    seed: int
    max_rss_bytes: int


class TerminationRequest:
    """Signal-safe flag checked at optimizer boundaries."""

    def __init__(self) -> None:
        self._event = threading.Event()
        self.signal_number: int | None = None

    def request(self, signal_number: int, _frame: object) -> None:
        self.signal_number = signal_number
        self._event.set()

    @property
    def requested(self) -> bool:
        return self._event.is_set()


def format_notification(title: str, body: str) -> str:
    """Mirror the runtime's literal-space title/body join."""

    return notification_text(title, body)


def _maximum_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def enforce_memory_ceiling(limit_bytes: int, context: str) -> int:
    """Fail when peak resident memory reaches the configured byte ceiling."""

    current = _maximum_rss_bytes()
    if current >= limit_bytes:
        raise ResourceLimitExceeded(
            f"{context}: RSS reached {current} bytes; limit is {limit_bytes}"
        )
    return current


def _require_local_base_model(path: Path) -> Path:
    expanded = path.expanduser()
    if not expanded.is_dir():
        raise TrainingInputError(
            f"--base-model must be an existing local directory: {expanded}"
        )
    resolved = expanded.resolve()
    if not (resolved / "config.json").is_file():
        raise TrainingInputError(
            f"local base model is missing config.json: {resolved}"
        )
    return resolved


def load_dataset(path: Path) -> dict[str, list[TrainingExample]]:
    """Load balanced train/validation rows from the deterministic dataset."""

    if not path.is_file():
        raise TrainingInputError(f"dataset does not exist: {path}")
    try:
        rows = validated_training_rows(path)
    except (ContractError, OSError) as error:
        raise TrainingInputError(str(error)) from error
    split_examples = {"train": [], "validation": []}
    for row in rows:
        split = row["split"]
        if split == "test":
            continue
        intent = row["intent"]
        example_id = row["id"]
        title = row["title"]
        body = row["body"]
        split_examples[split].append(
            TrainingExample(
                example_id=example_id,
                split=split,
                intent=intent,
                title=title,
                body=body,
            )
        )

    for split, examples in split_examples.items():
        if not examples:
            raise TrainingInputError(f"dataset has no {split} rows")
        counts = Counter(example.intent for example in examples)
        expected = set(INTENTS)
        if set(counts) != expected or len(set(counts.values())) != 1:
            raise TrainingInputError(
                f"{split} must be class-balanced across {list(INTENTS)}; "
                f"counts={dict(sorted(counts.items()))}"
            )
    return split_examples


def configure_offline_cpu_environment() -> None:
    """Set fail-closed offline and two-thread process defaults before ML imports."""

    for name, value in OFFLINE_CPU_ENVIRONMENT.items():
        os.environ[name] = value


def seed_everything(torch: Any, seed: int) -> None:
    """Configure deterministic Python and CPU PyTorch execution."""

    random.seed(seed)
    torch.manual_seed(seed)
    torch.set_num_threads(MAX_THREADS)
    try:
        torch.set_num_interop_threads(MAX_THREADS)
    except RuntimeError:
        # PyTorch permits setting this only before its first parallel operation.
        if torch.get_num_interop_threads() > MAX_THREADS:
            raise
    torch.use_deterministic_algorithms(True)


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
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def _replace_directory(
    target: Path,
    writer: Callable[[Path], None],
) -> None:
    """Replace a model bundle only after its new staging directory is complete."""

    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f".{target.name}.staging-", dir=target.parent)
    )
    backup = target.with_name(f".{target.name}.previous")
    try:
        writer(staging)
        if backup.exists():
            shutil.rmtree(backup)
        if target.exists():
            target.rename(backup)
        staging.rename(target)
        if backup.exists():
            shutil.rmtree(backup)
    except BaseException:
        if not target.exists() and backup.exists():
            backup.rename(target)
        if staging.exists():
            shutil.rmtree(staging)
        raise


def _counts_by_intent(
    examples: Sequence[TrainingExample],
) -> dict[str, int]:
    counts = Counter(example.intent for example in examples)
    return {label: counts[label] for label in INTENTS}


def _base_vocab_path(base_model: Path) -> Path | None:
    """Return the regular WordPiece vocabulary used by deployable models."""

    vocab = base_model / "vocab.txt"
    if not vocab.exists():
        return None
    if vocab.is_symlink() or not vocab.is_file():
        raise TrainingInputError(
            f"base model vocab.txt must be a regular file: {vocab}"
        )
    if vocab.stat().st_size <= 0:
        raise TrainingInputError(f"base model vocab.txt must not be empty: {vocab}")
    return vocab


def _copy_base_vocab(base_model: Path, target: Path) -> bool:
    """Preserve WordPiece vocabulary when a fast tokenizer omits vocab.txt."""

    source = _base_vocab_path(base_model)
    if source is None:
        return False
    shutil.copyfile(source, target / "vocab.txt")
    return True


def initial_manifest(
    options: TrainingOptions,
    examples: dict[str, list[TrainingExample]],
    torch_version: str,
    transformers_version: str,
) -> dict[str, Any]:
    """Build the machine-readable reproducibility manifest."""

    return {
        "schema_version": TRAINING_MANIFEST_VERSION,
        "status": "running",
        "device": "cpu",
        "max_threads": MAX_THREADS,
        "max_tokens": MAX_TOKENS,
        "text_format": {
            "version": TEXT_FORMAT_VERSION,
            "template": "{title} {body}",
            "normalization": (
                "join nonempty title/body with one literal ASCII space, "
                "strip outer whitespace, then normalize to NFC"
            ),
        },
        "labels": list(INTENTS),
        "label_to_id": LABEL_TO_ID,
        "seed": options.seed,
        "hyperparameters": {
            "epochs": options.epochs,
            "batch_size": options.batch_size,
            "learning_rate": options.learning_rate,
        },
        "resource_limits": {
            "max_rss_bytes": options.max_rss_bytes,
        },
        "inputs": {
            "base_model": str(options.base_model),
            "base_vocab_sha256": (
                sha256_file(vocab)
                if (vocab := _base_vocab_path(options.base_model)) is not None
                else None
            ),
            "dataset": str(options.dataset),
            "dataset_sha256": sha256_file(options.dataset),
            "rows_by_split": {
                split: len(rows) for split, rows in examples.items()
            },
            "rows_by_split_and_intent": {
                split: _counts_by_intent(rows)
                for split, rows in examples.items()
            },
        },
        "runtime": {
            "python": sys.version.split()[0],
            "torch": torch_version,
            "transformers": transformers_version,
        },
        "epochs": [],
        "best": None,
    }


def _validate_options(namespace: argparse.Namespace) -> TrainingOptions:
    if namespace.epochs <= 0:
        raise TrainingInputError("--epochs must be positive")
    if namespace.batch_size <= 0:
        raise TrainingInputError("--batch-size must be positive")
    if namespace.learning_rate <= 0:
        raise TrainingInputError("--learning-rate must be positive")
    if namespace.seed < 0:
        raise TrainingInputError("--seed must be non-negative")
    if namespace.max_rss_bytes <= 0:
        raise TrainingInputError("--max-rss-bytes must be positive")
    dataset = namespace.dataset.expanduser()
    if not dataset.is_file():
        raise TrainingInputError(f"dataset does not exist: {dataset}")
    output = namespace.output.expanduser().resolve()
    return TrainingOptions(
        base_model=_require_local_base_model(namespace.base_model),
        dataset=dataset.resolve(),
        output=output,
        epochs=namespace.epochs,
        batch_size=namespace.batch_size,
        learning_rate=namespace.learning_rate,
        seed=namespace.seed,
        max_rss_bytes=namespace.max_rss_bytes,
    )


def parse_args(argv: Sequence[str] | None = None) -> TrainingOptions:
    parser = argparse.ArgumentParser(
        description="Train AlarmControl's seven-way KoELECTRA classifier locally."
    )
    parser.add_argument(
        "--base-model",
        type=Path,
        required=True,
        help="Existing local Hugging Face KoELECTRA model directory.",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path(__file__).resolve().parent
        / "artifacts"
        / "dataset-v6"
        / "dataset.jsonl",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Output directory for best/checkpoint bundles and manifest.",
    )
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument(
        "--max-rss-bytes",
        type=int,
        default=DEFAULT_MAX_RSS_BYTES,
        help="Hard peak-RSS ceiling in bytes (default: 4 GiB).",
    )
    return _validate_options(parser.parse_args(argv))


def run_training(options: TrainingOptions) -> int:
    """Run the manual CPU training loop and return a process exit code."""

    configure_offline_cpu_environment()
    try:
        import torch
        import transformers
        from torch.utils.data import DataLoader, Dataset
        from transformers import (
            AutoModelForSequenceClassification,
            AutoTokenizer,
        )
    except ImportError as error:
        raise TrainingInputError(
            "local training requires preinstalled torch and transformers"
        ) from error

    seed_everything(torch, options.seed)
    examples = load_dataset(options.dataset)
    enforce_memory_ceiling(options.max_rss_bytes, "before model load")
    options.output.mkdir(parents=True, exist_ok=True)
    manifest_path = options.output / "training_manifest.json"
    manifest = initial_manifest(
        options,
        examples,
        torch.__version__,
        transformers.__version__,
    )
    _atomic_json(manifest_path, manifest)

    tokenizer = AutoTokenizer.from_pretrained(
        str(options.base_model),
        local_files_only=True,
        trust_remote_code=False,
    )
    model = AutoModelForSequenceClassification.from_pretrained(
        str(options.base_model),
        local_files_only=True,
        trust_remote_code=False,
        num_labels=len(INTENTS),
        id2label=ID_TO_LABEL,
        label2id=LABEL_TO_ID,
        ignore_mismatched_sizes=True,
    )
    model.config.id2label = ID_TO_LABEL
    model.config.label2id = LABEL_TO_ID
    model.config.problem_type = "single_label_classification"
    model.to(torch.device("cpu"))
    enforce_memory_ceiling(options.max_rss_bytes, "after model load")

    class ExampleDataset(Dataset):
        def __init__(self, rows: Sequence[TrainingExample]) -> None:
            self.rows = rows

        def __len__(self) -> int:
            return len(self.rows)

        def __getitem__(self, index: int) -> TrainingExample:
            return self.rows[index]

    def collate(rows: Sequence[TrainingExample]) -> dict[str, Any]:
        encoded = tokenizer(
            [row.text for row in rows],
            padding=True,
            truncation=True,
            max_length=MAX_TOKENS,
            return_tensors="pt",
        )
        encoded["labels"] = torch.tensor(
            [row.label_id for row in rows],
            dtype=torch.long,
        )
        return encoded

    validation_loader = DataLoader(
        ExampleDataset(examples["validation"]),
        batch_size=options.batch_size,
        shuffle=False,
        num_workers=0,
        pin_memory=False,
        collate_fn=collate,
    )
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=options.learning_rate,
    )
    termination = TerminationRequest()
    previous_sigterm = signal.signal(signal.SIGTERM, termination.request)
    previous_sigint = signal.signal(signal.SIGINT, termination.request)
    best_loss = float("inf")
    best_epoch: int | None = None

    def save_bundle(
        target: Path,
        epoch: int,
        reason: str,
        metrics: dict[str, float] | None,
    ) -> None:
        def writer(directory: Path) -> None:
            model.save_pretrained(
                directory,
                safe_serialization=True,
            )
            tokenizer.save_pretrained(directory)
            _copy_base_vocab(options.base_model, directory)
            torch.save(
                {
                    "epoch": epoch,
                    "optimizer_state_dict": optimizer.state_dict(),
                    "seed": options.seed,
                },
                directory / "optimizer.pt",
            )
            _atomic_json(
                directory / "checkpoint.json",
                {
                    "epoch": epoch,
                    "reason": reason,
                    "metrics": metrics,
                },
            )

        _replace_directory(target, writer)

    try:
        for epoch_index in range(options.epochs):
            epoch = epoch_index + 1
            generator = torch.Generator()
            generator.manual_seed(options.seed + epoch_index)
            train_loader = DataLoader(
                ExampleDataset(examples["train"]),
                batch_size=options.batch_size,
                shuffle=True,
                generator=generator,
                num_workers=0,
                pin_memory=False,
                collate_fn=collate,
            )
            model.train()
            train_loss_sum = 0.0
            train_count = 0
            train_batch_count = len(train_loader)
            for batch_index, batch in enumerate(train_loader, start=1):
                enforce_memory_ceiling(
                    options.max_rss_bytes,
                    f"epoch {epoch} before training batch",
                )
                optimizer.zero_grad(set_to_none=True)
                outputs = model(**batch)
                loss = outputs.loss
                loss.backward()
                optimizer.step()
                batch_size = int(batch["labels"].shape[0])
                train_loss_sum += float(loss.detach()) * batch_size
                train_count += batch_size
                peak_rss_bytes = enforce_memory_ceiling(
                    options.max_rss_bytes,
                    f"epoch {epoch} after training batch",
                )
                if (
                    batch_index % PROGRESS_BATCH_INTERVAL == 0
                    or batch_index == train_batch_count
                ):
                    print(
                        json.dumps(
                            {
                                "completed_batches": batch_index,
                                "epoch": epoch,
                                "peak_rss_bytes": peak_rss_bytes,
                                "phase": "train",
                                "total_batches": train_batch_count,
                            },
                            sort_keys=True,
                        ),
                        flush=True,
                    )
                if termination.requested:
                    save_bundle(
                        options.output / "checkpoint",
                        epoch,
                        "signal",
                        None,
                    )
                    manifest["status"] = "terminated"
                    manifest["termination_signal"] = (
                        termination.signal_number
                    )
                    manifest["completed_at_utc"] = datetime.now(
                        timezone.utc
                    ).isoformat()
                    _atomic_json(manifest_path, manifest)
                    return 128 + (termination.signal_number or 0)

            model.eval()
            validation_loss_sum = 0.0
            validation_correct = 0
            validation_count = 0
            with torch.inference_mode():
                for batch in validation_loader:
                    enforce_memory_ceiling(
                        options.max_rss_bytes,
                        f"epoch {epoch} before validation batch",
                    )
                    outputs = model(**batch)
                    batch_size = int(batch["labels"].shape[0])
                    validation_loss_sum += (
                        float(outputs.loss.detach()) * batch_size
                    )
                    predictions = outputs.logits.argmax(dim=-1)
                    validation_correct += int(
                        (predictions == batch["labels"]).sum()
                    )
                    validation_count += batch_size
                    enforce_memory_ceiling(
                        options.max_rss_bytes,
                        f"epoch {epoch} after validation batch",
                    )

            metrics = {
                "epoch": epoch,
                "train_loss": train_loss_sum / train_count,
                "validation_loss": (
                    validation_loss_sum / validation_count
                ),
                "validation_accuracy": (
                    validation_correct / validation_count
                ),
            }
            manifest["epochs"].append(metrics)
            if termination.requested:
                save_bundle(
                    options.output / "checkpoint",
                    epoch,
                    "signal",
                    metrics,
                )
                manifest["status"] = "terminated"
                manifest["termination_signal"] = termination.signal_number
                manifest["completed_at_utc"] = datetime.now(
                    timezone.utc
                ).isoformat()
                _atomic_json(manifest_path, manifest)
                return 128 + (termination.signal_number or 0)
            save_bundle(
                options.output / "checkpoint",
                epoch,
                "epoch-complete",
                metrics,
            )
            if metrics["validation_loss"] < best_loss:
                best_loss = metrics["validation_loss"]
                best_epoch = epoch
                save_bundle(
                    options.output / "best",
                    epoch,
                    "best-validation-loss",
                    metrics,
                )
                manifest["best"] = {
                    "epoch": epoch,
                    "path": "best",
                    "validation_loss": best_loss,
                    "validation_accuracy": metrics[
                        "validation_accuracy"
                    ],
                }
            _atomic_json(manifest_path, manifest)
            print(json.dumps(metrics, sort_keys=True), flush=True)

        if best_epoch is None:
            raise RuntimeError("training completed without a best checkpoint")
        manifest["status"] = "completed"
        manifest["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        _atomic_json(manifest_path, manifest)
        return 0
    finally:
        signal.signal(signal.SIGTERM, previous_sigterm)
        signal.signal(signal.SIGINT, previous_sigint)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        options = parse_args(argv)
        return run_training(options)
    except TrainingInputError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
