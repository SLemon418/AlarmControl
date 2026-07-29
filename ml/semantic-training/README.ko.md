# 의미 분류 모델 학습·릴리스 파이프라인

이 디렉터리는 AlarmControl의 7분류 경량 의미 분류기용 합성 데이터,
CPU 학습, LiteRT 변환, 평가, 패키징 도구를 포함한다. 앱 데이터, 실제 알림
내용, 패키지 메타데이터를 읽지 않으며 Android 런타임 네트워크 코드와
분리되어 있다.

라벨 순서는 다음으로 고정된다.

```text
MARKETING
TRANSACTIONAL
SECURITY
DELIVERY
SOCIAL
OTHER
AMBIGUOUS
```

## 배포 후보 상태(2026-07-29)

`koelectra-primary-v6` epoch 3을 `ml/src/main/assets/`의 정확한 네 파일
payload로 배포했다. 분류기는 47,050,248바이트(44.87MiB) dynamic-INT8
LiteRT 모델이며 고정 `[1, 128]` WordPiece 입력과 float32 logits 7개를
사용한다. 런타임 임계값은 다음의 정확한 float32 값이다.

- 일반: `0.949999988079071`
- `MARKETING`: `0.9917579889297485`

최종 새 blind v8은 고정한 이 TFLite 후보를 한국어·영어·혼합 언어 균형
420행에서 정확히 한 번 평가했다. `artifacts/sealed-holdout-v8/`에는
aggregate 근거만 남겼고, raw row와 행별 prediction은 평가 후 삭제했다.

| 지표 | 전체 | 영어 | 한국어 | 혼합 |
|---|---:|---:|---:|---:|
| Raw macro-F1 | 0.954401 | 0.891103 | 0.985678 | 0.985678 |
| Trusted coverage | 0.855556 | 0.650000 | 0.950000 | 0.966667 |

Raw MARKETING precision은 `1.000000`, runtime macro-F1은 `0.879211`이고,
신뢰 가능한 비-MARKETING→MARKETING 오탐은 0건이다.

Galaxy Note20 5G(`SM-N981N`, arm64-v8a)에서 실제 번들 asset의 모델 로딩,
tokenizer parity, 입력에 따라 달라지는 logits 계측을 통과했다. cold 초기화는
121.037ms이고 측정 추론 40회의 p50은 65.506ms, p95는 68.623ms였다.
프로세스 PSS는 45,531→96,117KiB, RSS는 122,420→173,308KiB,
native heap은 5,972,016→22,291,072바이트로 변했다. OOM은 없었고
thermal status는 `0`, 측정 charge counter는 3,332,000µAh로 유지됐다.
이 값은 해당 기기와 실행의 측정치다.

검증한 `bundleRelease` 산출물은 98,983,815바이트(94.40MiB)로 전체
물리 상한 105MiB 미만이지만 App Bundle 호환성 회귀 확인용일 뿐 배포 후보가
아니다. GitHub Releases에는 별도로 검증한 서명 범용 APK를 게시한다.
GitHub는 ABI를 자동 선택하지 않으므로 이 APK에는 지원하는 native library를
모두 포함하며 ABI와 무관한 의미 모델도 모든 호환 설치에 함께 전달된다.

런타임 통합은 규칙 우선이다. **활성** 규칙이 의미 판정을 필요로 할 때만
커밋 전에 `REALTIME` urgency로 추론한다. 관찰 전용 의미 작업은 활성
판정을 이미 커밋한 뒤 `BACKGROUND` urgency로 실행하므로 늦게 끝난
결과가 처리된 알림을 바꿀 수 없다. native 실행기는 실행 1개와 대기
1개만 허용하며, 실시간 작업은 대기 중인 백그라운드 작업만 밀어낼 수 있다.

기본 데이터셋과 경량 테스트는 다음과 같이 실행한다.

```bash
python3 ml/semantic-training/build_dataset.py
python3 -m unittest discover -s ml/semantic-training/tests -v
```

