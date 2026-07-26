# AlarmControl 빌드 안내

[English](BUILD.md) | **한국어**

AlarmControl 앱에는 `android.permission.INTERNET` 권한이 없습니다. 빌드 머신은 Gradle
의존성을 처음 해결할 때 인터넷이 필요할 수 있지만, 이것이 설치된 앱에 네트워크 권한을
부여하지는 않습니다. Debug와 Release 모두 오프라인 경계를 자동 검사합니다.

## 준비 사항

| 도구 | 버전 | 설명 |
|---|---|---|
| JDK | 17 | 모든 모듈이 Java 17 toolchain 사용 |
| Android SDK | API 36 | 현재 `compileSdk` / `targetSdk` |
| Gradle | Wrapper 8.11.1 | 저장소에 포함되므로 시스템 Gradle 불필요 |

macOS에서는 Gradle 실행 전에 JDK 17을 선택합니다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

`ANDROID_HOME`을 지정하거나 저장소 루트에 로컬 전용 `local.properties`를 둡니다.

```properties
sdk.dir=/absolute/path/to/your/Android/sdk
```

`local.properties`는 커밋하지 않습니다.

## 품질 검사와 테스트

```sh
./gradlew test                       # JVM 단위 테스트와 Robolectric 테스트
./gradlew check                      # 테스트 + detekt + ktlint + 오프라인 가드
./gradlew ktlintFormat               # Kotlin 포맷 자동 수정
./gradlew detekt ktlintCheck         # 정적 분석과 스타일만 검사
./gradlew :app:offlineGuard          # 병합 매니페스트와 런타임 의존성 검사
./gradlew build                      # 기기 독립 전체 빌드와 품질 게이트
./gradlew --dependency-verification strict check
```

`offlineGuard`는 Debug/Release 병합 매니페스트의 `android.permission.INTERNET` 또는
런타임 클래스패스의 금지된 네트워크 의존성을 발견하면 실패합니다. WorkManager의 읽기 전용
`ACCESS_NETWORK_STATE` 권한은 허용됩니다. `:baselineprofile:offlineManifestGuard`는 같은
검사를 두 Baseline Profile 테스트 APK에도 적용합니다.

현재 Debug JVM/Robolectric 테스트 집계는 총 425개이며 실패, 오류, 건너뜀은 모두 0개입니다.
API 34 Managed Device 계측 테스트는 총 11개(Room/데이터 6, 실제 TFLite 4, 앱 스모크 1)이며 모두
통과합니다.

## 빌드 산출물

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

로컬 APK 작업은 AGP 리소스 축소와 APK/AAB 릴리스 경로를 모두 안정적으로 지원하기 위해
범용 APK를 생성합니다. 스토어 배포에는 `app/build/outputs/bundle/release/` 아래의 Release
Android App Bundle을 권장하며, Play가 기기에 맞는 ABI APK를 생성합니다.

로컬 Release 컴파일에는 서명이 필수가 아닙니다. 서명된 산출물을 만들려면 네 환경 변수를
모두 제공합니다. 키 저장소와 자격 증명은 커밋하지 않습니다.

```sh
export ALARMCONTROL_KEYSTORE_FILE="/absolute/path/to/release.jks"
export ALARMCONTROL_KEYSTORE_PASSWORD="..."
export ALARMCONTROL_KEY_ALIAS="..."
export ALARMCONTROL_KEY_PASSWORD="..."
./gradlew :app:bundleRelease
```

일부 변수만 제공하면 의도적으로 설정 단계에서 실패합니다.

## 계측 테스트

JVM 테스트는 실제 Android 런타임 검증을 대체하지 않습니다. 기기나 에뮬레이터가 연결되어
있을 때 다음 명령을 실행합니다.

```sh
./gradlew :data:connectedDebugAndroidTest  # Room v1/v2/v3/v10/v12 -> v13 마이그레이션
./gradlew :ml:connectedDebugAndroidTest    # 번들 TFLite 런타임/에셋 호환성
./gradlew :app:connectedDebugAndroidTest   # 실제 Activity/Hilt/리소스/탐색 스모크 테스트
```

기기가 없을 때는 소스, 리소스, 의존성 오류를 찾기 위해 테스트 APK까지만 컴파일합니다.

