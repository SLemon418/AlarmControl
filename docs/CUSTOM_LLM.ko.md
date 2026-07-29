# 사용자 제작 로컬 LLM 가져오기

**[English](CUSTOM_LLM.md)**

AlarmControl은 사용자가 제작한 모델을 앱 전용 저장소로 복사할 수 있지만 생성형 LLM을
제공·학습·변환·호스팅·다운로드하지 않습니다. 모델 확보, 학습, 변환, 라이선스 준수와 검증은
사용자 책임입니다.

학습과 변환은 사용자가 관리하는 개발용 컴퓨터에서 Android 앱 밖에서 수행합니다. 설치된 앱은
기반 모델을 학습하지 않으며 학습 데이터를 어디에도 전송하지 않습니다.

> **현재 릴리즈의 제한:** 모델을 가져오면 파일 무결성과 MediaPipe 네이티브 로드 가능
> 여부까지만 확인합니다. 가져온 모델은 모두 `UNVERIFIED` 상태이므로 이 릴리즈에는 자동
> 백그라운드 또는 수동 LLM 추론 경로가 없습니다. 일반 필터링은 계속 번들 분류기가 담당합니다.

## 별도의 번들 분류기

APK에는 이미 47,050,248바이트(44.87 MiB)의 7종 의미 분류기가 포함됩니다. 이것은 고정 출력을
내는 LiteRT 분류기이며 생성형 LLM이 아니므로 추가 파일이나 설정이 필요하지 않습니다. 사용자
제작 LLM을 가져오지 않아도 규칙과 이 번들 분류기가 기본 실시간 경로로 계속 동작합니다.
기본값은 켜짐이지만 **설정 → 스마트 알림 분류 → 알림 내용을 자동으로 구분**에서 끌 수
있습니다. 끄면 7종 의미 추론만 건너뛰며 별도의 사용자 LLM 설정은 바뀌지 않습니다.

## 필수 모델 형식

AlarmControl은 MediaPipe Tasks GenAI `0.10.35`에 고정되어 있습니다. 텍스트 입출력 전용이며
하나의 완결된 MediaPipe Task Bundle인 `.task` 파일만 받습니다. 번들에는 다음이 포함되어야
합니다.

- MediaPipe와 호환되는 multi-signature LiteRT 모델
- 해당 모델의 tokenizer
- 모델별 시작·중지 토큰
- 올바른 사용자/모델 prompt wrapper와 필수 metadata

다른 파일의 확장자만 바꿔도 변환되지 않습니다. GGUF, Ollama 파일, ONNX, safetensors,
Hugging Face 체크포인트 디렉터리, raw `.tflite`, LoRA adapter 단독 파일, `.task`를 담은 ZIP은
런타임 모델로 가져올 수 없습니다.

임의의 LLM이 자동으로 호환되는 것은 아닙니다. 해당 아키텍처와 연산을 MediaPipe LLM
Inference task용으로 내보낼 수 있는 converter가 있어야 합니다. 모델 계열마다 변환 mapping,
tokenizer 처리, chat template, 특수 token, KV cache layout과 양자화 방식이 다를 수 있습니다.
호환 converter가 없다면 그 모델을 사용하려면 AlarmControl의 런타임 또는 모델 adapter를 별도
검토해 변경해야 합니다.

## AlarmControl 추론 계약

학습과 변환은 다음 고정 계약을 유지해야 합니다.

| 항목 | 필수 값 |
|---|---|
| 런타임 | `com.google.mediapipe:tasks-genai:0.10.35` |
| modality | 텍스트 입력과 텍스트 출력만 사용 |
| context | prompt와 생성 결과를 합쳐 4,096 token |
| 출력 예약 | 4,096 token 안에서 128 token |
| decoding | `topK=1`, `topP=1.0`, `temperature=0`, 고정 random seed |
| 신뢰 기준 | `confidence >= 0.6`이며 intent가 `AMBIGUOUS`가 아님 |

Task에 컴파일된 KV cache 또는 context 길이는 4,096과 일치해야 합니다. MediaPipe의
`maxTokens`는 출력 전용 제한이 아니라 입력과 출력을 합친 제한입니다. AlarmControl은 출력에
128 token을 예약하므로 prompt에는 최대 3,968 model token을 사용할 수 있습니다.

