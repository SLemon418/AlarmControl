#!/usr/bin/env python3
"""Train and convert the CPU-friendly KoELECTRA-embedding Conv1D fallback."""

from __future__ import annotations

import argparse
import json
import os
import platform
import resource
import shutil
import signal
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence

from semantic_contract import (
    LABELS,
    MAX_SEQUENCE_LENGTH,
    RUNTIME_TEXT_FORMAT_VERSION,
    WordPieceTokenizer,
    sha256_file,
    validated_training_rows,
)

GIB = 1024**3
DEFAULT_MAX_RSS_BYTES = 4 * GIB
DEFAULT_SEED = 20260728
BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DATASET = BASE_DIR / "artifacts" / "dataset-v6" / "dataset.jsonl"
DEFAULT_BASE_MODEL = BASE_DIR / "artifacts" / "base-model" / "koelectra-small-v3"
DEFAULT_OUTPUT = BASE_DIR / "artifacts" / "fallback-conv1d"


class ResourceLimitExceeded(RuntimeError):
    """Raised when the process reaches its configured resident-memory ceiling."""


def _text_format_manifest() -> dict[str, str]:
    return {
        "version": RUNTIME_TEXT_FORMAT_VERSION,
        "template": "{title} {body}",
        "normalization": (
            "join nonempty title/body with one literal ASCII space, "
            "strip outer whitespace, then normalize to NFC"
        ),
    }


def _configure_process_environment() -> None:
    os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")
    os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "0")
    os.environ.setdefault("OMP_NUM_THREADS", "2")
    os.environ.setdefault("MKL_NUM_THREADS", "2")
    os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
    os.environ.setdefault("VECLIB_MAXIMUM_THREADS", "2")
    os.environ.setdefault("NUMEXPR_NUM_THREADS", "2")
    os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")
    os.environ.setdefault("TF_NUM_INTEROP_THREADS", "1")
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")


