# AlarmControl LLM 모델 파이프라인

**[English](README.md)**

이 빌드 전용 도구는 사용자가 로컬에 준비한 Gemma 3 체크포인트를 AlarmControl의 선택형
7분류 의미 모델로 미세조정하고, 앱이 가져올 수 있는 단일 MediaPipe `.task`로 내보냅니다.
모델·알림·체크포인트·학습 도구는 APK에 포함되지 않습니다.

설치된 Android 앱은 계속 완전한 무네트워크 상태입니다. 개발 머신은 사용자가 약관에
동의한 뒤 빌드 의존성과 원본 체크포인트를 내려받을 수 있습니다. 토큰, 다운로드 도구,
학습 환경과 가중치는 빌드 전용이며 AlarmControl APK에 포함되지 않습니다. 합성 자료나
사용권이 확보된 자료를 쓰는 개발자 전용 학습 작업도 네트워크를 사용할 수 있지만 실제
사용자 알림 내용을 입력받아서는 안 됩니다.

## 현재 산출물 상태

저장소에는 재현 가능한 데이터, 학습, 엄격 평가, LiteRT 변환, MediaPipe 번들링, 모델 카드,
배포 패키징 경로가 있습니다. 다음 이유로 Gemma 원본이나 생성된 `.task`는 의도적으로 넣지
않았습니다.

- Gemma 접근에는 사용자의 약관 동의와 인증이 필요하며 스크립트가 대신 동의하면 안 됩니다.
- 병합 체크포인트, 양자화 `.task`, 대표 Android 실기기가 모두 아래 기준을 통과하기 전에는
  배포 가능한 모델이 아닙니다.
- 모델은 Git이나 APK에 넣기에는 너무 큽니다. 사용자는 압축에서 꺼낸 `.task`를 앱의
  로컬 전용 Storage Access Framework 선택기로 가져옵니다.

상태 기록일: **2026-07-29**. 현재 로컬 1B 연구 산출물은 `google/gemma-3-1b-it` revision
`dcc83ea841ab6100d6b47a070329e1ba4cf78752`를 사용합니다. v13 커리큘럼 체크포인트는
엄격 validation을 통과했지만 같은 기준의 개발 회귀에서 실패했으므로 **배포 후보가
아닙니다**.

| Gate | macro-F1 | clean | injection | JSON | 잘못된 행동 가능 예측 | 결과 |
|---|---:|---:|---:|---:|---:|---|
| validation (84) | 0.8860 | 0.9048 | 0.8571 | 1.0000 | 0.0476 | 통과 |
| 개발 회귀 (28) | 0.7701 | 1.0000 | 0.4167 | 1.0000 | 0.0000 | 실패 |

기록된 계보는 원본 1B -> `2e-4`의 v10 rank-16 LoRA checkpoint 84 -> 안전 병합 -> 70행
커리큘럼에서 `5e-5`, 2 epoch의 새 rank-16 LoRA입니다. 두 단계 seed는 `20260727`이고,
최종 단계는 Apple MPS `bfloat16`을 사용했습니다. 이는 실패한 연구 체크포인트의
재현 정보이지 배포가 승인된 레시피가 아닙니다.

한 문장 prompt-only 후속 실험도 validation에서 실패해 되돌렸습니다. 변환과 실기기
검증도 실행하지 않았습니다. 2026-07-28에 넓은 범위의 소스 검색 결과에 기존 최종
holdout 문구가 실수로 포함됐습니다. 이 세트는 폐기하며 최종 근거로 사용해서는 안 됩니다.
향후 후보를 고정하고 모든 비최종 gate를 통과한 뒤, 학습 맥락과 분리된 작성자가 새
holdout을 만들고 해시를 고정해 정확히 한 번만 평가해야 합니다. 현재 16GB Apple Silicon
호스트는 변환 지침의 64GB 이상 Linux 요구사항을 충족하지 못하고, 고정한 converter
wheel은 별도로 x86_64 전용입니다. 향후 후보가 두 비최종 gate를 모두 통과한 뒤 적합한
호스트가 필요합니다.