```sh
./gradlew :data:assembleDebugAndroidTest :ml:assembleDebugAndroidTest \
  :app:assembleDebugAndroidTest :baselineprofile:assemble
```

계측 테스트 APK 컴파일을 실제 기기 테스트 실행으로 보고하지 않습니다.

현재 Room 테스트는 v1, v2, v3, v10, v12에서 v13까지의 실제 경로와 기존 이진 광고
관찰값의 7종 의미 prior 이관을 검증합니다.
`:baselineprofile:assemble`은 기기를 시작하지 않고 생성기 변형을 컴파일하며 프로필 수집은
명시적인 별도 작업입니다.

## Gradle Managed Device와 CI 단계

Android 런타임 모듈은 API 34 `pixel2Api34` `aosp-atd` Managed Device를 정의합니다. main 및
야간 CI와 같은 통합 테스트는 다음 명령으로 실행합니다.

```sh
./gradlew --dependency-verification strict \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  :data:pixel2Api34DebugAndroidTest \
  :ml:pixel2Api34DebugAndroidTest \
  :app:pixel2Api34DebugAndroidTest
```

소프트웨어 렌더링 속성은 Android Gradle Plugin이 지원하는 헤드리스/CI 실행 설정입니다.
홈 볼륨에 AVD 가변 데이터를 둘 공간이 부족하면 다음처럼 삭제 가능한 데이터만 다른 로컬
디스크로 옮깁니다. 프로젝트 내부 경로는 Git에서 무시됩니다.

```sh
export ANDROID_AVD_HOME="$PWD/.managed-avd"
mkdir -p "$ANDROID_AVD_HOME"
```

공식 [Gradle Managed Devices 문서](https://developer.android.com/studio/test/managed-devices)도
참고하세요.

PR에서는 JVM/Robolectric, detekt, ktlint, Android Lint, 오프라인 가드, Debug/Release APK,
Release AAB, 모든 계측 APK와 Baseline Profile 생성기 변형을 검사합니다. main 변경, 매일
예약 실행, 수동 실행에서는 Managed Device 테스트까지 수행합니다. 두 작업 모두 Gradle
wrapper 검증을 먼저 실행하고 `gradle/verification-metadata.xml`의 SHA-256을 strict 모드로
확인합니다. 실패 보고서는 CI artifact로 보존합니다.

## Baseline Profile과 오프라인 시작 벤치마크

빌드 전용 `:baselineprofile` 모듈에는 앱 시작 및 최상위 화면 탐색 프로필 생성기와 연결형
콜드 스타트 벤치마크가 있습니다. 일반 `build`, `check`, `assemble`은 기기 없이 완료됩니다.

프로필을 생성하거나 갱신하려면 API 33 이상 실제 기기 또는 적절한 rooted 에뮬레이터를
연결합니다.

```sh
./gradlew :app:generateBaselineProfile
```

생성된 프로필이 적용된 `benchmarkRelease`는 대표 실제 기기에서 다음과 같이 측정합니다.

```sh
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.alarmcontrol.baselineprofile.StartupBenchmark
```

이 테스트는 강제 종료 후 시작을 10회 수행하고 ActivityManager `TotalTime`을 기록합니다.
AndroidX TraceProcessor 방식은 기기 내부 localhost HTTP 소켓 때문에 테스트 APK에도
`INTERNET` 권한이 필요하므로 사용하지 않습니다. 오프라인 원칙에는 벤치마크 예외가 없습니다.
성능 수치는 기기별 결과이며 APK 컴파일만으로 추정하지 않습니다.

## 문제 해결

- **Unable to locate a Java Runtime**: `JAVA_HOME`을 JDK 17로 지정합니다.
- **SDK location not found**: `ANDROID_HOME` 또는 `local.properties`를 설정합니다.
- **Offline guard failure**: 오류가 지목한 병합 매니페스트나 의존성을 제거합니다. 가드를
  억제하거나 약화하지 않습니다.
- **No compatible LLM model**: MediaPipe 모델은 앱에 포함되지 않습니다. 설정에서 호환되는
  로컬 양자화 모델을 가져옵니다. 모델이 없어도 규칙과 번들 TFLite는 정상 동작합니다.
