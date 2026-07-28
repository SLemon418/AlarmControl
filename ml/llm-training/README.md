# AlarmControl LLM model pipeline

**[한국어](README.ko.md)**

This build-time-only pipeline adapts a local Gemma 3 checkpoint into AlarmControl's optional
seven-intent notification classifier and exports the single MediaPipe `.task` file accepted by the
app. No model, notification, checkpoint, or training tool is shipped in the APK.

The installed Android app remains strictly network-free. Developer machines may use the network to
install build dependencies and download a base checkpoint after the user accepts its terms. Tokens,
download clients, training environments, and weights remain build-time-only and are never packaged
in AlarmControl. Developer-only training jobs using synthetic or rights-cleared data may also use
the network, but must never receive user notification content.

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

Status snapshot: **2026-07-29**. The current local 1B research artifact uses
`google/gemma-3-1b-it` revision
`dcc83ea841ab6100d6b47a070329e1ba4cf78752`. The v13 curriculum checkpoint passed the strict
validation gate but failed the equally strict development-regression gate, so it is **not a release
candidate**:

| Gate | macro-F1 | clean | injection | JSON | wrong actionable | Result |
|---|---:|---:|---:|---:|---:|---|
| validation (84) | 0.8860 | 0.9048 | 0.8571 | 1.0000 | 0.0476 | pass |
| development (28) | 0.7701 | 1.0000 | 0.4167 | 1.0000 | 0.0000 | fail |

Its recorded lineage is upstream 1B -> v10 rank-16 LoRA at `2e-4`, checkpoint 84 -> safe merge ->
fresh rank-16 LoRA over the 70-row curriculum for two epochs at `5e-5`. Both stages used seed
`20260727`; the final stage used Apple MPS with `bfloat16`. These details document a rejected
research checkpoint, not a recipe authorized for distribution.

The one-sentence prompt-only follow-up also failed validation and was reverted. Conversion and
physical-device validation have not run. On 2026-07-28, a broad source search accidentally exposed
the existing final-holdout text. That set is retired and must never be used as final evidence. After
a future candidate is frozen and passes every non-final gate, a context-isolated author must create
and hash a replacement holdout for exactly one evaluation. The local 16 GB Apple Silicon host also
cannot satisfy the converter's documented Linux host with at least 64 GB RAM requirement;
additionally, the pinned converter wheel is x86_64-only. A qualifying host is required after a
future candidate passes both non-final gates.

The smaller local **Gemma 3 270M** fine-tuned checkpoint is also rejected by the release gates:

| Gate | macro-F1 | MARKETING precision | actionable accuracy | wrong actionable |
|---|---:|---:|---:|---:|
| validation (84) | 0.6093 | 0.6000 | 0.6935 | 0.2262 |
| development (28) | 0.6724 | 0.5000 | 0.6957 | 0.2500 |

The bounded `dynamic_int4_block128` static preflight is blocked on the current macOS ARM64 host as
described below. No 270M model construction, conversion, `.tflite`, `.task`, MediaPipe Tasks GenAI
`0.10.35` compatibility, or physical-device result has been established. AlarmControl therefore
keeps the optional LLM path unverified/manual and exposes no automatic background compatibility
profile.

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

`data/seed_examples.jsonl` contains 210 manually authored, content-free synthetic seeds. They are
not real notifications and were not generated from notification exports:

- exactly 30 examples for each intent;
- exactly half English and half Korean;
- fixed, balanced source splits of 140 train, 42 validation, and 28 development seeds;
- an independent hard-boundary train and validation example for every intent and locale;
- financial promotion versus real transactions, OTP/security, delivery, social, device/news/alarm,
  mixed or missing context, control characters, and prompt injection.

`prepare_dataset.py` deterministically renders three development datasets:

- `train.jsonl`: 420 rows = 140 original train seeds + 280 prompt-injection wrappers;
- `validation.jsonl`: 84 rows = 42 original validation seeds + 42 prompt-injection variants from a
  template family that is separate from the training injection templates;
