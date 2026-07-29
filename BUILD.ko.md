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

저장소에는 JVM/Robolectric 테스트와 연결형 Room, LiteRT, 앱 런타임 계측 테스트가 있습니다.
CI도 구성된 API 34 Managed Device에서 연결형 테스트를 실행합니다. 릴리스 노트에는 이 문서의
과거 테스트 개수를 복사하지 말고 해당 릴리스 커밋에서 실제 실행한 보고서 결과를 기록해야
합니다.

## 빌드 산출물

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease  # App Bundle 호환성 회귀 확인용이며 무서명일 수 있음
```

APK와 App Bundle 경로는 AGP 리소스 축소를 적용한 상태로 모두 빌드 가능하게 유지합니다.
`bundleRelease`는 로컬 및 CI 호환성 확인을 위해 키 저장소 없이도 실행할 수 있지만 AAB는
현재 배포 산출물이 아니며, 이 작업의 성공만으로 출시 가능한 상태가 되지 않습니다.

첫 Release 전에 장기간 유지할 Android 업데이트 키 저장소 하나를 만들고 안전하게
백업합니다. `keytool -list -v` 등으로 인증서 SHA-256을 별도로 확인한 뒤 콜론을 제거한
64자리 16진수로 `config/release-signing-certificate.sha256`의 대기 값을 교체합니다.
지문은 공개 정보지만 키 저장소와 비밀번호는 공개하거나 커밋하면 안 됩니다. 이 공개
지문이 유효하게 커밋되기 전에는 `releaseCandidate`가 의도적으로 실패합니다.

GitHub Releases 배포본을 만들 때는 네 환경 변수를 모두 제공하고 명시적인 배포 후보
게이트를 실행합니다. 키 저장소와 자격 증명은 커밋하지 않습니다.

```sh
export ALARMCONTROL_KEYSTORE_FILE="/absolute/path/to/release.jks"
export ALARMCONTROL_KEYSTORE_PASSWORD="..."
export ALARMCONTROL_KEY_ALIAS="..."
export ALARMCONTROL_KEY_PASSWORD="..."
./gradlew :app:releaseCandidate
```

일부 변수만 제공하면 의도적으로 설정 단계에서 실패합니다. `releaseCandidate`는 기기 없이
실행 가능한 품질·오프라인 검사 전체, 계측 테스트 APK와 Baseline Profile 변형 컴파일,
의미 분류기 raw 45MiB, 비의미 물리 payload 140MiB, 전체 물리 APK 185MiB 상한을
검사합니다. 또한 의미 asset 네 개와 manifest 해시를 확인한 뒤 minSdk 26 기준으로
`apksigner` 검증을 수행하고 유일한 서명자가 커밋된 업데이트 인증서 지문과 일치하는지
확인합니다. 검증된 범용 APK는
`app/build/outputs/apk/release/` 아래 유일한 APK입니다. 이 APK만 GitHub 배포 후보이며
`bundleRelease`는 기존 AAB 상한을 유지한 CI와 형식 회귀 확인용 산출물로 남습니다.

현재 Release APK는 지원하는 모든 native ABI 라이브러리를 포함하는 범용 APK입니다.
GitHub Releases는 Play처럼 설치 기기를 판별해 ABI별 자산을 자동 선택하지 않으므로
사용자는 하나의 범용 APK를 내려받습니다. ABI와 무관한 경량 의미 분류기는 모든 설치용
APK에 포함됩니다.

### GitHub Release 게시

Release 워크플로는 `vMAJOR.MINOR.PATCH` 태그를 push할 때만 실행합니다. 태그 버전은 APK의
`versionName`과 정확히 같아야 하며 태그 커밋은 저장소 기본 브랜치의 조상이어야 합니다.
새로 게시하는 APK의 `versionCode`도 이전 Release보다 반드시 커야 합니다. 같거나 낮은
값은 Android가 업데이트로 설치하지 않습니다. 두 값은 `app/version.json`에서 변경합니다.
워크플로는 새 태그의 과거 이력만 보지 않고 checkout된 기본 브랜치 ref에서 도달 가능한
모든 strict SemVer Release 태그의 커밋된 메타데이터를 확인합니다. 현재 코드가 그
모두보다 크지 않으면 거부하므로 과거 커밋에 Release 태그를 뒤늦게 붙여도 우회할 수
없습니다. 해당 태그가 없는 첫 Release는 허용하며, 비교에는 checkout이 받은 Git 이력만
사용하고 추가 네트워크 요청을 하지 않습니다. 서명과 게시 전에는 checkout이 태그가
가리키는 정확한 커밋인지 확인하고, 그 checkout에서 `:data`, `:ml`, `:app`
`pixel2Api34DebugAndroidTest`를 실행합니다. `github-release` Environment에 다음 secret을
설정합니다.

- `ALARMCONTROL_KEYSTORE_BASE64`
- `ALARMCONTROL_KEYSTORE_PASSWORD`
- `ALARMCONTROL_KEY_ALIAS`
- `ALARMCONTROL_KEY_PASSWORD`

플랫폼별 클립보드 명령 없이 키 저장소를 인코딩하려면 표준 입력에서 GitHub CLI로 바로
전달합니다.

```sh
base64 < /absolute/path/to/release.jks |
  gh secret set ALARMCONTROL_KEYSTORE_BASE64 --env github-release
