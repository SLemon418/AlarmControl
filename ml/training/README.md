# Notification classifier — training pipeline

Offline, **build-time-only** tool that produces the bundled on-device model. It is not part of the
Gradle build and never ships in the APK. Running it downloads TensorFlow from PyPI on the *dev
machine* — the same category as Gradle resolving build dependencies. It has nothing to do with the
app's runtime, which declares **no `INTERNET` permission** and loads everything locally
(CLAUDE.md §1/§3).

## What it produces

`train.py` writes a complete immutable generation under
`../src/main/assets/classifier_generations/` and atomically selects it with
`classifier_current.txt` (all committed to the repo):

| Asset | Contract |
|-------|----------|
| `notification_classifier.tflite` | float32 input `[1, V]`, softmax output `[1, L]` |
| `vocab.txt` | one token per line — defines feature order (`V` rows) |
| `labels.txt` | one label per line — defines output order (`L` rows) |

These three are the **single source of truth** for vocab + labels. The Kotlin runtime resolves the
single committed generation before loading its model, vocab, and labels (see `ModelAssets` and
`MlModule`), so a killed or concurrent publisher cannot expose a mixed set. Repositories produced
before generation publishing retain the root asset names as a compatibility fallback.

## Feature contract

The model consumes exactly what `BagOfWordsFeatureExtractor` (in `:ml`) produces: lowercase the
text, retain Unicode letter/number runs (including Korean), drop empty tokens, and **count**
vocabulary tokens. `train.py`'s `tokenize` mirrors the same Unicode-category behavior. If you change
the tokenizer on either side, change both.

## Run it

```sh
cd ml/training
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt   # plain `tensorflow` (CPU) on Apple Silicon
./.venv/bin/python train.py
```

The run is deterministic (fixed seeds + op determinism, alphabetically sorted vocab) and **fails**
if any held-out fixture regresses — this is the "assert exact labels" check from CLAUDE.md §5,
performed here because the Android instrumented test cannot run in this environment. New outputs
are written to a same-filesystem immutable generation, fsynced, and verified before a single pointer
replacement publishes the set. A process lock serializes concurrent publishers; a crash before the
pointer replacement leaves the last-known-good generation selected.

The publisher regression tests use only the Python standard library:

```sh
python3 -m unittest discover -s . -p 'test_*.py'
```

## Editing the dataset

`dataset.jsonl` holds one `{"text": ..., "label": ...}` per line. Labels must be one of the four in
`LABELS` (top of `train.py`). After editing, re-run `train.py`; it regenerates all three assets and
re-verifies. Keep the confidence threshold in `train.py` in sync with `MlConfig.CONFIDENCE_THRESHOLD`.

The dataset and held-out checks include both English and Korean fixtures. The generated model is
also verified by `:ml/src/androidTest` against the real Android TensorFlow Lite runtime; package-level
feedback then adapts its scores incrementally on-device (CLAUDE.md §5).