- `test.jsonl`: the unchanged 28 original test seeds, retained only as a development-regression set.

The existing test split has already informed pipeline development, so it is not unbiased final
evidence. The previously designated `data/final_holdout_examples.jsonl` was also exposed during
development and is retired. Do not evaluate it. Only after the candidate, conversion, and
physical-device results are frozen may a context-isolated author create a new seed-disjoint
synthetic holdout, record its hash, and run it exactly once. Never train on it, select prompts or
hyperparameters from it, or use its errors for another attempt.

This small seed set teaches the app contract; it does not prove broad production accuracy. Never
upload real notification exports to a hosted notebook or training service. Extend it only with
synthetic, rights-cleared, or explicitly opted-in and irreversibly redacted examples.

## 1. Prepare and verify the dataset

Dataset generation uses only Python's standard library. The focused pipeline tests use the isolated
training environment:

```sh
cd ml/llm-training
python3 prepare_dataset.py
./.venv-train/bin/python -m unittest \
  tests.test_pipeline.ContractTest \
  tests.test_pipeline.DatasetTest \
  tests.test_pipeline.EvaluationTest \
  tests.test_pipeline.RuntimeAlignmentTest \
  tests.test_training_contract \
  tests.test_merge_adapter \
  tests.test_convert_to_litert \
  tests.test_bundle_task -v
```

Do not use unrestricted test discovery during development: `FinalHoldoutTest` reads the retired
source and is excluded from all candidate evidence. The generated development files live under
ignored `artifacts/dataset/`: 420 train rows, 84 validation rows, and 28 development-regression rows
in the compatibility-named `test.jsonl`.

## 2. Obtain the base model as an explicit user action

