# AlarmControl

[English](README.md) | **한국어**

**개인정보 보호를 최우선으로 하는 오프라인 우선 Android 알림·알람 필터링 앱입니다.**

AlarmControl은 규칙 기반 필터 엔진과 사용자 교정을 학습하는 경량 온디바이스 ML
분류기를 결합해 알림을 기기 안에서 분류하고, 취소하고, 다시 알리도록 합니다. 클라우드,
원격 분석, 계정, **설치된 앱의 네트워크 호출**이 없으며 알림 데이터는 휴대전화 밖으로
나가지 않습니다.

> 이 프로젝트에서 “알람”은 `NotificationListenerService`가 감지하는 알람 카테고리
> **알림**을 뜻합니다. 앱은 `AlarmManager`로 알람을 예약하지 않으며 다른 앱의 시스템
> 알람이나 헤드업 알림을 표시 전에 차단할 수 없습니다. 가능한 동작은 알림 읽기,
> **취소**, **다시 알림**과 로컬 판정 기록입니다.

---

## 주요 기능

### 🔕 규칙 기반 알림 필터링

- 패키지, 제목/본문 키워드, Android 카테고리, 채널, 진행 중 여부, ML 카테고리,
  시간대, 빈도, 중요도·대화·포그라운드 서비스, 7종 의미 분류를 조건으로 사용할 수 있습니다.
- `AllOf` / `AnyOf` / `Not`을 이용한 깊게 중첩된 복합 규칙을 지원합니다.
- 규칙은 **활성** 또는 **관찰** 모드로 실행됩니다. 활성 규칙의 첫 일치만 실제 동작을
  수행하고, 관찰 규칙은 별도로 평가해 예상 동작만 기록합니다.
- 빈도 규칙은 패키지 또는 패키지+채널 범위에서 1분~24시간을 집계합니다. 현재 알림도
  횟수에 포함하며 필요한 값이 없으면 `UNKNOWN`이 되어 파괴적 동작을 실행하지 않습니다.
- 각 규칙은 **취소**, **다시 알림**, **기록만**, **유지** 동작으로 연결됩니다.
- 매칭 엔진은 순수 Kotlin이며, 조건 단락 평가와 활성 규칙 메모리 캐시를 사용합니다.
- 새 규칙은 최근 관찰한 앱·채널을 검색해 고르고 동작·시간대·반복 조건을 단계적으로
  선택하는 안내형 편집기로 시작합니다. 취소·다시 알림 초안은 안전한 **관찰** 모드가
  기본이며, 필요하면 검증된 패키지명 입력이나 전체 복합 조건 편집기로 전환할 수 있습니다.
- 목록과 통계용 판정 결과는 패키지·채널, 카테고리, 동작, 시간 같은 최소 메타데이터만
  저장합니다. 선택형 암호화 상세 내용은 아래 개인정보 설정에 따라 별도로 격리합니다.

### 🧠 온디바이스 ML 분류와 점진적 학습

- 앱에 포함된 LiteRT/TensorFlow Lite 분류기가 알림을 프로모션, 소셜, 뉴스, 알람 등의
  카테고리로 분류합니다.
- 모델, 어휘, 라벨은 `:ml` 에셋에 함께 포함되어 계약 불일치를 방지합니다.
- 모델이 없거나 신뢰도가 낮으면 ML 결과를 사용하지 않고 규칙 엔진만으로 정상 동작합니다.
- 사용자가 카테고리를 교정하면 패키지별 로컬 집계에 축소 사전분포를 적용합니다.

  ```text
  blended[label] = (1 − β) · model[label] + β · (corrections_for_label / n)
  β = n / (n + K)          # n: 해당 패키지의 교정 수, K: 평활 계수 3
  ```

- 런타임 역전파나 무거운 재학습은 수행하지 않으며 학습 데이터와 기울기를 내보내지 않습니다.

### 🔎 온디바이스 의미 분석

- 실시간 경로는 규칙 우선입니다. 의미 판정으로 동작이 달라질 때만 **활성** 규칙을 실제
  플랫폼 동작 전에 우선 추론합니다. **관찰** 전용 분석은 활성 판정을 커밋한 뒤 실행해
  예상 동작만 기록하며 해당 알림을 바꿀 수 없습니다. 단일 실행기의 제한된 큐에서 실시간
  작업은 대기 중인 백그라운드 작업만 밀어낼 수 있고, 실행 중인 작업이나 다른 대기 실시간
  작업은 밀어내지 않습니다.