더 작은 로컬 **Gemma 3 270M** 미세조정 체크포인트도 배포 gate에서 탈락했습니다.

| Gate | macro-F1 | MARKETING precision | 행동 가능 정확도 | 잘못된 행동 가능 |
|---|---:|---:|---:|---:|
| validation (84) | 0.6093 | 0.6000 | 0.6935 | 0.2262 |
| 개발 회귀 (28) | 0.6724 | 0.5000 | 0.6957 | 0.2500 |

제한한 `dynamic_int4_block128` 정적 프리플라이트도 아래와 같이 현재 macOS ARM64
host에서 막혔습니다. 270M 모델 구성, 변환, `.tflite`, `.task`, MediaPipe Tasks GenAI
`0.10.35` 호환성, 실기기 결과는 어느 것도 확립되지 않았습니다. 따라서 AlarmControl은
선택형 LLM을 미검증 수동 경로로 유지하며 자동 백그라운드 호환 프로필을 제공하지 않습니다.

## 고정 앱 계약

- 입력: `LlmPrompt`가 제목+본문 최대 2,000 UTF-16 unit을 신뢰하지 않는 JSON 데이터로
  감싼 문자열이며 패키지·채널 정보는 포함하지 않음. 런타임은 가져온 tokenizer로 실제
  토큰 수를 재고, 출력 reserve를 지키기 위해 필요하면 입력 prefix를 더 줄임
- 의도: `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`,
  `AMBIGUOUS`
- 출력: `intent`, `0..1` 숫자 `confidence`, 짧은 `reason`이 있는 JSON 객체 하나
- 기권: `AMBIGUOUS` 또는 신뢰도 `0.6` 미만
- 문맥: 변환 KV cache와 같은 입력+출력 합계 4,096토큰
- 디코딩: greedy(`topK=1`, `temperature=0`, 고정 seed)
- 런타임: 완전 로컬 MediaPipe Tasks GenAI 0.10.35, 관리자 제한 9초

`contract.py`는 Kotlin 프롬프트와 파서를 그대로 반영합니다. 핵심 상수나 의도 이름이
달라지면 단위 테스트가 실패합니다.

## 데이터

`data/seed_examples.jsonl`에는 사람이 작성한 내용 비식별 합성 seed 210개가 있습니다.
실제 알림이 아니며 알림 내보내기에서 생성한 자료도 아닙니다.

- 의도별 정확히 30개
- 영어/한국어 정확히 절반씩
- 원본 기준 train 140개, validation 42개, 개발 회귀 28개의 고정 균형 분할
- 모든 의도와 언어에 서로 독립적인 hard-boundary train·validation 예제 배치
- 금융 판촉과 실제 거래, OTP/보안, 배송, 소셜, 기기/뉴스/알람, 혼합·정보 부족,
  제어문자, 알림 본문의 prompt injection 포함

`prepare_dataset.py`는 다음 개발용 데이터셋을 결정론적으로 생성합니다.

- `train.jsonl`: 원본 train 140개 + prompt-injection wrapper 280개 = 420행
- `validation.jsonl`: 원본 validation 42개 + 학습 injection과 별도 template 계열을 쓰는
  prompt-injection 변형 42개 = 84행
- `test.jsonl`: 원본 test 28개를 변경 없이 유지한 개발 회귀용 데이터셋

기존 test 분할은 이미 파이프라인 개발에 참고했으므로 편향 없는 최종 근거가 아닙니다.
이전에 최종 세트로 지정한 `data/final_holdout_examples.jsonl`도 개발 중 노출되어
폐기했습니다. 평가하지 마세요. 후보·변환·실기기 결과를 모두 고정한 뒤에만 학습 맥락과
분리된 작성자가 seed와 겹치지 않는 새 합성 holdout을 만들고 해시를 기록해 정확히 한 번
평가합니다. 학습, prompt·hyperparameter 선택, 오류를 반영한 재시도에 사용해서는 안 됩니다.

