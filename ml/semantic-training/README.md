# Semantic model training and release pipeline

This directory contains fictional synthetic notification families, CPU
training, LiteRT conversion, evaluation, packaging, and release-evidence tools
for AlarmControl's seven-way lightweight semantic classifier. It does not read
app data, real notification content, package metadata, or network resources.

## Promoted candidate status (2026-07-29)

`koelectra-primary-v6` epoch 3 has been promoted as the exact four-file payload
under `ml/src/main/assets/`. The classifier is a 47,050,248-byte (44.87 MiB)
dynamic-INT8 LiteRT model with fixed `[1, 128]` WordPiece inputs and seven
float32 logits. Its runtime thresholds are the exact float32 values:

- general: `0.949999988079071`
- `MARKETING`: `0.9917579889297485`

The final fresh-blind v8 run evaluated this frozen TFLite candidate exactly
once on 420 balanced Korean, English, and mixed-language rows. Only aggregate
evidence remains in `artifacts/sealed-holdout-v8/`; raw rows and per-row
predictions were removed after evaluation.

| Metric | Overall | English | Korean | Mixed |
|---|---:|---:|---:|---:|
| Raw macro-F1 | 0.954401 | 0.891103 | 0.985678 | 0.985678 |
| Trusted coverage | 0.855556 | 0.650000 | 0.950000 | 0.966667 |

Raw MARKETING precision was `1.000000`, runtime macro-F1 was `0.879211`,
and there were zero trusted non-MARKETING to MARKETING false positives.

On a Galaxy Note20 5G (`SM-N981N`, arm64-v8a), the real bundled assets passed
model-loading, tokenizer-parity, and input-dependent-logit instrumentation.
Cold initialization was 121.037 ms; 40 measured inferences produced p50
65.506 ms and p95 68.623 ms. Process PSS changed from 45,531 to 96,117 KiB,
RSS from 122,420 to 173,308 KiB, and native heap from 5,972,016 to 22,291,072
bytes. No OOM occurred, thermal status remained `0`, and the sampled charge
counter remained 3,332,000 µAh. These measurements describe that device and
run only.

The verified `bundleRelease` output is 98,983,815 bytes (94.40 MiB), below the
105 MiB physical cap, but it is only an App Bundle compatibility regression
artifact and not a distribution candidate. GitHub Releases publishes exactly
five separately verified APKs signed with the same update key: `universal`,
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, each with its matching
checksum. GitHub does not select one automatically. The same ABI-independent
semantic model is bundled in every APK variant; an optional custom LLM remains
a separate user-provided file.

Runtime integration remains rule-first. Semantic inference runs before commit
only when an Active rule needs it, using `REALTIME` urgency. Monitor-only
semantic work uses `BACKGROUND` urgency after the active decision is already
committed, so a late result cannot change the handled notification. The
single native runner admits at most one running and one queued request;
real-time work may evict only queued background work.

Build the dataset:

```bash
python3 ml/semantic-training/build_dataset.py
```

The default output is written to the ignored
`ml/semantic-training/artifacts/dataset-v6/` directory:

- `dataset.jsonl` — 26,460 clean and prompt-injected rows
- `manifest.json` — row/family/split counts and SHA-256 hashes

Each Korean and mixed-language locale/intent group contains 190 synthetic
families. English contains 250 per intent. The additional balanced boundary
catalogs emphasize commercial-looking hard negatives without using any sealed
holdout examples. Family-disjoint train/validation/test counts are 152/19/19
for Korean and mixed, and 200/25/25 for English.

Validate the independent sealed holdout:

```bash
python3 ml/semantic-training/validate_holdout.py
```

Validation reads the three locale holdouts without merging them into the
training dataset. It writes only file SHA-256 values and aggregate counts to
the ignored
`ml/semantic-training/artifacts/sealed-holdout-v1/manifest.json`; notification
examples and identifiers are never included in that manifest.

Run the lightweight standard-library tests:

```bash
python3 -m unittest discover -s ml/semantic-training/tests -v
```

The split is family-disjoint. Within every intent and locale, a fixed seed and
SHA-256 ordering apply the locale counts above, preserving an exact 80/10/10
percent split.

## Storage and CPU guard

Heavy jobs must run under one dedicated, existing run root. Initialize its
fail-closed marker once, inspect the plan, and use `launch --apply` for every
training or conversion subprocess:

```bash
mkdir -p /absolute/path/to/semantic-runs
python3 ml/semantic-training/storage_guard.py \
  --root /absolute/path/to/semantic-runs init
python3 ml/semantic-training/storage_guard.py \
  --root /absolute/path/to/semantic-runs report
python3 ml/semantic-training/storage_guard.py \
  --root /absolute/path/to/semantic-runs check --dry-run
python3 ml/semantic-training/storage_guard.py \
  --root /absolute/path/to/semantic-runs launch --apply -- \
  /absolute/path/to/python \
  /absolute/path/to/repository/ml/semantic-training/train_koelectra.py \
  --base-model /absolute/path/to/local-koelectra \
  --dataset /absolute/path/to/dataset.jsonl \
  --output /absolute/path/to/semantic-runs/training-primary
```

The default policy requests cleanup at 80 GiB, deletes oldest eligible data
until the marked run root is below 70 GiB, hard-stops at 100 GiB, and also
hard-stops when the filesystem has less than 100 GiB free. Cleanup is limited
to marker-authorized `.disposable` directories under `cache/`, `tmp/`,
`failed-conversions/`, `checkpoints/`, or `seed-weights/`. Protected model,
dataset, manifest, tokenizer, config, evaluation, selected/best/last, model
card, and `.tflite` paths are never candidates. The launcher checks before and
after the child process and forces CPU-only execution, two math threads, and
one advertised subprocess. Review the emitted JSON report after every
boundary; a hard stop or incomplete cleanup is a failed run.

## CPU-only KoELECTRA training

`train_koelectra.py` is the primary seven-way classifier trainer. It uses a
manual PyTorch loop, reads only the `train` and `validation` rows from
`dataset.jsonl`, and requires a complete local Hugging Face-compatible
KoELECTRA model directory. Transformers offline mode is forced and
`local_files_only=True` is used; a model name or remote download is never
accepted.

The trainer calls the shared `semantic_contract.notification_text()` helper,
which mirrors the deployed Android classifier: nonempty title and body are
joined with one literal ASCII space, stripped, and normalized to NFC. BERT
clean-text handling removes control/format characters without introducing
word boundaries. No training-only marker tokens are inserted.

Inputs are truncated to 128 tokens. Training runs on CPU with at most two
intra-op and two inter-op threads, deterministic seeds, zero data-loader
workers, and the exact label order `MARKETING`, `TRANSACTIONAL`, `SECURITY`,
`DELIVERY`, `SOCIAL`, `OTHER`, `AMBIGUOUS`. The builder currently emits
balanced train and validation splits; the trainer verifies that balance rather
than adding a weighted sampler.

With `torch` and `transformers` already installed locally:

```bash
python3 ml/semantic-training/train_koelectra.py \
  --base-model /absolute/path/to/local-koelectra \
  --dataset ml/semantic-training/artifacts/dataset-v6/dataset.jsonl \
  --output /absolute/path/to/training-output \
  --epochs 3 \
  --batch-size 8 \
  --learning-rate 2e-5 \
  --max-rss-bytes 4294967296
```

The output contains atomic `best/` and `checkpoint/` bundles with model
weights, tokenizer, model config, optimizer state, checkpoint metadata, and
`training_manifest.json`. `SIGINT` or `SIGTERM` is handled at an optimizer
boundary and writes a final checkpoint before exiting. Peak RSS is checked
before model loading and at every training and validation batch boundary; the
default hard ceiling is 4 GiB and can be lowered with `--max-rss-bytes`. Heavy
training is never part of the standard-library test command.

Evaluate a local `best/` bundle without loading a remote model:

```bash
python3 ml/semantic-training/predict_koelectra.py \
  --model-dir /absolute/path/to/training-output/best \
  --input ml/semantic-training/artifacts/dataset-v6/dataset.jsonl \
  --split validation \
  --output /absolute/path/to/koelectra-predictions.jsonl \
  --batch-size 8 \
  --max-rss-bytes 4294967296
```

The predictor uses the same literal-space text contract, exact label order,
128-token limit, two-thread CPU configuration, and default 4 GiB peak-RSS
ceiling as training. Batch size is capped at 16. It atomically writes strict
prediction JSONL with the evaluator's exact nine fields, including
`split="holdout"` for sealed files, plus a neighboring
`alarmcontrol-semantic-prediction-manifest-v2` manifest. Its common binding
records the backend, input/output paths and SHA-256 hashes, model-artifact
SHA-256, selected split, and row count; TensorFlow Lite predictions also bind
the exact WordPiece vocabulary SHA-256. Backend-specific bundle hashes remain
alongside those fields. The manifest records
`alarmcontrol-semantic-prediction-v1` as the row contract version. Manifest
timings are developer-host measurements and are explicitly not Android device
acceptance results.

## Semantic evaluation and release gate

Prediction JSONL uses the label order from `semantic_contract.py` and contains
`id`, `locale`, `intent`, `injection`, `pair_id`, `predicted_intent`,
`confidence`, and `probabilities`. Probabilities may be an exact label-keyed
object or a seven-value array in contract label order. Threshold tuning also
requires `split: "validation"` on every row.