- 배포한 번들 encoder는 44.87 MiB dynamic-INT8 LiteRT 모델이며 128토큰 WordPiece 입력과
  `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`,
  `AMBIGUOUS` 7개 logits를 사용합니다. 정확한 float32 신뢰 임계값은 일반
  `0.949999988079071`, `MARKETING` `0.9917579889297485`입니다. 로컬 교정으로 원래
  비마케팅이거나 마케팅 임계값 미만인 판정을 신뢰 가능한 마케팅 신호로 승격할 수 없습니다.
- 모델·어휘·라벨·배포 manifest 네 파일은 해시로 함께 묶입니다. 자산 누락·손상, timeout,
  낮은 신뢰도, `AMBIGUOUS`는 모두 유지로 열린 실패 처리하며 생성형 JSON이나 reasoning은
  알림 동작 경로에 없습니다. 최종 새 blind v8 aggregate gate는 한국어·영어·혼합 언어
  균형 420행에서 raw macro-F1 `0.954401`, MARKETING precision `1.000000`, trusted
  coverage `0.855556`, 신뢰 가능한 비마케팅→MARKETING 오탐 0건으로 통과했습니다.
- 재현 가능한 데이터·학습·엄격 평가·LiteRT 변환·자산 패키징 파이프라인은
  **[ml/semantic-training](ml/semantic-training/README.ko.md)** 에 있습니다. 명시적 교정은
  알림 데이터를 내보내지 않고 기존 7종 로컬 축소 사전분포에 반영됩니다.
- MediaPipe Tasks GenAI는 선택형 지연 관찰 계층으로만 남습니다. 늦게 끝난 생성형 결과는
  미래 교정·통계·추천에는 반영할 수 있지만 이미 처리한 알림을 취소하거나 스누즈하지 않습니다.
- 사용자는 Storage Access Framework로 양자화 로컬 `.task` 파일을 가져올 수 있고 앱은 이를
  다운로드하지 않고 전용 저장소로 원자적으로 복사합니다. 이 빌드가 정확한 모델 프로필의
  호환성을 검증하기 전에는 자동 백그라운드 사용을 막습니다. 현재 호환으로 표시된 가져온
  프로필은 없으며 설정 화면이 이 상태를 명시합니다.
- **[ml/llm-training](ml/llm-training/README.ko.md)** 에 Gemma 3 미세조정, 고정 평가,
  양자화 변환, MediaPipe 번들링 도구가 있습니다. 원본 가중치는 사용자가 약관에
  동의해야 하고 실기기 검증 전에는 배포할 수 없으므로 생성 아티팩트와 함께 저장소 밖에 둡니다.
- 2026-07-29 기준 기존 270M 미세조정 체크포인트는 배포 품질 gate에 미달합니다. 로컬
  macOS ARM64 정적 프리플라이트도 LLVM 옵션 중복 충돌로 Gemma 3/converter 모듈을
  import하는 중 중단됐지만, 분리한 KV cache와 export configuration import는 통과했습니다.
  270M 변환, `.task`, MediaPipe Tasks GenAI `0.10.35` 호환성, 실기기 동작은 어느 것도
  확립되지 않았습니다. 따라서 선택형 LLM은 미검증 수동 기능으로 유지하며 자동 백그라운드
  작업에 사용할 호환 프로필은 없습니다.
- 모델과 SHA-256 무결성 기록을 함께 원자적으로 저장하고 이후 초기화마다 전체 파일을
  검증합니다. 설정에서 전체 지문을 확인할 수 있으며, 이 검사는 가져온 뒤 변경 여부만
  확인하고 모델 제작자를 인증하지는 않습니다. 모델 교체 도중 프로세스가 종료되면 다음
  초기화에서 마지막 정상 모델을 복구하고, 활성화된 새 모델이 호환되지 않아도 이전 정상
  모델로 되돌린 뒤 추론을 재개합니다.
- 출력은 `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`,
  `AMBIGUOUS` 7종으로 엄격히 제한합니다. 기존 광고 조건은 `MARKETING` 호환 뷰입니다.
