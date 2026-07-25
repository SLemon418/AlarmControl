"""Offline trainer for the bundled on-device notification classifier.

Build-time dev tool only — it is never shipped and the app declares no INTERNET permission
(CLAUDE.md sections 1/3). It trains a tiny bag-of-words softmax classifier and writes three
bundled assets that the runtime loads locally:

    ml/src/main/assets/notification_classifier.tflite   float32 [1, V] -> softmax [1, L]
    ml/src/main/assets/vocab.txt                         one token per line (feature order)
    ml/src/main/assets/labels.txt                        one label per line (output order)

These assets are the single source of truth for vocab + labels, so the Kotlin runtime and this
trainer can never drift. The feature contract is fixed by the existing, unit-tested
BagOfWordsFeatureExtractor: locale-independent lowercase, retain Unicode letters/numbers, and
count vocab tokens. This trainer matches that tokenizer, then trains a model to consume it.

Determinism (sections 5/9): fixed seeds + op determinism, alphabetically sorted vocab. Re-running
on the same dataset reproduces the same assets. Verification asserts exact labels on held-out
fixtures and fails the run if any regress.

Usage:
    python -m venv .venv && ./.venv/bin/pip install -r requirements.txt
    ./.venv/bin/python train.py
"""

import json
import os
from collections import Counter

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf
from tensorflow import keras

# --- Configuration ---------------------------------------------------------------------------
SEED = 1337
MIN_DF = 2  # keep a token only if it appears in at least this many examples
EPOCHS = 300
# Must match MlConfig.CONFIDENCE_THRESHOLD — verification mirrors the runtime gate.
CONFIDENCE_THRESHOLD = 0.6
# Fixed output order; mirrors the app's notification categories.
LABELS = ["promotion", "social", "news", "alarm"]

HERE = os.path.dirname(os.path.abspath(__file__))
DATASET_PATH = os.path.join(HERE, "dataset.jsonl")
ASSETS_DIR = os.path.normpath(os.path.join(HERE, "..", "src", "main", "assets"))

def tokenize(text):
    """Identical to BagOfWordsFeatureExtractor.tokenize on the Kotlin side."""
    tokens = []
    current = []
    for character in text.lower():
        if character.isalnum():
            current.append(character)
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    return tokens


