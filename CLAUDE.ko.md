# CLAUDE.md 한국어 번역

[English](CLAUDE.md) | **한국어**

이 문서는 온디바이스 AI를 사용하는 **오프라인 우선 알림·알람 필터링 앱**의 프로젝트
지침을 한국어로 제공합니다. 규칙 기반 필터링, 로컬 ML 분류, Tasker/Samsung Routines
자동화를 지원합니다.

> 이 파일은 이해를 돕는 한국어 번역본입니다. 내용이 다르거나 해석이 충돌하면 영문
> [`CLAUDE.md`](CLAUDE.md)와 아래 HARD RULES가 최종 기준입니다.

---

## 0. 확정된 결정 사항

- **AI는 고전적 방법 우선의 하이브리드 구조입니다.** 통계는 SQL/Room, 분류는 앱에
  포함된 경량 TFLite 모델이 담당합니다. 생성형 LLM은 선택적이고 기기 조건을 확인하는
  보조 기능이며 핵심 필터 경로의 필수 의존성이 아닙니다.
- **온디바이스 LLM 엔진은 MediaPipe Tasks GenAI**
  (`com.google.mediapipe:tasks-genai`)와 `LlmInference`입니다. 대형 양자화 모델은 APK에
  포함하지 않으며 사용자가 Storage Access Framework로 직접 가져온 뒤 앱의 `filesDir`에
  저장합니다. 앱은 모델을 다운로드하지 않습니다. 모델이 없거나 로드에 실패하면
  `OnDeviceLlmManager`가 `Unavailable`을 보고하고 규칙 + TFLite 경로로 돌아갑니다.
- **알람 범위는 알람 카테고리 알림의 필터링에 한정됩니다.** `AlarmManager`, 정확한 알람,
  전체 화면 Intent 권한을 사용하지 않습니다. 다른 앱의 실제 시스템 알람은 알림으로
  노출되는 경우에만 관찰할 수 있습니다.
- **오프라인은 약속이 아니라 빌드로 강제합니다.** 앱에는 `INTERNET` 권한이 없습니다.
- **Compose는 `:app`에만 존재합니다.** `:core`, `:data`, `:ml`, `:notifications`에 Compose
  코드를 추가하지 않습니다. 여러 UI 소비자가 실제로 필요해지기 전에는 디자인 시스템이나
  `:feature:*` 모듈을 새로 만들지 않습니다.

---

## 1. 양보할 수 없는 HARD RULES

이 HARD RULES는 설치된 Android 앱, 패키징한 APK와 런타임 모듈에 적용됩니다. 개발자
전용 모델·데이터 도구는 승인된 가중치·합성 데이터·의존성을 내려받고 학습할 때
네트워크를 사용할 수 있지만 앱에 포함되지 않으며 실제 사용자 알림을 입력받아서는
안 됩니다.

1. **설치된 Android 앱은 네트워크를 절대 사용하지 않습니다.** 런타임의 클라우드 호출,
   원격 분석, 크래시 업로드와 원격 모델 다운로드를 금지합니다.
2. **모든 런타임 AI/ML은 기기에서 실행됩니다.** 추론과 앱 안의 사용자 교정 학습은
   로컬에서 수행합니다. 경량 분류기는 앱에 포함하고, 선택형 대형 LLM은 사용자가 로컬
   저장소에서 직접 가져오는 경우에만 사용합니다.
3. **사용자 데이터는 기기에만 남습니다.** 알림 내용은 일반 로그를 포함해 기기 밖으로
   나가지 않습니다.

작업이 이 규칙을 위반해야 하는 것처럼 보이면 우회하지 말고 중단한 뒤 사용자에게 확인합니다.

---

## 2. 확정 기술 스택

| 관심사 | 선택 |
|---|---|
| 언어 | Kotlin 전용, 모든 비동기/반응형 코드에 Coroutines + Flow |
| UI | Jetpack Compose + Material 3, 단일 Activity, Navigation-Compose |
| 아키텍처 | MVVM + 단방향 데이터 흐름 + 저장소 패턴 |
| DI | Hilt |
| 데이터베이스 | Room (KSP) |
| 설정 | DataStore Preferences |
| 백그라운드 | WorkManager, 로컬 인사이트와 보존 기간 정리 |
| 알림 핵심 | `NotificationListenerService` |
| 온디바이스 ML | 번들 LiteRT 분류기 + 선택형 로컬 MediaPipe Tasks GenAI LLM |
| 자동화 | 공개 Intent, Tasker/Locale, 빠른 설정 타일, App Shortcuts |
| 빌드 | Gradle Kotlin DSL, version catalog, KSP, R8 |
| 품질 | detekt + ktlint, `check`/`build` 필수 게이트 |
| SDK | minSdk 26, compile/target는 최신 안정 버전(현재 36) |