AlarmControl은 알림 제목과 본문을 합치고 최대 2,000 UTF-16 code unit만 유지한 뒤, 내용을
신뢰하지 않는 JSON 문자열로 escape합니다. 필요하면 가져온 tokenizer로 측정해 다시 줄입니다.
패키지와 채널 식별자는 모델에 전달하지 않습니다.
`ml/src/main/kotlin/com/alarmcontrol/ml/llm/LlmPrompt.kt`의 실제 runtime prompt가 기준이며,
지도 학습 예제를 만들 때 그대로 사용해야 합니다.

유효한 intent label은 다음 7개뿐입니다.

```text
MARKETING
TRANSACTIONAL
SECURITY
DELIVERY
SOCIAL
OTHER
AMBIGUOUS
```

모델은 Markdown 없이 JSON 객체 하나를 반환해야 합니다.

```json
{"intent":"TRANSACTIONAL","confidence":0.91,"reason":"결제 완료"}
```

- `intent`는 위의 대문자 label 중 하나와 정확히 일치해야 합니다.
- `confidence`는 `0.0`부터 `1.0`까지의 유한한 숫자여야 합니다.
- `reason`은 문자열이어야 하며, runtime이 유지하는 최대 280자 안에서 짧게 작성합니다.
- 근거가 부족하거나 충돌하면 `AMBIGUOUS` 또는 `0.6` 미만의 confidence를 사용합니다.
- 별도 연구와 보정을 거치지 않은 모델 생성 confidence를 보정된 확률로 설명하면 안 됩니다.

잘못된 JSON, 누락되거나 타입이 다른 필드, 알 수 없는 label, 유한하지 않거나 범위를 벗어난
confidence, 낮은 confidence와 `AMBIGUOUS`는 모두 fail-open 처리됩니다.

## 학습 데이터

합성 데이터, 권리가 명확한 데이터 또는 그 밖에 합법적으로 준비한 데이터만 사용합니다.
AlarmControl은 학습용 알림 말뭉치를 내보내지 않습니다. 실제 알림 내용, 사용자 피드백 또는
LLM reasoning을 호스팅 notebook, 모델 제공자, telemetry 서비스나 다른 외부 시스템에
업로드하지 마세요.

학습 데이터에는 한국어, 영어와 혼합 언어 알림, 7개 label 전체, 어려운 분류 경계, 잘리거나
누락된 문맥, JSON·제어문자와 prompt injection 시도를 포함해야 합니다. 정확한 runtime
prompt를 user turn으로, 짧은 JSON 객체를 model turn으로 학습합니다.

## 빌드 절차

명령과 라이브러리는 선택한 모델 계열에 따라 달라지지만 산출물 흐름은 다음과 같습니다.

1. MediaPipe 호환 변환 경로가 있는 text-to-text 모델을 선택하고 라이선스를 확인합니다.
2. 사용자가 통제하는 연산 환경에서 앱 밖에서 정확한 AlarmControl prompt와 JSON 계약으로
   미세조정합니다. 원격 연산에는 합성 데이터나 권리가 명확한 데이터만 전달하고 실제 알림
   내용이나 사용자 피드백은 절대 전달하지 않습니다.
3. adapter 기반 방식이라면 선택한 adapter를 독립 실행 가능한 체크포인트로 병합합니다. 현재
   앱은 runtime LoRA sidecar를 받지 않습니다.
4. 모델 계열이 지원하는 converter로 병합 모델을 변환하고 양자화합니다. 4,096-token KV cache를
   만들고 AlarmControl prompt에 충분한 prefill 용량을 둡니다.
5. 변환한 LiteRT 모델과 일치하는 tokenizer, chat wrapper, 시작·중지 token, metadata를 하나의
   `.task`로 묶습니다.
6. `.task` SHA-256, converter 버전, base revision, 학습 변경, 양자화, context 길이,
   prompt-contract revision, 라이선스와 검증 결과를 기록합니다.
7. 향후 실제 사용을 활성화하기 전에 최종 `.task`를 대표적인 Android 실기기에서 검증합니다.

원본 체크포인트의 성공이 양자화된 Task Bundle의 동등성을 증명한다고 가정하면 안 됩니다.
최종 `.task`를 같은 prompt와 parser 계약으로 다시 평가해야 합니다.

