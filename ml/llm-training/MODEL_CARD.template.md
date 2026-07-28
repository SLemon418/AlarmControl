# AlarmControl Gemma 3 semantic notification classifier

## Status

Replace this section with the training date, exact base-model revision, evaluator commit, and
whether every release gate passed. A checkpoint or `.task` must not be described as production
ready until the frozen merged candidate passes the one-shot final holdout, and the quantized
`.task` passes non-final validation/development agreement plus physical-device checks. Do not rerun
the one-shot final holdout on the quantized artifact. A holdout exposed before the candidate is
frozen is retired and cannot supply release evidence; create its replacement only after every
non-final result is fixed.

- Validation gate: `not run | pass | fail` (metrics path and model hash)
- Development-regression gate: `not run | pass | fail` (metrics path)
- One-shot final holdout: `not created | sealed | pass | fail` (hash, run count, and evidence path)
- LiteRT/MediaPipe `.task` conversion: `not run | pass | fail` (task SHA-256)
- Physical Android validation: `not run | pass | fail` (device and measurements)

## Intended use

This model classifies a single English or Korean Android notification into exactly one of:
`MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`, or `AMBIGUOUS`. It emits
one compact JSON object for AlarmControl's optional on-device semantic signal. It is not a chatbot,
content generator, safety classifier, financial adviser, or source of facts.

The app keeps rule matching and the bundled classical classifier as its core path. This LLM is
optional, off by default, and cannot trigger an active automatic action unless the user separately
enables that setting.

## Training data

- Source: `data/seed_examples.jsonl`
- Composition: 210 synthetic, content-free examples; seven balanced intents; balanced English and
  Korean; fixed 140/42/28 train/validation/development splits
- Rendered full dataset: 420/84/28 train/validation/development rows; record any curriculum subset
  separately with its exact manifest hash
- Hard cases: promotion disguised as account or delivery notices, real transactions, OTP/security
  alerts, mixed notices, prompt injection inside notification text, control characters, and
  insufficient context
- Real notification content: none

Do not add private notification exports. Only use synthetic, rights-cleared, or explicitly opted-in
and irreversibly redacted examples. App data must never be sent to hosted training services.

## Base and adaptation

- Base: `google/gemma-3-1b-it` (record the exact revision here)
- Method: record `lora` or `full`, hyperparameters, seed, and training hardware here
- Deployment: merged Hugging Face safetensors -> LiteRT dynamic INT8 -> MediaPipe Task Bundle
- Context: 4,096 combined prompt and output tokens
- Decode: greedy (`topK=1`, `temperature=0`, fixed seed)

## Required evaluation record

Record separately labeled validation, development-regression, and one-shot-final metrics paths,
model hashes, commands, and results here. Then add:

- quantized `.task` agreement with the merged checkpoint
- JSON and enum validity
- macro-F1 and every class's recall
- `MARKETING` precision
- prompt-injection hard-set accuracy
- non-`AMBIGUOUS` prompt-injection accuracy and false-`AMBIGUOUS` abstention rate
- wrong actionable prediction rate at the app's 0.6 gate
- English and Korean accuracy
- actual device cold-load time, RSS, p50/p95 latency, thermal behavior, and repeated-call result

The model-generated `confidence` field is not a calibrated probability until a separate calibration
study demonstrates that it is. Keep LLM automatic actions off by default.

## Known limitations

- The synthetic seed set is intentionally small and cannot represent every app, locale, scam, or
  mixed-intent notification.
- The model sees title and body text only, not package, channel, sender identity, or external facts.
- `AMBIGUOUS` and scores below 0.6 are abstentions, not evidence that a notification is safe.
- Prompt injection defenses reduce risk but do not prove that arbitrary hostile text is harmless.
- Performance and compatibility vary by Android SoC and MediaPipe runtime.

## License and distribution

This is a modified Gemma model and remains subject to the Gemma Terms of Use and prohibited-use
policy. Before distributing a derivative, provide the required Gemma terms copy, modified-model
notice, and `NOTICE` text, and ensure downstream terms preserve the required restrictions. Record
the task SHA-256 and distribute documentation alongside the `.task`; the app itself imports only
the extracted `.task` file.