표에 준하는 주요 의존성을 새로 추가하는 것은 아키텍처 결정입니다. 사전 제안 없이 추가하지 않습니다.

---

## 3. 오프라인 강제 방법

- 모든 앱·모듈·Debug·테스트 매니페스트에서 `INTERNET` 권한을 금지합니다.
- Android 앱·테스트 런타임 classpath에서 Retrofit, OkHttp, Ktor client, Volley, Apollo,
  Firebase, 원격 분석 및 크래시 업로드 SDK를 금지합니다. 앱에 포함되지 않는 개발자 전용
  Python 환경은 이 classpath 규칙의 대상이 아닙니다.
- 분류 모델은 `:ml/src/main/assets/`에 포함하고 로컬에서만 읽습니다.
- 생성형 LLM은 사용자가 로컬 파일로 선택하고 앱 전용 `filesDir`에 원자적으로 복사합니다.
  가져올 때 SHA-256 사이드카를 함께 원자적으로 기록하고 이후 초기화마다 전체 파일을
  해시해 누락되거나 불일치한 무결성 기록을 거부합니다. 이는 가져온 뒤 파일 변경을
  탐지하지만 모델 제작자를 인증하지는 않습니다. 누락·손상·변경된 모델은 정상적으로
  성능 저하 처리합니다.
- 백업/모델 파일 선택 Intent에는 `Intent.EXTRA_LOCAL_ONLY`를 지정합니다.
- 로컬 백업 v6에는 규칙 모드와 지원 조건 트리, 이름 있는 프로필, 채널·앱·시간대·의미별
  일별 요약, 선택한 설정을 포함하며 v1~v5도 계속 복원합니다. 패키지별 7종 의미 학습 투표는
  비밀번호 기반 AES-256-GCM 암호화 백업에만 포함할 수 있습니다.
  알림 내용, LLM 추론 문장, 자동화 토큰은 내보내지 않습니다.
- 알림 상세 내용 저장은 기본값이 꺼진 명시적 선택 기능입니다. 켜면 `SECRET`이 아닌
  제목·본문만 길이를 제한해 Android Keystore AES-256-GCM 키로 암호화하고 별도 자식 행에
  최대 7일 보관합니다. 앱별 제외를 지원하며 목록·검색·통계·로그·백업은 평문을 읽거나
  포함하지 않습니다. 설정을 끄면 모든 암호문과 내보낼 수 없는 키를 즉시 삭제합니다.
- `OfflineManifestGuardTest`, `OfflineGuardTest`와 `:app:offlineGuard`가 Debug/Release 병합
  매니페스트와 런타임 의존성을 검사합니다. `:baselineprofile:offlineManifestGuard`는 같은
  경계를 두 Baseline Profile 테스트 APK에도 적용합니다.
- WorkManager의 `ACCESS_NETWORK_STATE`는 네트워크 상태 읽기만 가능하므로 허용합니다.
  이 예외가 `INTERNET` 권한이나 네트워크 클라이언트를 허용한다는 뜻은 아닙니다.
- 오프라인 게이트를 비활성화하거나 약화하지 않습니다.

---

## 4. 모듈 아키텍처

```text
:app             Compose UI, 탐색, DI 연결, NotificationListenerService 진입점
:core            프레임워크 독립 도메인, 저장소 계약, 디스패처, 결과 타입
:data            Room v13, DataStore, 저장소 구현, 백업, 매퍼
:ml              번들 분류기, 선택형 로컬 LLM, 특징 추출, 피드백 학습
:notifications   순수 Kotlin 알림 매칭 엔진
:automation      공개 Intent, Tasker/Locale, QS 타일, App Shortcuts
:baselineprofile 빌드 전용 프로필 생성기와 시작 벤치마크
```

- 런타임 모듈은 여섯 개로 유지합니다. `:baselineprofile`은 런타임 의존성이 없는 유일한
  빌드 전용 테스트 모듈입니다.
