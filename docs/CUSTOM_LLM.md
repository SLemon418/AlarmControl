# Bring your own local LLM

**[한국어](CUSTOM_LLM.ko.md)**

AlarmControl can copy a user-created model into private app storage, but it does not provide,
train, convert, host, or download a generative LLM. Model acquisition, training, conversion,
licensing, and validation are the user's responsibility.

Training and conversion happen outside the Android app on a user-controlled development computer.
The installed app neither trains a foundation model nor sends training data anywhere.

> **Current release limitation:** importing a model only verifies its file integrity and whether
> MediaPipe can load it. Every imported model remains `UNVERIFIED`, so this release has no automatic
> background or manual LLM inference path. The bundled classifiers continue to provide the normal
> filtering behavior.

## Separate bundled classifier

The APK already contains the 47,050,248-byte (44.87 MiB) seven-intent semantic classifier. It is a
fixed-output LiteRT classifier, not a generative LLM, and needs no additional file or setup. Rules
and this bundled classifier remain the default real-time path even when no custom LLM is imported.
It is enabled by default but can be turned off under
**Settings → Smart notification sorting → Sort notifications by content**; disabling it skips
seven-intent inference and leaves the separate custom-LLM setting unchanged.

## Required model format

AlarmControl is pinned to MediaPipe Tasks GenAI `0.10.35`. It accepts one self-contained, text-only
MediaPipe Task Bundle with a `.task` suffix. The bundle must contain:

- a MediaPipe-compatible multi-signature LiteRT model;
- its tokenizer;
- the model-specific start and stop tokens;
- the correct user/model prompt wrappers and required metadata.

Renaming another format does not convert it. GGUF, Ollama files, ONNX, safetensors, a Hugging Face
checkpoint directory, a raw `.tflite`, a LoRA adapter by itself, or a ZIP containing the `.task`
cannot be imported as the runtime model.

An arbitrary LLM is not automatically compatible. Its architecture and operations must be
supported by a converter that targets the MediaPipe LLM Inference task. Each model family may need
its own conversion mapping, tokenizer handling, chat template, special tokens, KV-cache layout, and
quantization recipe. If no compatible converter exists, using that model requires a separately
reviewed runtime or model-adapter change in AlarmControl.

## AlarmControl inference contract

Training and conversion must preserve this fixed contract:

| Item | Required value |
|---|---|
| Runtime | `com.google.mediapipe:tasks-genai:0.10.35` |
| Modality | Text input and text output only |
| Context | 4,096 combined prompt and generated tokens |
| Output reserve | 128 tokens inside the 4,096-token context |
| Decode | `topK=1`, `topP=1.0`, `temperature=0`, fixed random seed |
| Trusted threshold | `confidence >= 0.6` and intent other than `AMBIGUOUS` |

The task's compiled KV-cache or context length must match 4,096. MediaPipe's `maxTokens` is the
combined input and output limit, not an output-only limit. AlarmControl reserves 128 tokens, leaving
at most 3,968 model tokens for the prompt.

AlarmControl joins the notification title and body, keeps at most 2,000 UTF-16 code units, escapes
the content as an untrusted JSON string, and shortens it further with the imported tokenizer when
needed. Package and channel identifiers are not sent to the model. The exact runtime prompt in
`ml/src/main/kotlin/com/alarmcontrol/ml/llm/LlmPrompt.kt` is the source of truth and should be used
unchanged when preparing supervised examples.

The only valid intent labels are:

```text
MARKETING
TRANSACTIONAL
SECURITY
DELIVERY
SOCIAL
OTHER
AMBIGUOUS
```

The model must return one JSON object and no Markdown:

```json
{"intent":"TRANSACTIONAL","confidence":0.91,"reason":"Confirmed payment"}
```

- `intent` must exactly match one uppercase label above.
- `confidence` must be a finite number from `0.0` through `1.0`.
- `reason` must be a string; keep it short, with 280 characters as the runtime maximum retained.
- Use `AMBIGUOUS`, or confidence below `0.6`, when the evidence is insufficient or conflicting.
- Model-generated confidence is not a calibrated probability unless separately studied and
  calibrated.

Malformed JSON, missing or mistyped fields, unknown labels, non-finite or out-of-range confidence,
low confidence, and `AMBIGUOUS` all fail open.

## Training data