이 작은 seed는 앱 계약을 가르치기 위한 것이며 폭넓은 운영 정확도를 증명하지 않습니다.
실제 알림 내보내기를 호스팅 노트북이나 학습 서비스에 업로드하지 마세요. 합성 자료,
사용권이 확보된 자료, 또는 명시적 동의를 받고 복원 불가능하게 비식별화한 자료만
추가해야 합니다.

## 1. 데이터 준비와 검증

데이터 생성은 Python 표준 라이브러리만 사용합니다. 명시적 파이프라인 테스트는 격리된
학습 환경을 사용합니다.

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

개발 중에는 unrestricted test discovery를 사용하지 마세요. `FinalHoldoutTest`는 폐기한
원본을 읽으므로 어떤 후보 근거에서도 제외합니다. 생성 파일은 Git에서 제외된
`artifacts/dataset/`에 놓이며
train 420행, validation 84행, 호환성을 위해 `test.jsonl`이라는 이름을 유지한 개발 회귀
28행입니다.

## 2. 사용자가 직접 원본 모델 준비

모델 제공자 페이지에서 [Gemma 약관](https://ai.google.dev/gemma/terms)을 읽고 동의한 뒤
로컬에서 인증하고, 저장소 밖 절대 경로에 **고정 revision**을 받습니다. 약관 동의 후 예:

```sh
hf auth login
hf download google/gemma-3-1b-it \
  --revision <PINNED_COMMIT> \
  --local-dir /absolute/private/path/gemma-3-1b-it
```

Hugging Face 토큰, 원본 모델, 약관 동의 기록을 저장소에 넣지 마세요. 학습 스크립트는
Hugging Face와 Transformers를 오프라인 모드로 설정하고 로컬 디렉터리만 받습니다.

## 3. 미세조정과 병합

Python 3.11 또는 3.12를 사용합니다. CUDA가 공식적인 고속 경로입니다. 기본 LoRA
레시피는 실험 목적으로 Apple MPS나 CPU도 사용할 수 있지만 시간과 메모리는 기기마다
다릅니다.

```sh
python3.12 -m venv .venv-train
./.venv-train/bin/pip install -r requirements-train.txt
./.venv-train/bin/python train.py \
  --base-model /absolute/private/path/gemma-3-<SIZE>-it \
  --base-revision <PINNED_COMMIT> \
  --method lora \
  --epochs <EPOCHS>
```

학습기를 실행하면 다음 동작을 하도록 구현되어 있습니다.

- 전체 런타임 프롬프트의 loss를 마스킹해 assistant JSON만 학습
- 기본 rank-16 LoRA로 attention projection 조정
- epoch 또는 고정 step마다 저장할 수 있으며 teacher-forced loss가 아니라 생성 지표로
  후보 선택
- 메모리가 제한된 MPS 호스트에서는 Trainer loss 평가를 생략해도 별도 엄격 생성 gate 유지
- 감사를 위해 adapter와 독립 병합 모델 저장
- 모델 허브나 보고 서비스에 연결하지 않음

충분한 학습 장비에서는 `--method full`로 전체 파라미터 SFT를 할 수 있습니다. 런타임
LoRA sidecar는 사용하지 않습니다. AlarmControl은 독립적인 파일 하나만 가져오며,
MediaPipe 런타임 LoRA는 GPU 전용 두 번째 아티팩트가 필요합니다.

## 4. 선택·병합과 개발 회귀 반복

모든 epoch checkpoint를 `validation.jsonl`에서 실제 생성으로 평가하고, 잘못된 고신뢰
행동 가능 예측 비율을 macro-F1과 마케팅 precision보다 우선해 선택합니다. teacher-forced
validation loss만으로 선택하지 않습니다. 선택한 adapter를 병합합니다.

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

호환성을 위해 이름을 유지한 `test.jsonl`은 변경하지 않은 28행 개발 회귀 데이터셋입니다.
결과는 진단용일 뿐입니다. 후보 개선에 참고할 수 있지만 변환이나 배포를 승인할 수 없고,
blind test 성능으로 보고해서도 안 됩니다.

아래 기존 명령은 폐기한 holdout을 렌더링하므로 현재 배포 근거를 위해서는 **실행하지
않습니다**.

```sh
python3 prepare_final_holdout.py

./.venv-train/bin/python evaluate.py \
  --model-dir artifacts/training/selected-merged \
  --dataset artifacts/final-holdout/test.jsonl \
  --output-dir artifacts/evaluation/final
```

최종 평가 전에 모델, prompt, tokenizer, decoding, 변환, 실기기 결과와 모든
hyperparameter를 고정한 뒤 위에서 설명한 학습 맥락 분리 절차로 이 기존 원본과 고정
해시를 교체해야 합니다. 교체한 원본 해시, 생성 manifest, prediction, metric, 모델 해시,
정확한 명령을 배포 근거로 보존합니다. 최종 holdout 오류를 보고 조정한 뒤 같은 holdout을
재실행하면 안 됩니다. 1회 gate 실패는 해당 배포 후보의 실패입니다.

최종 평가에서 JSON 파싱률 100%, macro-F1 `0.85` 이상, 마케팅 precision `0.90` 이상,
clean·injection·비-AMBIGUOUS injection 정확도가 각각 `0.85` 이상, 전체 행 중 잘못된
고신뢰 행동 가능 예측 비율이 `0.05` 이하여야 합니다. 평가기는 잘못된 `AMBIGUOUS`
기권도 별도로 기록합니다. 이는 최소 기준이지 보정 증명이 아닙니다. 별도 calibration
연구 없이 모델이 작성한 `confidence`를 보정된 확률이라고 설명하면 안 됩니다.

## 5. Gemma 3 270M INT4 호환성 프리플라이트

제한형 probe는 Gemma 3 270M과 `dynamic_int4_block128` 조합을 대상으로 합니다.
제어된 비교를 위해 `--model-size 1b`와 기존 양자화 옵션도 유지하지만 어느 크기도
자동 백그라운드 호환 후보로 검증되지 않았습니다.

정확히 고정된 별도 변환 환경을 사용합니다. 스크립트는 완전한 로컬 모델 디렉터리
(`config.json`, `tokenizer.model`, 비어 있지 않은 `model*.safetensors`)만 받고,
위험한 출력 경로와 충돌을 거부하며 prefill/KV 길이를 앱의 4,096토큰 컨텍스트로
제한합니다. Hugging Face/Transformers 오프라인 모드를 강제하고 가속기 장치를 숨기며
일반 수학 라이브러리를 CPU 스레드 2개로 제한합니다. 모델 다운로드 기능은 없습니다.

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

프리플라이트의 목적은 모델을 구성하거나 가중치를 변환하지 않고 고정한 LiteRT Torch
API의 `build_model_270m`, INT4 recipe, 변환 진입점, transposed KV cache, export
설정을 확인하는 것입니다. 현재 macOS ARM64 host에서는 로컬 모델 입력 검증과 분리한
`kv_cache`, `export_config` import는 통과했습니다. 그러나 Gemma 3 모듈 또는 converter를
import하면 exit code 134로 중단됩니다.

```text
Option 'info-output-file' registered more than once
```

이는 LLVM 옵션 중복 등록 충돌이며
`artifacts/litert-270m-preflight/compatibility-preflight.json`에
`BLOCKED_BY_HOST_IMPORT_CONFLICT`로 기록했습니다. 모델은 구성하지 않았고 변환도
실행하지 않았으며 `.tflite`나 `.task`도 만들지 않았습니다. MediaPipe Tasks GenAI
`0.10.35`와 실기기 호환성은 계속 `UNVERIFIED`입니다.

현재 작업 정책에서는 무거운 프로세스를 한 번에 하나만 실행하고 CPU 스레드는 2개,
RSS는 4GiB로 제한합니다. 이 host에서는 `--preflight-only`를 제거하거나 dependency
version을 임의로 바꿔 import 중단을 우회하면 안 됩니다. 향후 실험은 지원되는 격리
host에서 고정한 recipe를 유지하고 첫 메모리 제한 실패 시 중단해야 합니다.

생성된 중간 `.tflite`는 AlarmControl에 직접 가져올 수 없습니다.

## 6. MediaPipe Task Bundle 생성

MediaPipe Python 의존성이 변환기와 충돌하지 않도록 또 다른 환경을 사용합니다.

```sh
python3.12 -m venv .venv-bundle
./.venv-bundle/bin/pip install -r requirements-bundle.txt
./.venv-bundle/bin/python bundle_task.py \
  --tflite-model artifacts/litert/<CONVERTER_OUTPUT>.tflite \
  --model-dir artifacts/training/selected-merged
```

최종 `.task`는 LiteRT 모델, Gemma tokenizer, prompt template, stop token, metadata를
포함합니다. GGUF, Ollama 파일, safetensors, 중간 `.tflite`는 대체할 수 없습니다.

## 7. 양자화 결과와 실기기 검증

정적 프리플라이트는 MediaPipe Tasks GenAI `0.10.35` 호환성을 증명하지 않습니다.
제한된 변환에 성공한 뒤에도 번들링과 실기기 검증이 필요합니다. 최종 `.task`에 대해:

1. 고정한 MediaPipe 도구로 번들링하고 결과 구조와 checksum을 검증합니다.
2. MediaPipe로 validation 및 개발 회귀 fixture를 실행하고 같은 파싱·정확도 기준을
   적용합니다. 1회 최종 holdout은 다시 열거나 재실행하지 않습니다.
3. 이 비최종 fixture에서 병합 체크포인트와 양자화 모델의 label 일치율을 기록합니다.
4. AlarmControl 설정에서 가져와 `Ready`, 표시된 크기와 SHA-256을 확인합니다.
5. Note20에서 create/load, `sizeInTokens`, generate, unload/reload, idle TTL 해제를
   실행한 뒤에만 해당 정확한 모델 SHA를 검증됨으로 취급합니다.
6. 대표 실기기에서 cold load, RSS, p50/p95 추론 시간, 반복 호출 안정성, OOM, 발열을
   기록합니다. MediaPipe LLM 성능은 에뮬레이터로 신뢰성 있게 검증할 수 없습니다.
7. p95가 관리자 제한 9초보다 짧은지 확인합니다.
8. 배포 근거가 충분하지 않으면 LLM 자동 동작은 끈 상태로 유지합니다.
9. 일반 Gradle 테스트, 품질 검사, 오프라인 가드를 실행합니다.

## 8. 배포 패키지

`MODEL_CARD.template.md`에 고정 base revision, 변경사항, 지표, 실기기 결과를 채웁니다.
배포자가 직접 해당 Gemma 약관 사본을 준비한 뒤 패키징합니다.

```sh
python3 make_release.py \
  --task artifacts/alarmcontrol-gemma3-270m-dynint4-block128-kv4096.task \
  --model-card /path/to/completed/MODEL_CARD.md \
  --gemma-terms /path/to/GEMMA_TERMS.pdf
```

ZIP에는 `.task`, 모델 카드, NOTICE, 약관 사본, checksum이 들어갑니다. 사용자는 압축을
풀고 AlarmControl에서 `.task`를 선택해야 합니다. 파생 모델은 계속
[Gemma 약관](https://ai.google.dev/gemma/terms)과
[금지 사용 정책](https://ai.google.dev/gemma/prohibited_use_policy)의 적용을 받습니다.

## 참고

- [Google: Gemma 텍스트 모델 미세조정](https://ai.google.dev/gemma/docs/core/huggingface_text_finetune_qlora)
- [Google: Gemma safetensors를 MediaPipe Task로 변환](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)
- [Google: Android MediaPipe LLM Inference](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)

Google 문서상 MediaPipe LLM Inference는 현재 maintenance-only이지만, 이 프로젝트에서는
해당 엔진이 이번 milestone의 잠긴 결정입니다. LiteRT-LM 전환은 별도 검토 작업입니다.