```

Managed Device, 품질, 오프라인, 테스트 APK 컴파일과 서명 게이트를 모두 통과하면
워크플로가 다음 자산을 포함한 Release를 만듭니다.

- `AlarmControl-<version>-universal.apk`
- `AlarmControl-<version>-universal.apk.sha256`

기존 태그, Release 또는 같은 이름의 자산은 덮어쓰지 않고 실패합니다. 이 키는 Play
upload key와 달리 설치된 APK가 실제 앱 업데이트 서명 신원으로 신뢰하는 키입니다.
GitHub secret만 백업으로 간주하지 말고 키 저장소와 자격 증명을 암호화한 별도 오프라인
백업으로 보관해야 합니다. 키를 잃거나 바꾸면 기존 설치 위에 업데이트할 수 없으며,
재설치하려고 앱을 제거하면 사용자가 먼저 백업을 내보내지 않은 로컬 데이터가 삭제될
수 있습니다.

Release 자산은 저장소 공개 범위를 그대로 따릅니다. 비공개 저장소라면 일반 사용자는
권한이 있는 GitHub 계정으로 로그인하지 않고 APK를 받을 수 없습니다. 공개 직접 배포에는
공개 저장소를 사용하거나, 검증한 APK와 체크섬을 그대로 둔 별도 공개 위치가 필요합니다.
사용자가 직접 업데이트를 설치하며 AlarmControl 앱 자체에는 GitHub 클라이언트, 업데이트
확인 기능 또는 `INTERNET` 권한이 없습니다.

이 배포 경로에는 Google Play 비공개 테스트가 필요하지 않습니다. 다만 Play 외부에서
설치하는 앱에도 별도의 Android 개발자 검증이 순차 적용되고 있습니다. 대상 지역에
시행되기 전까지 최신
[Android 개발자 검증](https://developer.android.com/developer-verification) 안내에 따라
Android Developer Console(또는 계속 보유할 Play Console)에서 `com.alarmcontrol`과 장기
Release 서명키를 등록해야 합니다.

선택형 생성 LLM은 앱 Release에 포함하거나 앱 payload로 계산하지 않습니다. 호환 모델을
제공할 경우 라이선스와 해시를 명시한 별도 파일로 배포하며 사용자가 Storage Access
Framework로 직접 가져옵니다. GitHub 앱 Release 워크플로는 LLM을 업로드하지 않습니다.

## 계측 테스트

JVM 테스트는 실제 Android 런타임 검증을 대체하지 않습니다. 기기나 에뮬레이터가 연결되어
있을 때 다음 명령을 실행합니다.

```sh
./gradlew :data:connectedDebugAndroidTest  # Room v1/v2/v3/v10/v12 -> v13 마이그레이션
./gradlew :ml:connectedDebugAndroidTest    # 번들 TFLite 런타임/에셋 호환성
./gradlew :app:connectedDebugAndroidTest   # Activity/Hilt, 리스너, 자동화, LLM 폴백, WorkManager
```

> **전용 테스트 기기 또는 사용자 프로필에서 실행하세요.** AGP의
> `connectedDebugAndroidTest` 수명 주기는 종료 시 대상 Debug 패키지를 제거할 수 있으며,
> 이때 해당 앱의 로컬 데이터도 삭제됩니다. 보존할 데이터가 있다면 먼저 로컬 백업을
> 내보내세요. 데이터가 있는 개발용 휴대전화에서는 빌드된 대상/테스트 APK를
> `adb install -r`로 설치하고 `adb shell am instrument`로 실행한 뒤 `.test` 패키지만
> 제거합니다. 리스너 테스트는 `com.android.shell`로 통제된 알림을 게시하므로 두 APK 모두
> `POST_NOTIFICATIONS` 권한을 요청하지 않습니다.

기기가 없을 때는 소스, 리소스, 의존성 오류를 찾기 위해 테스트 APK까지만 컴파일합니다.

```sh
./gradlew :data:assembleDebugAndroidTest :ml:assembleDebugAndroidTest \
  :app:assembleDebugAndroidTest :baselineprofile:assemble
```

계측 테스트 APK 컴파일을 실제 기기 테스트 실행으로 보고하지 않습니다.

현재 Room 테스트는 v1, v2, v3, v10, v12에서 v15까지의 실제 경로와 기존 이진 광고
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
예약 실행, 수동 실행에서는 Managed Device 테스트까지 수행합니다. strict SemVer Release
태그도 태그 커밋에서 같은 테스트를 별도로 다시 실행하며, 실패하면 게시할 수 없습니다.
각 작업은 Gradle wrapper 검증을 먼저 실행하고 `gradle/verification-metadata.xml`의
SHA-256을 strict 모드로 확인합니다. 루트 `verifyCiActionPins` 게이트는 워크플로, 재사용
워크플로, composite action YAML을 모두 검사하며 원격 Action은 40자 커밋 SHA, 컨테이너
Action은 SHA-256 이미지 digest로 고정해야 합니다. 실패 보고서는 CI artifact로 보존합니다.

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
  앱 전용 Gemma 후보 생성 절차는
  [`ml/llm-training/README.ko.md`](ml/llm-training/README.ko.md)를 따릅니다.
  safetensors, GGUF, 중간 `.tflite`는 직접 가져오지 않습니다.
- **LLM 무결성 기록 누락/불일치**: 설정에서 신뢰하는 로컬 모델을 다시 가져옵니다.
  AlarmControl은 가져올 때 기록한 SHA-256 값을 검증할 수 없는 모델을 의도적으로
  불러오지 않습니다.