- 기존 이진 광고 교정은 마케팅/거래성 표로 이관하며 생성형 reasoning과 알림 내용은 관찰
  테이블에 저장하지 않습니다.

### 📊 인사이트·기간 분석·알림 기록

- 통계 화면은 **개요**, **분석**, **기록**으로 나뉩니다. 개요에는 오늘 결과와 펼칠 수 있는
  일별 카드가 있고, 분석에서는 저장된 기간을 직접 골라 일/주/월 추세와 앱·규칙·카테고리·
  채널·시간대·7종 의미·관찰 예상·로컬 교정 분포를 확인할 수 있습니다.
- 모든 차트는 서드파티 라이브러리 없이 Jetpack Compose `Canvas`로 그립니다.
- WorkManager가 SQL 집계로 작은 `DailyInsight` 기록을 만들기 때문에 화면을 열 때 전체
  이벤트 로그를 다시 읽지 않습니다.
- 기록 탭은 **유지**를 포함한 모든 로컬 판정을 보여주며 동작과 앱·채널, 메타데이터 검색
  필터를 제공합니다. 목록에서는 내용을 복호화하지 않고 사용자가 개별 상세를 열 때만
  해당 암호문 한 건을 읽습니다.
- 활동 행에서 채널, 실제/관찰 결과, 규칙 이름, ML·LLM 신뢰도와 최대 128노드의 내용 없는
  조건 트레이스를 펼쳐볼 수 있습니다. 정확한 Android 채널 설정을 열고, OEM이 지원하지
  않으면 앱 알림 설정으로 대체합니다. 앱이 다른 앱의 채널을 직접 변경하지는 않습니다.
- 최근 7일 SQL 집계는 많이 음소거한 채널 검토와 반복된 마케팅 교정에 대한 관찰 규칙
  초안만 제안합니다. 자동 저장·자동 활성화하지 않으며 닫은 제안은 로컬에서 숨깁니다.

### 🤖 안전한 외부 자동화

- Samsung 모드 및 루틴은 **애플리케이션 → 앱을 열거나 앱 동작 바로 실행**에서
  AlarmControl의 동적 App Shortcut을 직접 사용합니다. 필터링 켜기·일시 중지와 게시된
  프로필 바로가기는 외부 자동화 허용이나 토큰이 필요하지 않습니다.
- Tasker, MacroDroid 및 호환 도구는 별도의 공개 브로드캐스트 계약으로 마스터 스위치나
  이름 있는 프로필을 제어할 수 있습니다.
- 외부 수신기는 기본적으로 비활성화되어 있으며 설정에서 명시적으로 허용해야 합니다.
- 모든 외부 요청은 설치별 무작위 `AUTH_TOKEN`을 포함해야 하며 분당 12회로 제한됩니다.
- 토큰 노출을 막기 위해 발신자는 AlarmControl 패키지 또는 component를 명시해야 하며
  암시적 브로드캐스트는 거부됩니다.
- 토큰은 언제든 회전할 수 있고 백업이나 감사 기록에 포함되지 않습니다.
- 최근 결과는 최대 200건의 내용 없는 로컬 감사 기록으로만 보관합니다.
- 빠른 설정 타일과 동적 런처 바로가기는 같은 `ProfileController`를 사용합니다.
- 자세한 설정 방법은 [한국어 자동화 안내](docs/automation.ko.md)를 참고하세요.

### 🔁 로컬 백업과 복원

- 규칙 트리, 이름 있는 프로필, 선택한 설정, 일별 기록을 Storage Access Framework로
  구조화된 JSON 파일에 내보내고 복원할 수 있습니다.
- 선택적으로 PBKDF2-HMAC-SHA256과 AES-256-GCM으로 암호화할 수 있으며, 평문 JSON은
  누구나 읽을 수 있는 파일로 취급해야 합니다.
- 새 암호화 백업 비밀번호는 8자 이상이어야 하며 기존의 짧은 비밀번호 백업도 계속
  복원할 수 있습니다.
- 패키지별 분류/광고 학습 투표는 암호화된 백업에만 포함할 수 있습니다.
- 알림 제목·본문, LLM 추론 문장, 비밀번호, 자동화 토큰은 절대 내보내지 않습니다.
- 복원 전에 검증된 미리보기를 표시하고 병합/교체 및 섹션별 복원을 지원합니다.
- 손상되거나 인증되지 않은 입력은 로컬 상태를 변경하기 전에 거부합니다.