- 의존성 방향은 `:app` → 기능 → `:data`/`:ml`/`:notifications` → `:core`입니다.
- 하위 계층은 `:app`이나 Compose에 의존하지 않습니다.
- `:notifications`는 알림 스냅샷을 받아 판정을 반환하는 순수 Kotlin 코드입니다.
- `NotificationListenerService`는 얇은 진입점으로 유지하고 비즈니스 규칙을 넣지 않습니다.

---

## 5. AI/ML 규칙

- **SQL로 해결할 수 있는 문제에 ML을 사용하지 않습니다.** 통계 인사이트는 Room 집계입니다.
- 런타임 번들 분류기의 테스트는 고정 모델과 픽스처로 정확한 라벨을 검증합니다.
- 런타임 선택형 LLM은 사용자 가져오기, 기기 조건 확인, 결정적 테스트 더블을 사용하며
  어느 런타임 경로도 모델을 내려받지 않습니다.
- 앱 안의 학습은 사용자의 명시적 피드백을 로컬 가중치/집계에 반영하는 점진적 방식입니다.
- 앱 학습 데이터나 기울기를 내보내지 않습니다.
- 의미 분류는 `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`,
  `AMBIGUOUS` 7종으로 고정합니다. 잘못되거나 모순된 출력은 거부하며 기존 광고 참/거짓
  피드백은 마케팅/거래성으로 이관합니다. `IsAdvertisement`는 `MARKETING` 호환 뷰입니다.
- 번들 경량 의미 encoder의 신뢰 가능한 결과만 활성 규칙 신호가 될 수 있으며 낮은 신뢰도나
  `AMBIGUOUS` 결과는 유지로 열린 실패 처리합니다. 생성형 LLM 결과는 미래 교정·통계·추천을
  위한 관찰값으로만 사용하고 이미 처리한 알림을 바꾸지 않습니다. 정확한 가져온 모델에 대해
  호환성 프로필을 검증하기 전에는 LLM 자동 백그라운드 분석을 활성화하지 않습니다.
- 모델을 사용할 수 없거나 신뢰도가 낮으면 규칙 기반 필터링으로 돌아갑니다.
- 모델 I/O는 `:ml` 인터페이스 뒤에 감춰 호출자를 런타임 구현과 분리합니다.

---

## 6. 알림과 필터 엔진 규칙

- 엔진은 규칙 우선이며 ML 카테고리는 여러 조건 신호 중 하나입니다.
- 활성 규칙은 **ACTIVE**, 관찰 규칙은 **MONITOR** 목록으로 별도 컴파일합니다. 첫 활성
  일치만 플랫폼 동작을 수행하며 첫 관찰 일치는 예상 동작만 기록하고 활성 규칙을 가리지
  않습니다.
- 리스너 작업은 최대 64개, 동시 평가 4개로 제한합니다. 실제 알림 게시 시각을 freshness로
  사용하므로 늦게 도착한 과거 콜백이 더 최신 대기 작업을 밀어낼 수 없고, 이미 실행 중인
  작업은 폭주 퇴출 대상이 아닙니다. 같은 알림의 새 게시와 규칙·권한 변경은 오래된 작업의
  Binder 실행 권한을 취소하며, 시작 캐시는 2초 뒤 fail-open 합니다.
- 빈도 조건은 패키지 또는 패키지+채널 범위에서 현재 알림을 포함해 1분~24시간, 임계치
  2~1000을 지원합니다. Room 메타데이터로 인메모리 추적기를 한 번만 초기화하고 알림마다
  DB를 조회하지 않습니다. 초기화·채널·Ranking 신호 누락은 `UNKNOWN`이며 동작하지 않습니다.
- 채널 제어는 정확한 Android 시스템 채널 설정으로 이동하고 불가능하면 앱 알림 설정으로
  대체합니다. 다른 앱 채널을 직접 바꾸거나 헤드업 표시 전에 차단할 수 있다고 표현하지 않습니다.
- 보호 기능은 숨겨진 예외가 아니라 사용자가 확인·편집하는 고우선순위 `Keep` 규칙
  초안입니다. 대화, 포그라운드 서비스, 고중요도, 알람 템플릿은 자동 저장하지 않습니다.
- 조건 매칭과 설명 트레이스는 단락 가능한 단일 순회로 계산합니다. 처리 설명은 lane, 조건
  종류, 3상태 결과, 깊이, 순서만 활성/관찰 합산 최대 128노드로 저장하며 비교값, 알림 내용,
  LLM reasoning은 저장하지 않습니다.
- 규칙 추천은 SQL 기반 로컬 초안이며 자동 저장·활성화하지 않습니다. 구조적으로 같은 규칙은
  제외하고 사용자가 닫은 추천을 로컬에서 다시 노출하지 않습니다.
