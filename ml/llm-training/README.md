# AlarmControl LLM model pipeline

**[한국어](README.ko.md)**

This build-time-only pipeline adapts a local Gemma 3 checkpoint into AlarmControl's optional
seven-intent notification classifier and exports the single MediaPipe `.task` file accepted by the
app. No model, notification, checkpoint, or training tool is shipped in the APK.

## Current artifact status

The repository now contains the reproducible data, training, strict evaluation, LiteRT conversion,
MediaPipe bundling, model-card, and release-packaging path. It intentionally does **not** contain a
base Gemma checkpoint or generated `.task`:

- Gemma access requires the user to accept Google's terms and authenticate. A script must not accept
  legal terms on the user's behalf.
- A generated artifact is not releasable until the merged checkpoint, quantized `.task`, and a
  representative physical Android device all pass the gates below.
- Model weights are far too large for Git or the APK. AlarmControl imports the extracted `.task`
  through its local-only Storage Access Framework picker.

This README describes the required procedure, not completed release evidence. It does not claim a
successful training run, conversion, one-shot final evaluation, or physical-device validation.

Start with `google/gemma-3-270m-it` because this is a narrow JSON classification task, then promote
the unchanged recipe to `google/gemma-3-1b-it` only if 270M misses the semantic or Korean hard-set
gates. Merge the selected LoRA checkpoint and convert that self-contained model to dynamic INT8.

## Fixed app contract

- Input: the exact string built by `LlmPrompt`, containing at most 2,000 UTF-16 units of title + body
  as untrusted JSON data; no package or channel identity. The runtime measures with the imported
  tokenizer and shortens the prefix further when needed to preserve the output reserve.
- Intents: `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`, `AMBIGUOUS`
- Output: one JSON object with `intent`, numeric `confidence` in `0..1`, and short `reason`
- Abstention: `AMBIGUOUS` or confidence below `0.6`
- Context: 4,096 combined input + output tokens, matching the conversion KV cache
- Decode: greedy (`topK=1`, `temperature=0`, fixed seed)
- Runtime: MediaPipe Tasks GenAI 0.10.35, fully local, with a nine-second manager timeout

`contract.py` mirrors the Kotlin prompt and parser. The unit suite fails if their critical constants
or intent names drift.

## Data

`data/seed_examples.jsonl` contains 168 manually authored, content-free synthetic seeds. They are
not real notifications and were not generated from notification exports:

- exactly 24 examples for each intent;
- exactly half English and half Korean;
- fixed, balanced source splits of 98 train, 42 validation, and 28 test seeds;
- an independent hard-boundary train and validation example for every intent and locale;
- financial promotion versus real transactions, OTP/security, delivery, social, device/news/alarm,
  mixed or missing context, control characters, and prompt injection.

`prepare_dataset.py` deterministically renders three development datasets:

- `train.jsonl`: 196 rows = 98 original train seeds + 98 prompt-injection wrappers;
- `validation.jsonl`: 84 rows = 42 original validation seeds + 42 prompt-injection variants from a
  template family that is separate from the training injection templates;
- `test.jsonl`: the unchanged 28 original test seeds, retained only as a development-regression set.

The existing test split has already informed pipeline development, so it is not unbiased final
evidence. The final gate instead uses a separate, seed-disjoint 28-row synthetic holdout in
`data/final_holdout_examples.jsonl`. Keep it blind during development: do not inspect its contents,
train on it, select prompts or hyperparameters from it, or repeatedly evaluate it.

This small seed set teaches the app contract; it does not prove broad production accuracy. Never
upload real notification exports to a hosted notebook or training service. Extend it only with
synthetic, rights-cleared, or explicitly opted-in and irreversibly redacted examples.

## 1. Prepare and verify the dataset

These steps use only Python's standard library:

```sh
cd ml/llm-training
python3 prepare_dataset.py
python3 -m unittest discover -s tests -v
```

The generated development files live under ignored `artifacts/dataset/`: 196 train rows, 84
validation rows, and 28 development-regression rows in the compatibility-named `test.jsonl`.

## 2. Obtain the base model as an explicit user action