### 🧹 로컬 개인정보 관리

- 활동 로그와 일별 인사이트 보존 기간을 독립적으로 설정할 수 있습니다.
- 선택형 **알림 상세 내용 기록**은 기본값이 꺼져 있습니다. 켜면 `SECRET`이 아닌 제목·본문을
  길이 제한 후 Android Keystore AES-256-GCM으로 암호화해 7일 보관하고 앱별로 제외할 수
  있습니다. 끄는 즉시 암호문과 키를 삭제하며 내용은 통계·일반 로그·백업에 들어가지 않습니다.
- 활동, 피드백, 인사이트 또는 전체 로컬 데이터를 확인 절차 후 삭제할 수 있습니다.
- 전체 삭제는 사용자가 가져온 LLM 모델과 앱 설정도 함께 초기화합니다.

### 🎨 Material You와 대화형 규칙 편집기

- Android 12 이상에서 Material You 동적 색상을 사용하고 다크 모드를 완전히 지원합니다.
- 넓은 화면에서는 Navigation Rail, 일반 휴대전화에서는 하단 탐색을 사용합니다.
- 중첩 AND/OR/NOT 트리를 시각적으로 편집하고 형제 노드를 위아래로 재정렬할 수 있습니다.
- 비어 있거나 잘못된 조건과 시간 입력에는 인라인 오류 안내를 표시합니다.

---

## 아키텍처와 모듈

MVVM, 단방향 데이터 흐름, 저장소 패턴을 적용한 여섯 개 런타임 모듈과 하나의 빌드 전용
성능 테스트 모듈로 구성됩니다.

| 모듈 | 책임 |
|---|---|
| `:app` | Compose/Material 3 UI, 단일 Activity, Navigation, DI 연결, `NotificationListenerService`. **Compose를 사용하는 유일한 모듈** |
| `:core` | 프레임워크 독립 도메인 모델, 저장소 계약, 디스패처, 결과 타입 |
| `:data` | Room v13, DataStore, 저장소 구현, 매퍼, 백업. 데이터를 영속화하는 유일한 모듈 |
| `:ml` | 번들 LiteRT 분류기, Unicode 특징 추출, 피드백 블렌더, 선택형 로컬 MediaPipe LLM |
| `:notifications` | 순수 Kotlin 알림 매칭 엔진 |
| `:automation` | 외부 Intent, 프로필 제어, 빠른 설정 타일, 동적 App Shortcuts |
| `:baselineprofile` | 빌드 전용 Baseline Profile 생성기와 오프라인 연결형 시작 벤치마크 |

의존성 방향은 다음과 같습니다.

```text
:app → :data / :ml / :notifications / :automation → :core
```

하위 계층은 `:app`이나 Compose에 의존하지 않으며 기능 모듈끼리 직접 의존하지 않습니다.
공유 계약은 `:core`에 위치합니다.

주요 기술: Kotlin, Coroutines/Flow, Jetpack Compose/Material 3, Hilt, Room v13, DataStore,
WorkManager, LiteRT, MediaPipe Tasks GenAI, Gradle Kotlin DSL, KSP, R8. minSdk 26,
compile/target SDK 36입니다.

---

## 개인정보 보호와 보안

- 어떤 앱·라이브러리·디버그·테스트 매니페스트에도 `android.permission.INTERNET`이 없습니다.
- Android 런타임 classpath에서 Retrofit, OkHttp, Ktor, gRPC, Volley, Apollo, Firebase,
  원격 분석 SDK를 사용하지 않습니다.
- 경량 분류 모델은 앱에 포함되며 대형 LLM은 사용자가 로컬 파일로 직접 가져옵니다.
- 앱 런타임의 모든 추론과 사용자 교정 학습은 기기에서 수행됩니다.
- 알림 제목과 본문은 목록·통계·피드백·감사 로그·백업·일반 로그에 들어가지 않습니다.
  선택형 상세 기능을 켠 경우에만 길이를 제한한 AES-GCM 암호문을 별도 Room 자식 테이블에
  7일 보관하고 사용자가 선택한 한 건을 기기에서 복호화합니다. 기본값은 꺼짐이며
  `SECRET` 알림과 사용자가 제외한 앱은 저장하지 않습니다.