Use only synthetic, rights-cleared, or otherwise lawfully prepared data. AlarmControl does not
export a notification corpus for training. Do not upload real notification content, user feedback,
or LLM reasoning to a hosted notebook, model provider, telemetry service, or other remote system.

The training set should cover Korean, English, and mixed-language notifications, all seven labels,
hard class boundaries, truncated or missing context, JSON/control characters, and prompt-injection
attempts. Teach the exact runtime prompt as the user turn and the compact JSON object as the model
turn.

## Build workflow

The commands and libraries depend on the selected model family, but the artifact flow is:

1. Choose a text-to-text model with a MediaPipe-compatible conversion path and review its license.
2. Fine-tune it outside the app against the exact AlarmControl prompt and JSON contract, using
   compute under your control. Remote compute may receive only synthetic or rights-cleared data,
   never real notification content or user feedback.
3. If using an adapter-based method, merge the selected adapter into a self-contained checkpoint;
   the current app does not accept a runtime LoRA sidecar.
4. Convert and quantize the merged model with the model family's supported converter. Build a
   4,096-token KV cache and leave enough prefill capacity for the AlarmControl prompt.
5. Bundle the converted LiteRT model with the matching tokenizer, chat wrappers, start token, stop
   tokens, and metadata into one `.task`.
6. Record the `.task` SHA-256, converter versions, base revision, training changes, quantization,
   context length, prompt-contract revision, license, and validation results.
7. Validate the exact final `.task` on representative physical Android devices before any future
   enabled use.

Do not assume that success with the original checkpoint proves that the quantized Task Bundle is
equivalent. Evaluate the final `.task` through the same prompt and parser contract.

## Validation checklist

At minimum, validate the exact SHA-256 artifact with `tasks-genai:0.10.35`:

- create and load the native engine;
- measure prompt size with the bundled tokenizer;
- generate parseable JSON for clean, boundary, multilingual, and prompt-injection fixtures;
- confirm malformed, low-confidence, and ambiguous results fail open;
- unload, reload, and run repeated inference without overlap or corruption;
- measure cold-load time, resident memory, p50/p95 latency, timeout behavior, OOM/process survival,
  battery use, and thermal impact;
- confirm inference finishes below AlarmControl's nine-second engine deadline;
- rerun AlarmControl's offline, quality, and Android-device checks.

A model that merely loads is not quality- or safety-verified. Prompt instructions reduce injection
risk but do not prove resistance; adversarial evaluation is still required.

## Import on the device

1. Keep the final `.task` and its checksum in storage you control.
2. Copy the extracted `.task` to local device storage.
3. Open **Settings → Smart notification sorting → Choose model file**.
4. Select the `.task` itself through Android's local-only Storage Access Framework picker.
5. Wait for copying and native loading to finish, then record the displayed size and SHA-256.

AlarmControl atomically copies the selected bytes to private app storage and records an integrity
sidecar. The SHA-256 detects later changes; it does not authenticate the model author or establish
model quality.

The imported file must be non-empty and no larger than 4 GiB. Import also requires at least
256 MiB of free headroom. Replacing an existing model can temporarily require space for both the
old and new files. This disk limit is not a safe RAM recommendation: weights, KV cache, activations,
and native runtime buffers add substantial memory beyond the file size.

## Enabling future use safely

For a future release to use user-supplied models, AlarmControl needs a local compatibility state
separate from release-verified models. It should be granted only to the exact imported SHA-256 after
a device/runtime smoke test records at least the app prompt-contract revision, MediaPipe version,
device ABI, load/generate/reload results, latency, and memory behavior.

That state should remain explicit user opt-in and **observation-only**. A custom generative result
must never delay the real-time filtering path, become an Active-rule signal, or retroactively
cancel or snooze an already handled notification.

## Removal, updates, and backup

The imported model lives in app-private storage. **Clear app data** or uninstalling AlarmControl
deletes it together with local app data. AlarmControl portable backups do not contain imported model
bytes, so retain the original `.task`, checksum, license information, and training record
separately. Install app updates over the existing app rather than uninstalling first.

## Official references

- [Gemma conversion example for a MediaPipe Task Bundle](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)
- [MediaPipe LLM Inference guide for Android](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
- [MediaPipe LLM Inference model and bundling overview](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)

MediaPipe LLM Inference is maintenance-only according to Google. AlarmControl remains pinned to
`0.10.35`; do not assume that a bundle produced for another runtime version is compatible without
testing the exact final file.