Evaluate raw argmax and runtime abstention metrics:

```bash
python3 ml/semantic-training/evaluate_semantic.py evaluate \
  /absolute/path/to/predictions.jsonl \
  --general-threshold 0.949999988079071 \
  --marketing-threshold 0.9917579889297485
```

Apply the default release gate:

```bash
python3 ml/semantic-training/evaluate_semantic.py gate \
  /absolute/path/to/predictions.jsonl \
  --source-manifest ml/semantic-training/artifacts/dataset-v6/manifest.json \
  --general-threshold 0.949999988079071 \
  --marketing-threshold 0.9917579889297485
```

The defaults require raw macro-F1 of at least `0.85`, raw MARKETING precision
of at least `0.90`, every locale macro-F1 of at least `0.80`, actionable
trusted coverage of at least `0.60` overall and `0.40` in each locale, and no
trusted non-MARKETING row classified as MARKETING, whether clean or injected.
Release selection keeps the general threshold at the conservative float32
floor `0.949999988079071`. It independently raises the MARKETING threshold to
the next float32 above the maximum validation
non-MARKETING-to-MARKETING score. Test or holdout rows never tune either value.
Selection schema v4 records them as the closed root fields
`general_threshold` and `marketing_threshold`; copy both unchanged into every
later gate and parity command.
The snippets use the current v6 development value `0.9917579889297485` for
MARKETING; a different candidate must use its own validation-selected field.
Actionable coverage excludes rows whose actual intent is `AMBIGUOUS` from both
the numerator and denominator, so misclassified ambiguous examples cannot
inflate coverage.
`AMBIGUOUS` argmax remains a normal output at any confidence; only a
below-threshold non-AMBIGUOUS argmax abstains to `AMBIGUOUS`.

`gate` and `select-threshold` require a dataset-v2/v3/v4/v5/v6 or
sealed-holdout-v1/v2 source manifest. Before measuring quality they verify every prediction
manifest, input/output hash and count, one global split, and exact
ID/locale/intent/pair/injection coverage. A sealed holdout gate receives all
three locale prediction files in one command; omitting a locale is rejected.
Strict gate outputs include `semantic-evaluation-provenance-v1`, binding the
source manifest, backend, model artifact, and vocabulary. TensorFlow Lite gates
reject missing or mismatched model/vocabulary files.

Select the highest-coverage safe threshold from validation predictions only:

```bash
python3 ml/semantic-training/evaluate_semantic.py select-threshold \
  /absolute/path/to/validation-predictions.jsonl \
  --source-manifest ml/semantic-training/artifacts/dataset-v6/manifest.json \
  --output /absolute/path/to/selected-threshold.json
```

The selector rejects missing, test, or holdout split metadata so sealed
evaluation data cannot tune the runtime thresholds. It chooses the next
representable float32 above an unsafe MARKETING score, not the next Python
float64, so the serialized threshold has the same comparison behavior on
Android.

## Local KoELECTRA to LiteRT conversion

Run conversion after the trained bundle passes its development-quality check.
Release threshold selection and the final sealed holdout gate are then rerun
from this exact TensorFlow Lite artifact.
Run from an isolated environment containing the pinned `litert-torch 0.9.1` toolchain:

```bash
/absolute/path/to/litert-venv/bin/python \
  ml/semantic-training/convert_koelectra_litert.py \
  --model-dir /absolute/path/to/training-output/best \
  --output-dir /absolute/path/to/litert-output \
  --quantization dynamic-int8
```

`auto` is allowed only for conversion experiments: it tries the experimental,
calibration-free PT2E dynamic-int8 weight path and may fall back to float32.
A deployable package must record `applied: "dynamic-int8"`; a float32 fallback
is rejected. After export the converter reopens the artifact with the LiteRT
interpreter and records `quantization_audit`, including positive INT8 tensor
and `QUANTIZE` operator counts. Missing, failed, or contradictory audit data is
also rejected by packaging. The atomic output contains
`semantic_classifier.tflite` and `conversion_manifest.json`; the manifest
binds the exact model bundle and `vocab.txt` SHA-256. Conversion is offline,
CPU-only, limited to two threads and 4 GiB peak RSS, and refuses a model larger
than 45 MiB.

The default deployment signature has fixed int32 `input_ids` and
`attention_mask` tensors shaped `[1, 128]` and one float32 logits output shaped
`[1, 7]`. Add `--include-token-type-ids` only when the eventual runtime
contract explicitly requires a third fixed int32 input.

## Fresh-blind and backend-parity policy

