# AlarmControl

[English](README.md) | **한국어**

**개인정보 보호를 최우선으로 하는 오프라인 우선 Android 알림·알람 필터링 앱입니다.**

AlarmControl은 규칙 기반 필터 엔진과 사용자 교정을 학습하는 경량 온디바이스 ML
분류기를 결합해 알림을 기기 안에서 분류하고, 취소하고, 다시 알리도록 합니다. 클라우드,
원격 분석, 계정, 네트워크 호출이 없으며 알림 데이터는 휴대전화 밖으로 나가지 않습니다.

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

### 🔎 선택형 온디바이스 LLM 광고 분석

- MediaPipe Tasks GenAI의 로컬 양자화 모델을 이용해 숨겨진 광고 의도와 은행 출금,
  배송, 보안 코드 같은 거래성 알림을 구분할 수 있습니다.
- 기본값은 꺼짐이며 사용자가 Storage Access Framework로 호환 모델을 직접 선택해야 합니다.
- 앱은 모델을 다운로드하지 않고 앱 전용 저장소로 원자적으로 복사합니다.
- 출력은 `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`,
  `AMBIGUOUS` 7종으로 엄격히 제한합니다. 기존 광고 조건은 `MARKETING` 호환 뷰입니다.
- 의미 조건이 포함된 규칙이 실제로 필요할 때만 제한된 백그라운드 큐에서 추론합니다.
- 모델 누락·손상, 낮은 신뢰도, 잘못된 응답은 모두 “신호 없음”으로 처리되어 기존 규칙과
  TFLite 분류 경로를 방해하지 않습니다.
- 관찰 규칙은 LLM 분석만 켜져 있어도 사용할 수 있지만 활성 규칙의 자동 동작에는 별도
  **LLM 자동 동작** 동의가 필요합니다. 교정은 7클래스 로컬 축소 사전분포에 반영됩니다.

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

- Samsung Galaxy Routines는 Good Lock RoutinePlus를 통해, Tasker와 MacroDroid는 직접
  공개 브로드캐스트 계약을 통해 마스터 스위치나 이름 있는 프로필을 제어할 수 있습니다.
- 외부 수신기는 기본적으로 비활성화되어 있으며 설정에서 명시적으로 허용해야 합니다.
- 모든 외부 요청은 설치별 무작위 `AUTH_TOKEN`을 포함해야 하며 분당 12회로 제한됩니다.
- 토큰은 언제든 회전할 수 있고 백업이나 감사 기록에 포함되지 않습니다.
- 최근 결과는 최대 200건의 내용 없는 로컬 감사 기록으로만 보관합니다.
- 빠른 설정 타일과 동적 런처 바로가기는 같은 `ProfileController`를 사용합니다.
- 자세한 설정 방법은 [한국어 자동화 안내](docs/automation.ko.md)를 참고하세요.

### 🔁 로컬 백업과 복원

- 규칙 트리, 이름 있는 프로필, 선택한 설정, 일별 기록을 Storage Access Framework로
  구조화된 JSON 파일에 내보내고 복원할 수 있습니다.
- 선택적으로 PBKDF2-HMAC-SHA256과 AES-256-GCM으로 암호화할 수 있습니다.
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
| `:data` | Room v12, DataStore, 저장소 구현, 매퍼, 백업. 데이터를 영속화하는 유일한 모듈 |
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

주요 기술: Kotlin, Coroutines/Flow, Jetpack Compose/Material 3, Hilt, Room v12, DataStore,
WorkManager, LiteRT, MediaPipe Tasks GenAI, Gradle Kotlin DSL, KSP, R8. minSdk 26,
compile/target SDK 36입니다.

---

## 개인정보 보호와 보안

- 어떤 앱·라이브러리·디버그·테스트 매니페스트에도 `android.permission.INTERNET`이 없습니다.
- Retrofit, OkHttp, Ktor, gRPC, Volley, Apollo, Firebase, 원격 분석 SDK를 사용하지 않습니다.
- 경량 분류 모델은 앱에 포함되며 대형 LLM은 사용자가 로컬 파일로 직접 가져옵니다.
- 모든 추론과 학습은 기기에서 수행됩니다.
- 알림 제목과 본문은 목록·통계·피드백·감사 로그·백업·일반 로그에 들어가지 않습니다.
  선택형 상세 기능을 켠 경우에만 길이를 제한한 AES-GCM 암호문을 별도 Room 자식 테이블에
  7일 보관하고 사용자가 선택한 한 건을 기기에서 복호화합니다. 기본값은 꺼짐이며
  `SECRET` 알림과 사용자가 제외한 앱은 저장하지 않습니다.