- `OfflineManifestGuardTest`, `OfflineGuardTest`, Gradle `:app:offlineGuard`가 Debug/Release
  병합 매니페스트와 런타임 의존성을 검사합니다.
- WorkManager의 읽기 전용 `ACCESS_NETWORK_STATE`는 허용하지만 네트워크 송신 권한인
  `INTERNET`은 빌드를 실패시킵니다.

개발 머신의 빌드 전용 학습·변환 도구는 공개 의존성과 사용자가 약관에 동의한 원본
모델을 내려받을 수 있고, 합성 자료나 사용권이 확보된 자료를 쓰는 개발자 전용 학습은
네트워크를 사용할 수 있습니다. 실제 사용자 알림을 입력해서는 안 됩니다. 이 도구,
인증 정보, 원본 가중치와 생성 산출물은 APK에 포함되지 않으며, 설치된 Android 앱의
무네트워크 규칙은 그대로 유지됩니다.

---

## 빌드와 테스트

전체 환경 설정은 [BUILD.ko.md](BUILD.ko.md)를 참고하세요.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

./gradlew assembleDebug
./gradlew :app:bundleRelease  # Release 형식 컴파일 확인용이며 무서명일 수 있음
./gradlew test
./gradlew check
./gradlew build
```

실제 배포할 서명 번들은 [BUILD.ko.md](BUILD.ko.md)의 네 `ALARMCONTROL_*` 서명 환경 변수를
설정한 뒤 `./gradlew :app:releaseCandidate`로 생성합니다.

- 런타임 모듈에 **564개의 JVM/Robolectric 테스트**가 있습니다. 현재 검증 집계는
  **564개, 실패 0개, 오류 0개, 건너뜀 0개**입니다.
- 계측 테스트 **19개(Room/데이터 7, 실제 TFLite 4, 앱 런타임 8)**도 연결된 Galaxy에서
  모두 통과했으며, CI는 같은 테스트를 API 34 Managed Device에서 실행합니다.
- `:app` Compose UI 테스트는 Robolectric Native Graphics 모드로 로컬 JVM에서 실행됩니다.
- `detekt`, `ktlint`, Android Lint, 오프라인 가드는 `check`와 `build`의 필수 게이트입니다.
- `verifyCiActionPins`는 워크플로·재사용 워크플로·로컬 composite action YAML을 검사하며,
  원격 Action은 전체 커밋 SHA, 컨테이너 Action은 SHA-256 이미지 digest로 고정해야 합니다.
- 실제 Android 런타임이 필요한 테스트는 기기나 에뮬레이터에서 실행합니다.

  ```sh
  ./gradlew :ml:connectedDebugAndroidTest
  ./gradlew :data:connectedDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest
  ```

- `connectedDebugAndroidTest`는 전용 테스트 기기/프로필에서만 실행합니다. AGP가 실행 종료
  후 대상 Debug 패키지를 제거하면서 로컬 데이터까지 삭제할 수 있습니다. 보존할 데이터가
  있다면 먼저 백업하거나, 빌드된 APK를 직접 설치한 뒤 `adb shell am instrument`로 실행해
  `.test` 패키지만 제거합니다. 리스너 테스트는 통제된 `com.android.shell` 알림을 사용하므로
  두 APK 모두 `POST_NOTIFICATIONS` 권한을 요청하지 않습니다.

- 기기가 없을 때는 계측 테스트 APK를 컴파일할 수 있지만, 이를 실행 완료로 간주하지 않습니다.

  ```sh
  ./gradlew :data:assembleDebugAndroidTest :ml:assembleDebugAndroidTest \
    :app:assembleDebugAndroidTest :baselineprofile:assemble
  ```

- Baseline Profile 생성과 시작 성능 측정은 API 33 이상 연결 기기에서 수행합니다. 시작
  측정은 테스트 APK에 `INTERNET` 권한을 추가하지 않고 ActivityManager 콜드 스타트를
  10회 기록합니다.

  ```sh
  ./gradlew :app:generateBaselineProfile
  ```

---

## 완료된 마일스톤

### Milestone 2

- WorkManager 기반 로컬 통계·보존 기간 정리
- 동적 런처 바로가기
- 앱과 Baseline Profile 테스트 APK의 `INTERNET` 권한 및 네트워크 의존성을 차단하는
  자동 오프라인 게이트

### Milestone 3

- 중첩 복합·시간대 규칙과 시각적 편집기
- 활성 규칙 메모리 캐시, 단락 평가, 성능 테스트
- Room `DailyInsight` 기록과 Canvas 기반 인사이트 UI
- Room v1·v2·v3·v10·v12→v13 마이그레이션 테스트
- 암호화 백업/복원, 이름 있는 프로필, Material You와 입력 검증

### Milestone 4

- 사용자가 가져오는 MediaPipe 로컬 LLM 모델 관리와 로드 전 SHA-256 무결성 검증
- 제한된 큐, 안전한 프롬프트, 엄격한 JSON 파싱과 신뢰도 게이트
- R8, App Bundle의 Play 관리형 ABI 전달, 적응형 탐색, 접근성, Baseline Profile 기반
- 모든 릴리스 경로에 연결된 오프라인 검사

### Milestone 5

- 채널별 활동·일별 통계와 시스템 채널 설정 연결
- 패키지/채널 빈도 규칙과 콘텐츠 없는 인메모리 빈도 캐시
- 활성/관찰 규칙 병렬 평가와 분리된 실제·예상 동작 통계
- 대화·포그라운드 서비스·고중요도·알람 보호 Keep 초안
- 보수적 규칙 충돌 분석과 최대 128노드의 실제 처리 설명
- 자동 적용하지 않는 로컬 규칙 추천과 7종 의미 교정
- 버전 호환 Room/백업, API 34 Managed Device CI, Gradle wrapper 및 SHA-256 의존성 검증

### Milestone 6

- 최근 앱·채널 검색, 안전한 관찰 기본값, 시간·반복 선택 조건, 수동 패키지 검증을 갖춘
  안내형 규칙 생성
- 개요/분석/기록 탭, 일·주·월 기간 집계, 앱·규칙·채널·시간대·의미·학습 지표와
  **유지** 판정을 포함한 인덱스 기반 알림 기록
- 기본 꺼짐, 앱별 제외, `SECRET` 제외, 7일 만료, 비활성화 시 키까지 삭제하는 Android
  Keystore AES-256-GCM 알림 상세
- 지원되는 구버전 데이터의 Room v13 이관과 v1~v5를 계속 복원하는 백업 v6

### 출시 안정화

- 알림 리스너 작업을 최대 64개, 동시 평가 4개로 제한하고 실제 게시 시각을 기준으로 오래된
  대기 작업을 먼저 버립니다. 같은 알림의 오래된 작업과 규칙·개인정보 설정 변경 전에 시작된
  동작은 실행 직전에 무효화합니다.
- 일별/오늘 통계는 게시 당시 로컬 날짜를 기준으로 계산하고, WorkManager 누락 일자는 한 번에
  최대 7일만 보충합니다. 원본 기록은 최신 10,000건, 상세 트레이스는 최신 1,000건으로
  제한합니다.
- 조건 판정과 설명 생성을 단락 가능한 한 번의 트리 순회로 통합하고 활성/관찰 트레이스를
  합쳐 최대 128개의 내용 없는 노드만 저장합니다.
- 민감 화면 캡처 차단, 60초 민감 클립보드 만료, 독립적인 데이터 삭제 실패 처리,
  대소문자 무시 프로필 중복 방지, 접을 수 있는 규칙 경고를 적용했습니다.
- 비의미 모델 AAB payload는 60MiB로 제한합니다. 번들 의미 분류기는 30MiB를 목표로 하고
  45MiB를 하드 상한으로 두며, 전체 물리 AAB는 105MiB로 제한합니다. detekt, ktlint, 전체
  로컬 테스트, 마이그레이션/계측 APK 컴파일, 의존성 검증, 오프라인 가드를 필수 출시
  게이트로 유지합니다. CI 컴파일용 `bundleRelease`는 무서명일 수 있지만, 실제 배포 경로인
  `:app:releaseCandidate`는 네 서명 설정이 모두 있는지 확인하고 AAB payload의 JAR 서명을
  암호학적으로 검증합니다. 검증한 `bundleRelease` 산출물은
  98,983,815바이트(94.40MiB)이지만 그 자체가 서명된 배포 후보는 아닙니다. 네 종류의
  지원 native ABI를 모두 포함하지만 Play의 ABI split은 설치 기기에 맞는 native
  library만 전달합니다. ABI와 무관한 의미 모델은 base asset이므로 모든 호환 설치에
  전달됩니다.

세부 동작은 [규칙 안내](docs/RULES_GUIDE.ko.md), 저장 항목과 제외 항목은
[개인정보 안내](docs/PRIVACY.ko.md)를 참고하세요.

## Galaxy 실기기 검증

2026-07-27 Galaxy Note20 5G(`SM-N981N`, Android 13/API 33, One UI 5.1)에서 다음을 확인했습니다.

- 실기기 계측 테스트 19개 전체 통과(`:data` 7, `:ml` 4, `:app` 8)
- 실제 알림 리스너 바인딩, 활성 취소, 독립 관찰 예상, 삼성 스누즈 저장, 처리 트레이스 저장
- 알림 접근 권한 해제 시 즉시 언바인드, 재허용, 앱 프로세스 종료 후 서비스 자동 재생성
- 20개 급속 알림 처리와 강제 deep idle 상태에서의 활성 취소
- 실제 Android Ranking 중요도 조건, 잘못된 토큰을 거부한 뒤 정상 요청은 즉시 허용하는
  외부 자동화 수신기, 로컬 LLM 미설치 시 production DI 폴백
- Samsung 모드 및 루틴과 Routine+ 1.0.60에서 실제 AlarmControl App Shortcut을 선택해
  필터링 일시 중지와 켜기 루틴으로 마스터 스위치를 끄고 다시 켠 양방향 검증
- 콘텐츠 없는 테스트 알림을 이용한 One UI 채널 설정 이동
- Hilt가 생성한 WorkManager 집계 경로와 `DailyInsight` 저장
- Release APK/AAB 오프라인 게이트와 Baseline/Startup Profile 생성(각 25,859줄)
- 프로필 적용 콜드 스타트 10회: 최소 206ms, 중앙값 213ms, 평균 215.4ms, 최대 233ms

성능 수치는 해당 기기와 실행에만 해당합니다. 검증용 규칙과 활동 기록은 완료 후 삭제했습니다.

2026-07-29 같은 Note20에서 실제 번들 의미 LiteRT asset 로딩, tokenizer parity,
입력에 따라 달라지는 logits 계측도 통과했습니다. warm-up과 측정 추론 40회에서 cold
초기화 121.037ms, p50 65.506ms, p95 68.623ms였습니다. 프로세스 PSS는
45,531→96,117KiB, RSS는 122,420→173,308KiB, native heap은
5,972,016→22,291,072바이트로 증가했습니다. OOM은 없었고 thermal status는 `0`,
측정 charge counter는 3,332,000µAh로 유지됐습니다. 이 수치는 해당 기기와 실행의
측정값이며 모든 기기의 보장은 아닙니다.

---

## 향후 작업

- 추가 One UI/API 버전의 알림 중요도와 Doze·배터리를 검증하고, 실제 Tasker/MacroDroid
  발신 앱에서 인증된 공개 Intent 계약 전체를 실행합니다.
- 연결된 Galaxy에서 새 v13 마이그레이션, 안내형 편집기, 기간 분석, 기록 상세, 7일 보존,
  Keystore 키 삭제 시나리오를 출시 전에 검증합니다.
- 270M 변환 host 충돌을 해결한 뒤 정확한 SHA의 `.task` 번들링과 MediaPipe 실기기 검증을
  통과해야 자동 백그라운드 LLM 호환 프로필을 추가합니다.
- 대표적인 실제 기기에서 MediaPipe 모델 호환성, 지연, 메모리, 발열을 측정합니다.
- 사용자의 알림 내용을 수집하지 않고 직접 작성한 익명 다국어 픽스처를 확대합니다.

프로젝트의 강제 아키텍처 규칙은 [CLAUDE.ko.md](CLAUDE.ko.md)를 참고하세요. 영문
[`CLAUDE.md`](CLAUDE.md)가 최종 기준 문서입니다.