- 실제 플랫폼 동작은 **취소**와 **다시 알림**입니다. `Keep`과 기존 `MarkRead`는 기록만
  하는 판정이며 다른 앱 알림을 실제 읽음 상태로 바꾸지 않습니다.
- 시스템 알람 차단, 헤드업 알림 사전 차단, 임의 알림 읽음 처리처럼 Android가 허용하지
  않는 기능을 UI에서 약속하지 않습니다.
- 인사이트용 이벤트 행에는 내용 없는 최소 메타데이터만 저장합니다. 선택형 제목·본문은
  별도의 Android Keystore 암호문 자식 행에만 7일간 보관하며 목록 쿼리와 통계에서 읽지 않습니다.
- 원본 기록은 설정 보존 기간과 최신 10,000건을 함께 적용하고 트레이스는 최신 1,000건만
  유지합니다. 일별/오늘 통계는 게시 당시 저장한 로컬 날짜를 우선하며 구버전 행만 시각으로
  호환 집계합니다.
- 통계 제외는 기록을 제외할 뿐 이미 닫힌 알림을 복원하지 않습니다.
- 서비스 콜백은 즉시 순수 로직에 위임합니다.

---

## 7. 자동화와 Samsung Routines 규칙

- 공개된 Samsung Modes and Routines 서드파티 액션 SDK는 없습니다. Samsung Routines의
  직접 연동은 **애플리케이션 → 앱을 열거나 앱 동작 바로 실행**에서 AlarmControl의 동적
  App Shortcut을 선택하는 방식입니다. 검증한 One UI 5.1 / Routine+ 1.0.60에는 임의
  브로드캐스트 전송 동작이 없습니다. Tasker, MacroDroid, Locale 호환 도구는 별도의
  인증된 공개 Intent 계약을 사용합니다.
- `ENABLE_PROFILE` / `DISABLE_PROFILE` 및 extra 이름은 공개 API처럼 안정적으로 유지합니다.
- 외부 요청은 앱 내부 opt-in과 설치별 `AUTH_TOKEN`을 모두 요구합니다.
- 발신자는 AlarmControl 패키지 또는 수신기 component를 명시해야 합니다. 다른 앱이 공개
  Action을 구독해 토큰을 볼 수 없도록 암시적 브로드캐스트는 거부합니다.
- 액션과 extra를 검증하고 브로드캐스트 폭주를 제한합니다.
- 외부 자동화 도구가 앱 정의 signature 권한을 가질 수 없으므로 사용자 정의 Android
  권한은 의도적으로 사용하지 않습니다.
- 감사 기록은 소스, 동작, 대상 타입, 결과, 변경 수만 최대 한도로 보관합니다. 대상 이름,
  토큰, 알림 내용은 기록하지 않습니다.
- QS 타일, App Shortcuts, UI, 외부 Intent는 모두 같은 `ProfileController`에 위임합니다.
  Samsung Routines의 App Shortcut 경로에는 외부 자동화 opt-in이나 토큰이 필요하지 않습니다.

---

## 8. 코딩 규칙

- ViewModel은 하나의 불변 UI 상태 `data class`를 `StateFlow`로 공개합니다.
- Composable은 상태가 없고 이벤트를 상위로 전달합니다. DB 접근이나 비즈니스 로직을 넣지 않습니다.
- 구조적 동시성만 사용합니다. 디스패처를 주입하고 `viewModelScope`/WorkManager 범위에
  작업을 묶습니다. `GlobalScope`와 메인 스레드 블로킹을 금지합니다.
- 계층 사이 오류는 sealed 결과/도메인 타입으로 전달합니다. 빈 catch 블록을 금지합니다.
- `:ml`과 `:automation`의 공개 API에는 KDoc을 작성합니다.
- 현재 파일 스타일에 맞추고 변경 이유가 있는 부분만 수정합니다.

---

## 9. 테스트와 목표 중심 실행

- 규칙/조건 변경에는 순수 매처 단위 테스트를 추가합니다.
- ML은 번들 픽스처 기반 결정적 테스트를 사용합니다.
- 저장소/DB는 Room in-memory 또는 Robolectric 테스트를 사용합니다.
- ViewModel/Flow는 Turbine + MockK로 검증합니다.
- 오프라인 JVM 테스트와 Gradle 게이트가 Debug/Release의 `INTERNET` 권한과 네트워크
  의존성을 검사합니다.