Read and accept the [Gemma Terms](https://ai.google.dev/gemma/terms) on the model provider's page,
authenticate locally, and download a **pinned revision** outside this repository. For example, after
the terms have been accepted:

```sh
hf auth login
hf download google/gemma-3-1b-it \
  --revision <PINNED_COMMIT> \
  --local-dir /absolute/private/path/gemma-3-1b-it
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
  --epochs <EPOCHS>
```

When run, the trainer is designed to:

- masks the full runtime prompt so loss applies only to the assistant JSON;
- adapts attention projections with rank-16 LoRA by default;
- can save every epoch or fixed step; generation metrics, not teacher-forced loss, select a
  candidate;
- supports skipping Trainer loss evaluation on memory-limited MPS hosts while preserving the
  separate strict generation gate;
- saves an adapter and self-contained merged model for audit;
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
  --output-dir artifacts/evaluation/dev-regression
```

The compatibility-named `test.jsonl` is the unchanged 28-row development-regression set. Its results
are diagnostic only: they may guide a candidate, but they cannot authorize conversion or release
and must never be reported as blind test performance.

The following legacy command renders the retired holdout and must **not** be run for current release
evidence:

```sh
python3 prepare_final_holdout.py

./.venv-train/bin/python evaluate.py \
  --model-dir artifacts/training/selected-merged \
  --dataset artifacts/final-holdout/test.jsonl \
  --output-dir artifacts/evaluation/final
```

Before final evaluation, replace this legacy source and its frozen hash through the context-isolated
workflow described above, after the model, prompt, tokenizer, decoding, conversion, device results,
and every hyperparameter are fixed. Preserve the replacement source hash, generated manifest,
predictions, metrics, model hash, and exact command as release evidence. Do not use final-holdout
errors to tune and rerun the same holdout; a failed one-shot gate rejects that release candidate.

The final evaluator fails unless all responses parse, macro-F1 is at least `0.85`, marketing
precision is at least `0.90`, clean, injection, and non-ambiguous-injection accuracy are each at
least `0.85`, and no more than `0.05` of all rows are wrong high-confidence actionable predictions.
The evaluator also records false `AMBIGUOUS` abstentions explicitly. These are minimum gates, not a
claim of calibration. The model-generated confidence value must not be described as a calibrated
probability without a separate calibration study.

## 5. Gemma 3 270M INT4 compatibility preflight

The bounded probe targets Gemma 3 270M with `dynamic_int4_block128`. The converter also retains
`--model-size 1b` and the previous quantization options for controlled comparisons, but neither
size is a verified automatic background candidate.

Use the isolated, exactly pinned converter environment. The script accepts only a complete local
model directory (`config.json`, `tokenizer.model`, and non-empty `model*.safetensors`), rejects
unsafe output paths and collisions, and caps prefill/KV lengths at the app's 4,096-token context.
It forces Hugging Face/Transformers offline mode, hides accelerator devices, and limits common math
libraries to two CPU threads. It contains no model downloader.

```sh
python3.12 -m venv .venv-convert
./.venv-convert/bin/pip install -r requirements-convert.txt
./.venv-convert/bin/python convert_to_litert.py \
  --model-dir artifacts/training/selected-merged \
  --model-size 270m \
  --quantize dynamic_int4_block128 \
  --prefill-seq-len 2048 \
  --kv-cache-max-len 4096 \
  --preflight-only
```

The preflight is intended to import the pinned LiteRT Torch API and verify `build_model_270m`, the
INT4 recipe, the conversion entry point, transposed KV-cache support, and export configuration
without constructing the model or converting weights. On the current macOS ARM64 host, local-model
input validation passes and isolated `kv_cache` and `export_config` imports pass. Importing either
the Gemma 3 module or converter aborts with exit code 134:

```text
Option 'info-output-file' registered more than once
```

This is an LLVM duplicate-option registration conflict, recorded in
`artifacts/litert-270m-preflight/compatibility-preflight.json` as
`BLOCKED_BY_HOST_IMPORT_CONFLICT`. The model was not constructed, conversion did not run, and no
`.tflite` or `.task` was created. MediaPipe Tasks GenAI `0.10.35` and device compatibility remain
`UNVERIFIED`.

Under the current work policy, only one heavy process may run, CPU use is limited to two threads,
and RSS is limited to 4 GiB. Do not remove `--preflight-only` on this host or work around the import
abort by silently changing dependency versions. A future experiment needs a supported isolated
host, must preserve the pinned recipe, and must stop on its first memory-limit failure.

Any emitted intermediate `.tflite` is not directly importable by AlarmControl.

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

Static preflight does not establish compatibility with MediaPipe Tasks GenAI `0.10.35`. After a
bounded conversion succeeds, bundling and device tests must still pass. On the final `.task`:

1. Bundle with the pinned MediaPipe tool and verify the resulting structure and checksum.
2. Run the validation and development-regression fixtures through MediaPipe and require the same
   parser/accuracy gates. Do not reopen or rerun the one-shot final holdout.
3. Record quantized-versus-merged label agreement on those non-final fixtures.
4. Import through AlarmControl Settings and confirm `Ready`, the displayed size, and SHA-256.
5. On the Note20, exercise create/load, `sizeInTokens`, generate, unload/reload, and idle-TTL
   release before treating the exact model SHA as verified.
6. On representative physical devices, record cold-load time, RSS, p50/p95 inference latency,
   repeated-call stability, OOM behavior, and thermal impact. MediaPipe does not reliably support
   emulator LLM performance testing.
7. Confirm p95 stays below the manager's nine-second limit.
8. Keep automatic LLM actions disabled unless the release evidence justifies the risk.
9. Run the normal Gradle tests, quality checks, and offline guards.

## 8. Distribution package

Complete `MODEL_CARD.template.md` with the pinned base revision, modifications, metrics, and device
results. Obtain the applicable Gemma Terms copy yourself, then package it:

```sh
python3 make_release.py \
  --task artifacts/alarmcontrol-gemma3-270m-dynint4-block128-kv4096.task \
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