## 검증 체크리스트

최소한 정확한 SHA-256 산출물을 `tasks-genai:0.10.35`에서 다음과 같이 검증합니다.

- 네이티브 engine 생성과 load
- 번들 tokenizer를 사용한 prompt token 수 측정
- 정상, 경계, 다국어와 prompt-injection fixture에서 파싱 가능한 JSON 생성
- malformed, 낮은 confidence와 ambiguous 결과의 fail-open 확인
- unload, reload와 반복 추론 시 겹침이나 손상이 없는지 확인
- cold load, 상주 메모리, p50/p95 지연, timeout, OOM과 프로세스 생존, 배터리 및 발열 측정
- AlarmControl 내부 9초 제한 안에 추론이 끝나는지 확인
- AlarmControl offline, 품질과 Android 실기기 검사 재실행

모델이 단순히 load되었다고 품질이나 안전성이 검증된 것은 아닙니다. Prompt 지시는 injection
위험을 줄이지만 저항성을 증명하지 않으므로 적대적 평가가 필요합니다.

## 기기에서 가져오기

1. 최종 `.task`와 checksum을 직접 관리하는 저장소에 보관합니다.
2. 압축을 푼 `.task`를 기기의 로컬 저장소로 복사합니다.
3. **설정 → 스마트 알림 분류 → 모델 파일 선택**을 엽니다.
4. Android의 로컬 전용 Storage Access Framework 선택기에서 `.task` 자체를 고릅니다.
5. 복사와 네이티브 load가 끝나면 화면에 표시된 크기와 SHA-256을 기록합니다.

AlarmControl은 선택한 byte를 앱 전용 저장소로 원자적으로 복사하고 무결성 sidecar를
기록합니다. SHA-256은 이후 변경을 찾지만 모델 제작자를 인증하거나 모델 품질을 증명하지
않습니다.

가져오는 파일은 비어 있지 않아야 하며 4 GiB 이하여야 합니다. 가져올 때 최소 256 MiB의 추가
여유 공간도 필요합니다. 기존 모델을 교체하는 동안에는 이전 파일과 새 파일을 함께 둘 공간이
일시적으로 필요할 수 있습니다. 이 저장 용량 제한은 안전한 RAM 권장값이 아닙니다. 가중치
외에도 KV cache, activation과 네이티브 runtime buffer가 상당한 메모리를 추가로 사용합니다.

## 향후 안전한 사용 활성화

향후 릴리즈가 사용자 제작 모델을 사용하려면 배포 시 검증한 모델과 구분되는 로컬 호환 상태가
필요합니다. 정확한 가져오기 SHA-256에 대해 실기기/runtime smoke test를 수행한 뒤 최소한 앱
prompt-contract revision, MediaPipe 버전, 기기 ABI, load·generate·reload 결과, 지연과 메모리
동작을 기록한 경우에만 이 상태를 부여해야 합니다.

이 상태도 명시적인 사용자 opt-in과 **관찰 전용**이어야 합니다. 사용자 제작 생성 결과가
실시간 필터링을 기다리게 하거나 Active 규칙 신호가 되거나 이미 처리한 알림을 나중에
취소·다시 알림 처리해서는 안 됩니다.

## 삭제, 업데이트와 백업

가져온 모델은 앱 전용 저장소에 있습니다. **앱 데이터 삭제** 또는 AlarmControl 제거 시 로컬
앱 데이터와 함께 삭제됩니다. AlarmControl 휴대용 백업에는 가져온 모델 byte가 포함되지
않으므로 원본 `.task`, checksum, 라이선스 정보와 학습 기록을 별도로 보관하세요. 앱을 먼저
제거하지 말고 기존 앱 위에 업데이트를 설치하세요.

## 공식 참고 자료

- [Gemma 모델의 MediaPipe Task Bundle 변환 예시](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)
- [Android용 MediaPipe LLM Inference 안내](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
- [MediaPipe LLM Inference 모델·번들 개요](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)

Google 문서상 MediaPipe LLM Inference는 maintenance-only입니다. AlarmControl은
`0.10.35`에 고정되어 있으므로 다른 runtime 버전용으로 만든 번들이 정확한 최종 파일 검증 없이
호환된다고 가정하면 안 됩니다.