Read and accept the [Gemma Terms](https://ai.google.dev/gemma/terms) on the model provider's page,
authenticate locally, and download a **pinned revision** outside this repository. For example, after
the terms have been accepted:

```sh
hf auth login
hf download google/gemma-3-270m-it \
  --revision <PINNED_COMMIT> \
  --local-dir /absolute/private/path/gemma-3-270m-it
```

Do not put a Hugging Face token, base model, or acceptance record in the repository. The training
script sets Hugging Face and Transformers to offline mode and accepts only a local directory.

## 3. Fine-tune and merge

Use Python 3.11 or 3.12. A CUDA machine is the supported high-throughput path; the default LoRA
recipe can also use Apple MPS or CPU for experimentation, but runtime and memory vary.

```sh
python3.12 -m venv .venv-train
./.venv-train/bin/pip install -r requirements-train.txt
./.venv-train/bin/python train.py \
  --base-model /absolute/private/path/gemma-3-<SIZE>-it \
  --base-revision <PINNED_COMMIT> \
  --method lora \
  --epochs 4
```

When run, the trainer is designed to:

- masks the full runtime prompt so loss applies only to the assistant JSON;
- adapts attention projections with rank-16 LoRA by default;
- evaluates and saves every epoch, while treating validation loss as a diagnostic rather than the
  release selection metric;
- saves the lowest-loss adapter and merged model for audit;
- never connects to a model hub or reporting service.

For a sufficiently provisioned training host, `--method full` performs full-parameter SFT. Do not
use a runtime LoRA sidecar: AlarmControl intentionally imports one self-contained file, and the
MediaPipe runtime LoRA path is GPU-only and uses a second artifact.

## 4. Select, merge, and iterate on development regression

Generate every epoch checkpoint on `validation.jsonl` and select by the release-aligned metrics,
prioritizing wrong actionable rate before macro-F1 and marketing precision. Do not select only by
teacher-forced validation loss. Merge the selected adapter:

```sh
./.venv-train/bin/python merge_adapter.py \
  --base-model /absolute/private/path/gemma-3-<SIZE>-it \
  --adapter-checkpoint artifacts/training/checkpoints/checkpoint-<STEP> \
  --output-dir artifacts/training/selected-merged
```

```sh
./.venv-train/bin/python measure_context.py \
  --model-dir artifacts/training/selected-merged

./.venv-train/bin/python evaluate.py \
  --model-dir artifacts/training/selected-merged \
  --dataset artifacts/dataset/test.jsonl \
  --output-dir artifacts/evaluation/dev-regression \
  --no-gate
```

The compatibility-named `test.jsonl` is the unchanged 28-row development-regression set. Its results
are diagnostic only: they may guide a candidate, but they cannot authorize conversion or release
and must never be reported as blind test performance.

After the model, prompt, tokenizer, decoding, and all hyperparameters are frozen, prepare and run the
fresh final holdout exactly once:

```sh
python3 prepare_final_holdout.py

./.venv-train/bin/python evaluate.py \
  --model-dir artifacts/training/selected-merged \
  --dataset artifacts/final-holdout/test.jsonl \
  --output-dir artifacts/evaluation/final
```

`prepare_final_holdout.py` refuses to render the 28 rows unless the source matches its frozen
`EXPECTED_SOURCE_SHA256`, and records that hash in the generated manifest. Preserve the source hash,
generated manifest, predictions, metrics, model hash, and exact command as release evidence. Do not
use final-holdout errors to tune and rerun the same holdout; a failed one-shot gate rejects that
release candidate.

The final evaluator fails unless all responses parse, macro-F1 is at least `0.85`, marketing
precision is at least `0.90`, clean accuracy and injection accuracy are each at least `0.85`, and no
more than `0.05` of all rows are wrong high-confidence actionable predictions. These are minimum
gates, not a claim of calibration. The model-generated confidence value must not be described as a
calibrated probability without a separate calibration study.

## 5. Convert to LiteRT dynamic INT8

Google's current converter uses a separate environment. It is CPU-only and can require substantial
RAM and time. Run it on a well-provisioned Linux host or local Linux container; loading TensorFlow
and LiteRT Converter together is not supported by this pipeline on native macOS.

```sh
python3.12 -m venv .venv-convert
./.venv-convert/bin/pip install -r requirements-convert.txt
./.venv-convert/bin/python convert_to_litert.py \
  --model-dir artifacts/training/selected-merged \
  --model-size <270m|1b> \
  --quantize dynamic_int8 \
  --prefill-seq-len 2048 \
  --kv-cache-max-len 4096
```

The intermediate `.tflite` is not directly importable by AlarmControl.

## 6. Create the MediaPipe Task Bundle

Use another environment so MediaPipe's Python dependencies cannot conflict with the converter:

```sh
python3.12 -m venv .venv-bundle
./.venv-bundle/bin/pip install -r requirements-bundle.txt
./.venv-bundle/bin/python bundle_task.py \
  --tflite-model artifacts/litert/<CONVERTER_OUTPUT>.tflite \
  --model-dir artifacts/training/selected-merged
```

The output `.task` bundles the LiteRT model, Gemma tokenizer, prompt templates, stop tokens, and
metadata. GGUF, Ollama files, safetensors, and the intermediate `.tflite` are not valid substitutes.

## 7. Quantized and physical-device validation

Do not release based only on the pre-quantization evaluator. On the final `.task`:

1. Run the validation and development-regression fixtures through MediaPipe and require the same
   parser/accuracy gates. Do not reopen or rerun the one-shot final holdout.
2. Record quantized-versus-merged label agreement on those non-final fixtures.
3. Import through AlarmControl Settings and confirm `Ready`, the displayed size, and SHA-256.
4. On representative physical devices, record cold-load time, RSS, p50/p95 inference latency,
   repeated-call stability, OOM behavior, and thermal impact. MediaPipe does not reliably support
   emulator LLM performance testing.
5. Confirm p95 stays below the manager's nine-second limit.
6. Keep automatic LLM actions disabled unless the release evidence justifies the risk.
7. Run the normal Gradle tests, quality checks, and offline guards.

## 8. Distribution package

Complete `MODEL_CARD.template.md` with the pinned base revision, modifications, metrics, and device
results. Obtain the applicable Gemma Terms copy yourself, then package it:

```sh
python3 make_release.py \
  --task artifacts/alarmcontrol-gemma3-270m-dynint8-kv4096.task \
  --model-card /path/to/completed/MODEL_CARD.md \
  --gemma-terms /path/to/GEMMA_TERMS.pdf
```

The ZIP contains the `.task`, model card, notice, terms copy, and checksums. Users must extract it
and select the `.task` in AlarmControl. The derivative remains subject to the
[Gemma Terms](https://ai.google.dev/gemma/terms) and
[prohibited-use policy](https://ai.google.dev/gemma/prohibited_use_policy).

## References

- [Google: fine-tune Gemma text models](https://ai.google.dev/gemma/docs/core/huggingface_text_finetune_qlora)
- [Google: convert Gemma safetensors to a MediaPipe Task](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)
- [Google: MediaPipe LLM Inference for Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)

MediaPipe LLM Inference is now maintenance-only in Google's documentation, but this project keeps it
for this milestone because the engine is a locked architectural decision. Any LiteRT-LM migration is
a separate reviewed change.