- Room 마이그레이션은 `:data/src/androidTest`의 `MigrationTestHelper`로 v1·v2·v3·v10·v12에서
  v13으로 업그레이드하고, 기존 데이터 보존과 이진 광고 피드백의 7종 의미 이관을 검증합니다.
- 계측 테스트는 기기/에뮬레이터에서 `./gradlew :data:connectedDebugAndroidTest`로 실행하며
  기본 JVM `test` 작업에는 포함되지 않습니다.
- AGP `connectedDebugAndroidTest`는 전용 테스트 기기/프로필에서만 실행합니다. 종료 과정이
  대상 Debug 패키지와 로컬 데이터를 삭제할 수 있으므로, 보존할 데이터가 있다면 먼저
  백업합니다. 또는 빌드된 APK를 직접 설치한 뒤 `adb shell am instrument`로 실행하고
  `.test` 패키지만 제거합니다. 리스너 테스트는 통제된 `com.android.shell` 알림을 사용하므로
  제품/테스트 APK 모두 `POST_NOTIFICATIONS` 권한을 요청하지 않습니다.
- `:app` 계측 테스트는 실제 Activity/Hilt/리소스/탐색, 알림 리스너의 통제된
  관찰/취소/스누즈 판단, 20개 급속 게시, 강제 Doze 취소, Android Ranking 중요도,
  인증된 외부 자동화, LLM 미설치 폴백, Hilt WorkManager 일별 집계 경로를 검증합니다.
  `:ml` 계측 테스트는 실제 TFLite 런타임과 에셋 호환성을 검증합니다.
- PR CI는 JVM/Robolectric, 품질·오프라인 게이트, Release APK/AAB 컴파일, 모든 계측 APK와
  Baseline Profile 변형을 컴파일합니다. main 변경·매일 예약·수동 실행은 추가로 API 34
  `pixel2Api34` `aosp-atd` Managed Device에서 `:data`, `:ml`, `:app` 테스트를 실행합니다.
- Release 번들이 컴파일됐다고 바로 배포 가능한 것은 아닙니다. CI의 `bundleRelease`는
  의도적으로 무서명일 수 있습니다. 실제 배포본은 반드시 `:app:releaseCandidate`를 통과해야
  하며, 이 작업은 네 서명 환경 변수, 기기 독립 게이트, AAB 크기 제한과 payload의
  암호학적 서명을 검증합니다. 키 저장소와 서명 자격 증명은 커밋하지 않습니다.
- CI는 Gradle wrapper를 검증하고 `gradle/verification-metadata.xml`의 SHA-256을 strict
  모드로 확인합니다. `verifyCiActionPins`는 워크플로·재사용 워크플로·composite action
  YAML을 모두 검사하며 원격 Action은 40자 커밋 SHA, 컨테이너 Action은 SHA-256 digest로
  고정해야 합니다. 이 공급망 검사를 우회하거나 약화하지 않습니다.
- Baseline Profile과 소켓 없는 ActivityManager 시작 벤치마크는 `:baselineprofile`에서
  관리합니다. AndroidX TraceProcessor 방식은 localhost HTTP에도 테스트 APK의 `INTERNET`
  권한이 필요하므로 사용하지 않습니다. 생성과 측정은 API 33 이상 연결 기기에서 수행하며
  일반 `build`와 `check`는 기기 없이 완료되어야 합니다.
- detekt와 ktlint는 모든 모듈의 `check`/`build`에 연결됩니다. 위반은 허용하지 않습니다.

  ```sh
  ./gradlew build
  ./gradlew ktlintFormat
  ./gradlew detekt ktlintCheck
  ```

---

## 10. 작업 방식

**코딩 전에 생각합니다.** 가정을 밝히고 여러 해석이 존재하면 선택지를 설명합니다.
더 단순한 해결책이 있으면 근거를 들어 제안합니다.

**단순성을 우선합니다.** 요청을 해결하는 최소 코드를 작성하며 추측성 기능, 불필요한 추상화,
실제로 발생할 수 없는 경우를 위한 복잡한 처리를 추가하지 않습니다.

**수술식 변경을 합니다.** 요청과 직접 관련된 부분만 수정하고 무관한 리팩터링이나 전체
재포맷을 하지 않습니다. 변경으로 생긴 고아 코드만 제거합니다.

**검증 가능한 목표로 실행합니다.** 성공 조건과 테스트를 먼저 정의하고 통과할 때까지
독립적으로 반복합니다.