기본 출력은 Git에서 제외된
`ml/semantic-training/artifacts/dataset-v6/`에 생성된다. 분할은 합성
family 단위로 분리되며, 앱이나 실제 사용자 알림에서 가져온 예시는 없다.
전체는 4,410 family, 26,460 row, 13,230 clean/injection pair이다.
locale별 row 수는 한국어 7,980, 영어 10,500, 혼합 7,980이며,
train/validation/test는 각각 21,168/2,646/2,646 row이다. 한국어와
혼합 locale은 intent별 family를 152/19/19로, 영어는
200/25/25로 나눈다.

## 저장공간·CPU 가드

무거운 학습과 변환은 전용 실행 루트 하나에서만 수행한다. 기존 디렉터리에
가드 마커를 한 번 만들고, 계획을 확인한 다음 모든 무거운 하위 프로세스를
`launch --apply`로 실행한다.

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

기본 정책은 실행 루트가 80 GiB에 도달하면 정리를 요구하고, 허용된
후보를 오래된 순서로 삭제해 70 GiB 미만을 목표로 한다. 100 GiB
도달 또는 파일시스템 여유 공간 100 GiB 미만은 hard stop이다. 삭제
후보는 `cache/`, `tmp/`, `failed-conversions/`, `checkpoints/`,
`seed-weights/` 아래에서 `.disposable` 마커가 있는 디렉터리로 한정된다.
모델, 데이터셋, manifest, tokenizer, config, evaluation,
selected/best/last, model card 및 `.tflite` 경로는 보호된다. 가드는
실행 전후에 모두 검사하며 CPU 전용, 수학 스레드 2개, 하위 프로세스
1개 환경을 강제한다. 각 경계의 JSON 보고서를 확인하고 hard stop이나
미완료 정리는 실패로 취급한다.

## CPU 전용 KoELECTRA 학습

학습기는 로컬 Hugging Face 호환 KoELECTRA 디렉터리만 허용하며
`local_files_only=True`와 Transformers offline 모드를 강제한다.
입력은 Android와 동일하게 제목과 본문을 ASCII 공백 하나로 결합하고
NFC 정규화하며 최대 128 WordPiece 토큰으로 자른다.

```bash
python3 ml/semantic-training/train_koelectra.py \
  --base-model /absolute/path/to/local-koelectra \
  --dataset /absolute/path/to/dataset-v6/dataset.jsonl \
  --output /absolute/path/to/training-output \
  --epochs 3 \
  --batch-size 8 \
  --learning-rate 2e-5 \
  --max-rss-bytes 4294967296
```

검증 예측과 안전한 float32 임계값 선택은 다음과 같다.

```bash
python3 ml/semantic-training/predict_koelectra.py \
  --model-dir /absolute/path/to/training-output/best \
  --input /absolute/path/to/dataset-v6/dataset.jsonl \
  --split validation \
  --output /absolute/path/to/validation-predictions.jsonl

python3 ml/semantic-training/evaluate_semantic.py select-threshold \
  /absolute/path/to/validation-predictions.jsonl \
  --source-manifest /absolute/path/to/dataset-v6/manifest.json \
  --output /absolute/path/to/selected-threshold.json
```

일반 릴리스 임계값은 정확한 float32 하한 `0.949999988079071`로
고정한다. MARKETING 임계값만 validation의 비-MARKETING→MARKETING
최대 점수 바로 다음 float32 값으로 올린다. 선택 스키마 v4는 두 값을
루트의 `general_threshold`, `marketing_threshold` 필드로 기록하며,
이 둘을 test와 blind 게이트 및 parity에 변경 없이 전달해야 한다.
아래 예시의 MARKETING 값 `0.9917579889297485`는 현재 v6 개발 선택값이며,
다른 후보는 그 후보의 validation 선택 필드를 사용해야 한다.
test나 blind 데이터로 어느 임계값도 조정하면 안 된다.
`gate`와 `select-threshold`는 dataset manifest v2/v3/v4/v5/v6 또는
sealed-holdout manifest v1/v2만 허용한다.
기본 게이트는 raw macro-F1 0.85 이상, MARKETING precision 0.90 이상,
locale별 macro-F1 0.80 이상, 신뢰 가능한 actionable coverage 전체
0.60 및 locale별 0.40 이상, 신뢰 가능한 비-MARKETING의 MARKETING
오탐 0건을 요구한다. 낮은 신뢰도와 `AMBIGUOUS`는 fail-open한다.