def load_examples():
    examples = []
    with open(DATASET_PATH, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            examples.append((obj["text"], obj["label"]))
    unknown = {lbl for _, lbl in examples} - set(LABELS)
    if unknown:
        raise SystemExit(f"dataset has labels outside LABELS: {sorted(unknown)}")
    return examples


def build_vocabulary(examples):
    document_freq = Counter()
    for text, _ in examples:
        for token in set(tokenize(text)):
            document_freq[token] += 1
    return sorted(token for token, count in document_freq.items() if count >= MIN_DF)


def featurize(text, index):
    counts = np.zeros(len(index), dtype=np.float32)
    for token in tokenize(text):
        position = index.get(token)
        if position is not None:
            counts[position] += 1.0
    return counts


def convert_to_tflite(export_model, vocab_size):
    try:
        return tf.lite.TFLiteConverter.from_keras_model(export_model).convert()
    except Exception as error:  # pragma: no cover - fallback path
        print("from_keras_model failed, falling back to concrete function:", error)
        concrete = tf.function(lambda x: export_model(x)).get_concrete_function(
            tf.TensorSpec([1, vocab_size], tf.float32)
        )
        return tf.lite.TFLiteConverter.from_concrete_functions([concrete], export_model).convert()


def make_interpreter(model_content):
    try:
        return tf.lite.Interpreter(model_content=model_content)
    except Exception:  # pragma: no cover - very new TF moves the interpreter
        from ai_edge_litert.interpreter import Interpreter

        return Interpreter(model_content=model_content)


def main():
    keras.utils.set_random_seed(SEED)
    tf.config.experimental.enable_op_determinism()

    examples = load_examples()
    vocab = build_vocabulary(examples)
    index = {token: i for i, token in enumerate(vocab)}
    label_index = {label: i for i, label in enumerate(LABELS)}

    features = np.stack([featurize(text, index) for text, _ in examples])
    targets = np.array([label_index[label] for _, label in examples], dtype=np.int64)

    model = keras.Sequential(
        [keras.Input(shape=(len(vocab),)), keras.layers.Dense(len(LABELS), activation="softmax")]
    )
    model.compile(
        optimizer=keras.optimizers.Adam(0.01),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.fit(features, targets, epochs=EPOCHS, batch_size=16, shuffle=True, verbose=0)
    _, train_accuracy = model.evaluate(features, targets, verbose=0)

    # Re-export with a fixed batch so the .tflite input is exactly [1, V], which is what
    # BundledTfLiteBackend feeds (it does not resize tensors).
    export_model = keras.Sequential(
        [keras.Input(batch_size=1, shape=(len(vocab),)), keras.layers.Dense(len(LABELS), activation="softmax")]
    )
    export_model.set_weights(model.get_weights())
    tflite_model = convert_to_tflite(export_model, len(vocab))

    os.makedirs(ASSETS_DIR, exist_ok=True)
    model_path = os.path.join(ASSETS_DIR, "notification_classifier.tflite")
    with open(model_path, "wb") as handle:
        handle.write(tflite_model)
    with open(os.path.join(ASSETS_DIR, "vocab.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(vocab) + "\n")
    with open(os.path.join(ASSETS_DIR, "labels.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(LABELS) + "\n")

    verify(tflite_model, vocab, index, train_accuracy)


def verify(tflite_model, vocab, index, train_accuracy):
    interpreter = make_interpreter(tflite_model)
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    if list(input_detail["shape"]) != [1, len(vocab)]:
        raise SystemExit(f"unexpected input shape {input_detail['shape']}, want [1, {len(vocab)}]")
    if list(output_detail["shape"]) != [1, len(LABELS)]:
        raise SystemExit(f"unexpected output shape {output_detail['shape']}, want [1, {len(LABELS)}]")

    def predict(text):
        x = featurize(text, index).reshape(1, -1)
        interpreter.set_tensor(input_detail["index"], x)
        interpreter.invoke()
        scores = interpreter.get_tensor(output_detail["index"])[0]
        best = int(np.argmax(scores))
        return LABELS[best], float(scores[best])

    # Held-out fixtures (not verbatim in the dataset): the exact-label guarantee of section 5,
    # checked here because the Android instrumented test cannot run in this environment.
    fixtures = [
        ("Huge weekend sale, 40% off everything", "promotion"),
        ("Your coupon for free shipping expires soon", "promotion"),
        ("Casey liked your photo and left a comment", "social"),
        ("New message from your friend Jordan", "social"),
        ("Breaking news: live election update tonight", "news"),
        ("Top headlines and market update today", "news"),
        ("Alarm ringing, time to wake up now", "alarm"),
        ("Snooze your morning alarm reminder", "alarm"),
        ("오늘만 특별 할인 쿠폰이 곧 만료됩니다", "promotion"),
        ("친구가 내 사진에 댓글을 남겼습니다", "social"),
        ("속보 주요 뉴스와 날씨 소식입니다", "news"),
        ("아침 기상 알람이 울리고 있습니다", "alarm"),
    ]

    print(f"\nvocab size: {len(vocab)}   labels: {LABELS}")
    print(f"train accuracy: {train_accuracy:.3f}   model bytes: {len(tflite_model)}\n")
    failures = []
    for text, expected in fixtures:
        label, confidence = predict(text)
        ok = label == expected and confidence >= CONFIDENCE_THRESHOLD
        print(f"{'OK  ' if ok else 'FAIL'} [{label:<9} {confidence:.2f}] expect {expected:<9} :: {text}")
        if not ok:
            failures.append(text)
    if failures:
        raise SystemExit(f"\n{len(failures)} fixture(s) failed — assets not bundled-ready")
    print("\nAll fixtures passed. Assets written to ml/src/main/assets/.")


if __name__ == "__main__":
    main()