- `OfflineManifestGuardTest`, `OfflineGuardTest`, Gradle `:app:offlineGuard`가 Debug/Release
  병합 매니페스트와 런타임 의존성을 검사합니다.
- WorkManager의 읽기 전용 `ACCESS_NETWORK_STATE`는 허용하지만 네트워크 송신 권한인
  `INTERNET`은 빌드를 실패시킵니다.

---

## 빌드와 테스트

전체 환경 설정은 [BUILD.ko.md](BUILD.ko.md)를 참고하세요.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

./gradlew assembleDebug
./gradlew :app:bundleRelease
./gradlew test
./gradlew check
./gradlew build
```

- 런타임 모듈에 **382개의 JVM/Robolectric 테스트**가 있습니다. 현재 검증 집계는
  **382개, 실패 0개, 오류 0개, 건너뜀 0개**입니다.
- API 34 Managed Device 계측 테스트도 **7개(Room 2, 실제 TFLite 4, 앱/Hilt/탐색 1)**가
  모두 통과합니다.
- `:app` Compose UI 테스트는 Robolectric Native Graphics 모드로 로컬 JVM에서 실행됩니다.
- `detekt`, `ktlint`, Android Lint, 오프라인 가드는 `check`와 `build`의 필수 게이트입니다.
- 실제 Android 런타임이 필요한 테스트는 기기나 에뮬레이터에서 실행합니다.

  ```sh
  ./gradlew :ml:connectedDebugAndroidTest
  ./gradlew :data:connectedDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest
  ```

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
- Room v3→v12 순차 마이그레이션 테스트
- 암호화 백업/복원, 이름 있는 프로필, Material You와 입력 검증

### Milestone 4

- 사용자가 가져오는 MediaPipe 로컬 LLM 모델 관리
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
- 기존 v3 데이터의 Room v12 순차 이관과 v1~v4를 계속 복원하는 백업 v5

세부 동작은 [규칙 안내](docs/RULES_GUIDE.ko.md), 저장 항목과 제외 항목은
[개인정보 안내](docs/PRIVACY.ko.md)를 참고하세요.

## Galaxy 실기기 검증

2026-07-22 Galaxy Note20 5G(`SM-N981N`, Android 13/API 33, One UI 5.1)에서 다음을 확인했습니다.

- 실기기 계측 테스트 7개 전체 통과(`:data` 2, `:ml` 4, `:app` 1)
- 실제 알림 리스너 바인딩, 활성 취소, 독립 관찰 예상, 처리 트레이스 저장
- 콘텐츠 없는 테스트 알림을 이용한 One UI 채널 설정 이동
- Release APK/AAB 오프라인 게이트와 Baseline/Startup Profile 생성(각 24,551줄)
- 프로필 적용 콜드 스타트 10회: 최소 168ms, 중앙값 190ms, 평균 188.7ms, 최대 204ms

성능 수치는 해당 기기와 실행에만 해당합니다. 검증용 규칙과 활동 기록은 완료 후 삭제했습니다.

---

## 향후 작업

- 추가 One UI/API 버전의 알림 중요도, Doze·배터리, Tasker/RoutinePlus 전체 흐름을
  실기기에서 검증합니다.
- 연결된 Galaxy에서 새 v12 마이그레이션, 안내형 편집기, 기간 분석, 기록 상세, 7일 보존,
  Keystore 키 삭제 시나리오를 출시 전에 검증합니다.
- 대표적인 실제 기기에서 MediaPipe 모델 호환성, 지연, 메모리, 발열을 측정합니다.
- 사용자의 알림 내용을 수집하지 않고 직접 작성한 익명 다국어 픽스처를 확대합니다.

프로젝트의 강제 아키텍처 규칙은 [CLAUDE.ko.md](CLAUDE.ko.md)를 참고하세요. 영문
[`CLAUDE.md`](CLAUDE.md)가 최종 기준 문서입니다.