## LiteRT dynamic-INT8 변환

배포 후보는 명시적으로 dynamic INT8 변환을 요청한다.

```bash
/absolute/path/to/litert-venv/bin/python \
  ml/semantic-training/convert_koelectra_litert.py \
  --model-dir /absolute/path/to/training-output/best \
  --output-dir /absolute/path/to/litert-output \
  --quantization dynamic-int8
```

`--quantization auto`는 변환 실험에서만 사용할 수 있다. experimental
dynamic-INT8 backend가 실패하면 float32로 fallback할 수 있지만, 패키저는
`applied: "dynamic-int8"`이 아닌 결과를 거부한다. 변환 후 LiteRT
interpreter로 산출물을 다시 열어 INT8 tensor와 `QUANTIZE` operator가
각각 하나 이상인지 검사하고 `quantization_audit`에 기록한다. 감사 필드가
없거나 실패했거나 count가 모순이면 배포할 수 없다. 모델 크기 상한은
45 MiB이며 입력은 `[1, 128]` int32, 출력은 `[1, 7]` float32 logits이다.

## 새 blind 원칙과 backend parity

후보 고정 전에 이미 열람하거나 평가한 모든 holdout과 aggregate gate,
즉 기존 v1–v6 세트는 개발용이다. 회귀 분석에는 쓸 수 있지만 최종
blind 근거로 재사용할 수 없다.

먼저 model bundle, vocabulary, TFLite artifact, conversion manifest,
float32 임계값을 모두 고정한다. 그 뒤 새로 격리한 blind 세트에서 정확히
그 TFLite 후보를 한 번만 실행한다. 결과를 보고 임계값 조정, 재학습,
재변환을 하면 안 된다. raw blind row와 행별 prediction은 저장소 밖의
격리 공간에 두고, 저장소에는 aggregate manifest, 통과한 aggregate gate,
각 SHA-256만 남긴다. 후보가 바뀌면 다른 새 blind 세트가 필요하다.

blind 실행 전 개발 test split에서 TFLite 게이트와 PyTorch↔TFLite
aggregate parity를 만든다.

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

패키저는 2,646개 test row 전체, 동일 dataset/model/vocabulary/conversion
provenance, TFLite 개발 게이트와 일치하는 품질 지표, 일관된 agreement
count, 새로 생긴 위험한 trusted MARKETING 0건을 요구한다.

## 패키징과 릴리스 증거

validation 임계값 선택, TFLite 개발 test 게이트, test parity, 새 blind
aggregate 게이트가 모두 통과한 뒤 패키징한다.

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

산출 manifest는 conversion manifest, threshold selection, development
test gate, test parity report, sealed blind gate의 SHA-256을 모두 묶는다.
또 threshold/test/blind provenance와 quantization audit를 포함한다.
정확히 네 Android asset 파일을 원자적으로 만들지만 Android 모듈로
자동 복사하지는 않는다.
packaging과 release evidence에는 선택한 동일한 `best/` 또는
`checkpoint/` bundle을 `--model-dir`로 전달해야 한다.

마지막으로 raw blind row를 읽지 않는 aggregate-only 릴리스 증거와
model card를 만든다.

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

`build_release_evidence.py`는 명시적으로 선택한 모델 bundle을 해시하고
`checkpoint.json`의 epoch와 metric을 training manifest와 대조한다. 선택된
epoch는 training-best epoch와 별도로 기록한다. 출력은 결정적인
`evidence.json`과 `MODEL_CARD.md` 두 파일이다. 두 파일은 aggregate만
포함하며 알림 내용, 행 식별자, 로컬 절대 경로를 거부한다.