def _maximum_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


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
        os.replace(temporary_name, path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise


def _load_pretrained_embeddings(weights_path: Path, expected_shape: tuple[int, int]):
    import torch

    state = torch.load(weights_path, map_location="cpu", weights_only=True)
    key = "electra.embeddings.word_embeddings.weight"
    tensor = state.get(key)
    if tensor is None or tuple(tensor.shape) != expected_shape:
        actual = None if tensor is None else tuple(tensor.shape)
        raise ValueError(
            f"{key} must have shape {expected_shape}, found {actual}"
        )
    return tensor.detach().cpu().numpy()


def _encode_split(records, split: str, tokenizer: WordPieceTokenizer):
    import numpy as np

    selected = [record for record in records if record["split"] == split]
    ids, masks, _ = tokenizer.encode_records(selected)
    labels = [LABELS.index(record["intent"]) for record in selected]
    return (
        {
            "input_ids": np.asarray(ids, dtype=np.int32),
            "attention_mask": np.asarray(masks, dtype=np.int32),
        },
        np.asarray(labels, dtype=np.int32),
        selected,
    )


def _build_model(tf, embedding_weights, max_sequence_length: int):
    vocabulary_size, embedding_size = embedding_weights.shape
    input_ids = tf.keras.Input(
        shape=(max_sequence_length,),
        dtype=tf.int32,
        name="input_ids",
    )
    attention_mask = tf.keras.Input(
        shape=(max_sequence_length,),
        dtype=tf.int32,
        name="attention_mask",
    )
    embeddings = tf.keras.layers.Embedding(
        vocabulary_size,
        embedding_size,
        embeddings_initializer=tf.keras.initializers.Constant(embedding_weights),
        trainable=True,
        name="word_embeddings",
    )(input_ids)
    float_mask = tf.keras.layers.Lambda(
        lambda value: tf.cast(tf.expand_dims(value, axis=-1), tf.float32),
        name="expand_attention_mask",
    )(attention_mask)
    masked_embeddings = tf.keras.layers.Multiply(name="mask_embeddings")(
        [embeddings, float_mask]
    )
    branches = []
    for kernel_size in (2, 3, 5):
        convolved = tf.keras.layers.Conv1D(
            filters=128,
            kernel_size=kernel_size,
            padding="same",
            activation="relu",
            name=f"conv_{kernel_size}",
        )(masked_embeddings)
        convolved = tf.keras.layers.Multiply(name=f"mask_conv_{kernel_size}")(
            [convolved, float_mask]
        )
        branches.append(
            tf.keras.layers.GlobalMaxPooling1D(name=f"pool_{kernel_size}")(convolved)
        )
    features = tf.keras.layers.Concatenate(name="pooled_features")(branches)
    features = tf.keras.layers.Dropout(0.15, name="feature_dropout")(features)
    features = tf.keras.layers.Dense(192, activation="relu", name="projection")(features)
    features = tf.keras.layers.Dropout(0.15, name="projection_dropout")(features)
    logits = tf.keras.layers.Dense(len(LABELS), name="logits")(features)
    return tf.keras.Model(
        inputs={
            "input_ids": input_ids,
            "attention_mask": attention_mask,
        },
        outputs=logits,
        name="alarmcontrol_semantic_conv1d",
    )


def _convert_to_dynamic_int8(tf, model, output_path: Path) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(
        prefix=".semantic-saved-model-",
        dir=output_path.parent,
    ) as temporary_directory:
        model.export(temporary_directory, verbose=False)
        converter = tf.lite.TFLiteConverter.from_saved_model(temporary_directory)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converted = converter.convert()
    output_path.write_bytes(converted)

    interpreter = tf.lite.Interpreter(model_path=str(output_path), num_threads=2)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    expected_shape = [1, MAX_SEQUENCE_LENGTH]
    if len(inputs) != 2:
        raise ValueError(f"converted model must have two inputs, found {len(inputs)}")
    for tensor in inputs:
        dtype_name = tensor["dtype"].__name__
        if dtype_name != "int32" or tensor["shape"].tolist() != expected_shape:
            raise ValueError(
                f"unexpected TFLite input {tensor['name']}: "
                f"{dtype_name} {tensor['shape'].tolist()}"
            )
    if len(outputs) != 1:
        raise ValueError(f"converted model must have one output, found {len(outputs)}")
    output = outputs[0]
    output_dtype_name = output["dtype"].__name__
    if output_dtype_name != "float32" or output["shape"].tolist() != [1, len(LABELS)]:
        raise ValueError(
            f"unexpected TFLite output {output['name']}: "
            f"{output_dtype_name} {output['shape'].tolist()}"
        )
    return {
        "inputs": [
            {
                "name": tensor["name"],
                "dtype": tensor["dtype"].__name__,
                "shape": tensor["shape"].tolist(),
            }
            for tensor in inputs
        ],
        "outputs": [
            {
                "name": output["name"],
                "dtype": output["dtype"].__name__,
                "shape": output["shape"].tolist(),
            }
        ],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--base-model", type=Path, default=DEFAULT_BASE_MODEL)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--epochs", type=int, default=18)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--max-rss-bytes", type=int, default=DEFAULT_MAX_RSS_BYTES)
    parser.add_argument(
        "--convert-only",
        action="store_true",
        help="load existing best.weights.h5 and skip the training loop",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    _configure_process_environment()
    arguments = build_parser().parse_args(argv)
    if arguments.epochs < 1 or arguments.batch_size < 1:
        raise ValueError("epochs and batch-size must be positive")
    if arguments.max_rss_bytes <= 0:
        raise ValueError("max-rss-bytes must be positive")

    import numpy as np
    import tensorflow as tf

    tf.config.set_visible_devices([], "GPU")
    tf.config.threading.set_intra_op_parallelism_threads(2)
    tf.config.threading.set_inter_op_parallelism_threads(1)
    tf.keras.utils.set_random_seed(arguments.seed)
    tf.config.experimental.enable_op_determinism()

    dataset_path = arguments.dataset.resolve(strict=True)
    base_model = arguments.base_model.resolve(strict=True)
    vocab_path = base_model / "vocab.txt"
    weights_path = base_model / "pytorch_model.bin"
    config_path = base_model / "config.json"
    for required in (vocab_path, weights_path, config_path):
        if not required.is_file():
            raise FileNotFoundError(required)

    tokenizer = WordPieceTokenizer.from_file(vocab_path)
    records = validated_training_rows(dataset_path)
    train_x, train_y, train_records = _encode_split(records, "train", tokenizer)
    validation_x, validation_y, validation_records = _encode_split(
        records, "validation", tokenizer
    )
    test_x, test_y, test_records = _encode_split(records, "test", tokenizer)
    if not train_records or not validation_records or not test_records:
        raise ValueError("dataset must contain train, validation, and test rows")

    embedding_weights = _load_pretrained_embeddings(
        weights_path,
        (len(tokenizer.vocabulary), 128),
    )
    model = _build_model(tf, embedding_weights, MAX_SEQUENCE_LENGTH)
    del embedding_weights
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=arguments.learning_rate),
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=[tf.keras.metrics.SparseCategoricalAccuracy(name="accuracy")],
    )

    output = arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    best_weights = output / "best.weights.h5"
    interrupted_weights = output / "interrupted.weights.h5"

    class MemoryCeiling(tf.keras.callbacks.Callback):
        def on_train_batch_end(self, batch, logs=None):
            if _maximum_rss_bytes() >= arguments.max_rss_bytes:
                self.model.stop_training = True
                raise ResourceLimitExceeded(
                    f"RSS reached {_maximum_rss_bytes()} bytes; "
                    f"limit is {arguments.max_rss_bytes}"
                )

    stop_requested = False

    def request_stop(signum, frame):
        del signum, frame
        nonlocal stop_requested
        stop_requested = True
        model.stop_training = True

    history_values: dict[str, list[float]] = {}
    if arguments.convert_only:
        if not best_weights.is_file():
            raise FileNotFoundError(
                f"--convert-only requires existing weights: {best_weights}"
            )
    else:
        previous_sigterm = signal.signal(signal.SIGTERM, request_stop)
        try:
            history = model.fit(
                train_x,
                train_y,
                validation_data=(validation_x, validation_y),
                epochs=arguments.epochs,
                batch_size=arguments.batch_size,
                shuffle=True,
                callbacks=[
                    MemoryCeiling(),
                    tf.keras.callbacks.ModelCheckpoint(
                        filepath=str(best_weights),
                        monitor="val_accuracy",
                        mode="max",
                        save_best_only=True,
                        save_weights_only=True,
                    ),
                    tf.keras.callbacks.EarlyStopping(
                        monitor="val_accuracy",
                        mode="max",
                        patience=4,
                        restore_best_weights=True,
                    ),
                    tf.keras.callbacks.ReduceLROnPlateau(
                        monitor="val_loss",
                        mode="min",
                        patience=2,
                        factor=0.5,
                        min_lr=1e-6,
                    ),
                ],
                verbose=2,
            )
            history_values = {
                name: [float(value) for value in values]
                for name, values in history.history.items()
            }
        finally:
            signal.signal(signal.SIGTERM, previous_sigterm)

    if stop_requested:
        model.save_weights(interrupted_weights)
        print(f"training interrupted; weights saved to {interrupted_weights}", file=sys.stderr)
        return 130
    if best_weights.is_file():
        model.load_weights(best_weights)

    validation_metrics = model.evaluate(
        validation_x,
        validation_y,
        batch_size=arguments.batch_size,
        return_dict=True,
        verbose=0,
    )
    test_metrics = model.evaluate(
        test_x,
        test_y,
        batch_size=arguments.batch_size,
        return_dict=True,
        verbose=0,
    )
    model_path = output / "semantic_notification_classifier.tflite"
    tensor_contract = _convert_to_dynamic_int8(tf, model, model_path)
    shutil.copyfile(vocab_path, output / "semantic_vocab.txt")
    (output / "semantic_labels.txt").write_text(
        "".join(f"{label}\n" for label in LABELS),
        encoding="utf-8",
    )

    config = json.loads(config_path.read_text(encoding="utf-8"))
    revision_file = base_model / "REVISION"
    manifest = {
        "schema_version": "alarmcontrol-semantic-model-v1",
        "architecture": "koelectra-embedding-conv1d",
        "quantization": "dynamic-range-int8",
        "seed": arguments.seed,
        "labels": list(LABELS),
        "max_sequence_length": MAX_SEQUENCE_LENGTH,
        "lowercase": False,
        "text_separator": "single-space",
        "text_format": _text_format_manifest(),
        "dataset": {
            "sha256": sha256_file(dataset_path),
            "rows": len(records),
            "train_rows": len(train_records),
            "validation_rows": len(validation_records),
            "test_rows": len(test_records),
        },
        "base_model": {
            "name_or_path": str(base_model),
            "revision": (
                revision_file.read_text(encoding="utf-8").strip()
                if revision_file.is_file()
                else None
            ),
            "config_sha256": sha256_file(config_path),
            "weights_sha256": sha256_file(weights_path),
            "vocab_sha256": sha256_file(vocab_path),
            "model_type": config.get("model_type"),
        },
        "training": {
            "conversion_only": arguments.convert_only,
            "epochs_requested": arguments.epochs,
            "epochs_completed": (
                None if arguments.convert_only else len(history_values.get("loss", []))
            ),
            "batch_size": arguments.batch_size,
            "learning_rate": arguments.learning_rate,
            "maximum_rss_bytes": _maximum_rss_bytes(),
            "rss_limit_bytes": arguments.max_rss_bytes,
            "history": history_values,
        },
        "keras_metrics": {
            "validation": {
                name: float(value) for name, value in validation_metrics.items()
            },
            "test": {name: float(value) for name, value in test_metrics.items()},
        },
        "artifact": {
            "file": model_path.name,
            "bytes": model_path.stat().st_size,
            "sha256": sha256_file(model_path),
            "tensor_contract": tensor_contract,
        },
        "runtime": {
            "tensorflow": tf.__version__,
            "keras": tf.keras.__version__,
            "python": platform.python_version(),
        },
    }
    _atomic_json(output / "training_manifest.json", manifest)
    print(json.dumps(manifest["artifact"], ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