Every holdout or aggregate gate that was inspected before the candidate was
frozen—including the old v1–v6 sets—is development-only. It may diagnose or
compare candidates, but it cannot be cited as final blind evidence. Freeze the
model bundle, vocabulary, converted artifact, conversion manifest, and
float32-exact threshold first. Then run the exact frozen TFLite candidate once
against a newly isolated blind set. Do not tune, retrain, reconvert, or select
a threshold from that result. Keep raw blind rows and per-row predictions
outside the repository; retain only the aggregate manifest, aggregate passing
gate, and their hashes. If the candidate changes, acquire a different fresh
blind set.

Before the blind run, create the development test gate from the TFLite test
predictions and an aggregate PyTorch-to-TFLite parity report:

```bash
python3 ml/semantic-training/predict_koelectra.py \
  --model-dir /absolute/path/to/training-output/best \
  --input /absolute/path/to/dataset-v6/dataset.jsonl \
  --split test \
  --output /absolute/path/to/pytorch-test-predictions.jsonl

python3 ml/semantic-training/predict_tflite.py \
  --model /absolute/path/to/litert-output/semantic_classifier.tflite \
  --vocab /absolute/path/to/training-output/best/vocab.txt \
  --input /absolute/path/to/dataset-v6/dataset.jsonl \
  --split test \
  --output /absolute/path/to/tflite-test-predictions.jsonl

python3 ml/semantic-training/evaluate_semantic.py gate \
  /absolute/path/to/tflite-test-predictions.jsonl \
  --source-manifest /absolute/path/to/dataset-v6/manifest.json \
  --general-threshold 0.949999988079071 \
  --marketing-threshold 0.9917579889297485 \
  --output /absolute/path/to/development-test-gate.json

python3 ml/semantic-training/compare_backend_parity.py \
  --pytorch-predictions /absolute/path/to/pytorch-test-predictions.jsonl \
  --tflite-predictions /absolute/path/to/tflite-test-predictions.jsonl \
  --source-manifest /absolute/path/to/dataset-v6/manifest.json \
  --conversion-manifest /absolute/path/to/litert-output/conversion_manifest.json \
  --general-threshold 0.949999988079071 \
  --marketing-threshold 0.9917579889297485 \
  --output /absolute/path/to/test-parity-report.json
```

The packager requires the complete 2,646-row test gate, exact model/vocabulary
and dataset provenance, metric parity with that TFLite gate, consistent
agreement counts, and zero newly introduced unsafe trusted MARKETING results.

## Packaging and release evidence

Package only after validation threshold selection, the TFLite development test
gate, test parity, and the fresh aggregate blind gate all pass:

```bash
python3 ml/semantic-training/package_semantic_assets.py \
  --conversion-dir /absolute/path/to/litert-output \
  --model-dir /absolute/path/to/training-output/best \
  --threshold-selection /absolute/path/to/selected-threshold.json \
  --development-test-gate /absolute/path/to/development-test-gate.json \
  --test-parity-report /absolute/path/to/test-parity-report.json \
  --sealed-holdout-gate /absolute/path/to/fresh-blind-gate.json \
  --output-dir /absolute/path/to/semantic-android-assets
```

The generated asset manifest hash-binds the conversion manifest, threshold
selection, development test gate, test parity report, and sealed blind gate.
It also embeds the threshold/test/blind provenance and the conversion
quantization audit. The packager atomically creates exactly four Android asset
files and does not copy them into the Android module automatically.
Use the same exact selected `best/` or `checkpoint/` bundle as `--model-dir`
for both packaging and release evidence.

Finally build aggregate-only checked-in evidence and its model card:

```bash
python3 ml/semantic-training/build_release_evidence.py \
  --release-id koelectra-primary-v1 \
  --upstream-provenance /absolute/path/to/upstream-provenance.json \
  --training-manifest /absolute/path/to/training-output/training_manifest.json \
  --model-dir /absolute/path/to/training-output/best \
  --conversion-manifest /absolute/path/to/litert-output/conversion_manifest.json \
  --threshold-selection /absolute/path/to/selected-threshold.json \
  --test-gate /absolute/path/to/development-test-gate.json \
  --sealed-holdout-manifest /absolute/path/to/fresh-blind-manifest.json \
  --sealed-holdout-gate /absolute/path/to/fresh-blind-gate.json \
  --parity-report /absolute/path/to/test-parity-report.json \
  --assets-dir /absolute/path/to/semantic-android-assets \
  --output-dir /absolute/path/to/release-evidence
```

`build_release_evidence.py` validates the same hash chain without reading raw
blind rows. It hashes the explicitly selected model bundle, validates its
`checkpoint.json` epoch and metrics against the training manifest, and reports
the selected epoch separately from the training-best epoch. Its two
deterministic outputs are `evidence.json` and `MODEL_CARD.md`; both are
aggregate-only and reject notification content, row identifiers, and absolute
local paths.
