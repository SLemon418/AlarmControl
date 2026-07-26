# Building AlarmControl

**English** | [한국어](BUILD.ko.md)

AlarmControl ships without `android.permission.INTERNET`. The build machine may still need network
access to resolve Gradle dependencies; that does not grant the installed app network access. The
offline boundary is checked automatically for debug and release variants.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | All modules use the Java 17 toolchain |
| Android SDK | API 36 | Current `compileSdk` / `targetSdk` |
| Gradle | Wrapper 8.11.1 | Included in the repository; no system Gradle is required |

On macOS, select JDK 17 before running Gradle:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

Set the Android SDK location with `ANDROID_HOME`, or place a machine-local `local.properties` file
at the repository root:

```properties
sdk.dir=/absolute/path/to/your/Android/sdk
```

Do not commit `local.properties`.

## Quality and tests

```sh
./gradlew test                       # all local JVM unit and Robolectric tests
./gradlew check                      # tests + detekt + ktlint + offline guard
./gradlew ktlintFormat               # apply Kotlin formatting fixes
./gradlew detekt ktlintCheck         # static-analysis/style checks only
./gradlew :app:offlineGuard          # merged manifests + runtime dependency graphs
./gradlew --dependency-verification strict check
```

`offlineGuard` fails if either merged app manifest declares `android.permission.INTERNET`, or if a
forbidden networking dependency appears on the debug/release runtime classpath. The
`:baselineprofile:offlineManifestGuard` task applies the same rule to both test APK variants.
WorkManager's read-only `ACCESS_NETWORK_STATE` permission is allowed.

The current debug JVM/Robolectric aggregate is 425 tests with zero failures, errors, or skips. The
API 34 Managed Device aggregate is 11 tests (Room/data 6, real TFLite 4, app smoke 1), all passing.

## Build artifacts

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

Local APK tasks produce a universal artifact so both APK and App Bundle release paths stay
buildable with AGP resource shrinking. For store distribution, prefer the release Android App
Bundle under `app/build/outputs/bundle/release/`; Play generates optimized ABI-specific device APKs.

Release signing is optional for local compilation. To produce signed release artifacts, provide all
four environment variables (never commit a keystore or its credentials):

```sh
export ALARMCONTROL_KEYSTORE_FILE="/absolute/path/to/release.jks"
export ALARMCONTROL_KEYSTORE_PASSWORD="..."
export ALARMCONTROL_KEY_ALIAS="..."
export ALARMCONTROL_KEY_PASSWORD="..."
./gradlew :app:bundleRelease
```

Providing only some of the variables fails configuration intentionally.

## Instrumented tests

The JVM suite does not replace tests that require the real Android runtime. With a connected device
or emulator, run:

```sh
./gradlew :data:connectedDebugAndroidTest  # supported Room v1/v2/v3/v10/v12 -> v13 migrations
./gradlew :ml:connectedDebugAndroidTest    # bundled TFLite runtime/asset compatibility
./gradlew :app:connectedDebugAndroidTest   # real Activity, Hilt, resources, and navigation smoke test
```

Without a device, compile the test APKs to catch source, resource, and dependency errors:

```sh
./gradlew :data:assembleDebugAndroidTest :ml:assembleDebugAndroidTest \
  :app:assembleDebugAndroidTest :baselineprofile:assemble
```

Do not report a compiled instrumented-test APK as an executed device test.

The Room tests exercise every real path from seeded v1, v2, v3, v10, and v12 databases to v13,
including migration of legacy binary advertisement observations into seven-way semantic-intent priors. The
`:baselineprofile:assemble` lifecycle is configured to compile both generator variants without
starting a device; profile collection remains an explicit command.

## Gradle Managed Device and CI tiers

Each Android runtime module defines the API 34 `pixel2Api34` `aosp-atd` managed device. Run the same
integration suite used by the main/nightly CI job with:

```sh
./gradlew --dependency-verification strict \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  :data:pixel2Api34DebugAndroidTest \
  :ml:pixel2Api34DebugAndroidTest \
  :app:pixel2Api34DebugAndroidTest
```

The software-rendering property is the Android Gradle Plugin's supported headless/CI setting. If
the home volume cannot hold the AVD's writable data, redirect only that disposable data to another
local disk before running the command (the project-local path is git-ignored):

```sh
export ANDROID_AVD_HOME="$PWD/.managed-avd"
mkdir -p "$ANDROID_AVD_HOME"
```

See the official [Gradle Managed Devices documentation](https://developer.android.com/studio/test/managed-devices).

Pull requests run JVM/Robolectric tests, detekt, ktlint, Android Lint, `offlineGuard`, debug/release
APK, release AAB, all instrumented-test APK builds, and both Baseline Profile generator variants.
Main pushes, the nightly schedule, and manual runs additionally execute the managed-device suite.
Gradle wrapper validation runs before both jobs and `gradle/verification-metadata.xml` enforces
strict SHA-256 verification of resolved Gradle artifacts. Failure reports are uploaded as CI
artifacts.

## Baseline Profile and offline startup benchmark

The build-only `:baselineprofile` module contains the startup/top-level-navigation generator and a
cold-start connected benchmark. Normal `build`, `check`, and `assemble` stay device-independent. To
generate or refresh the profile, connect an API 33+ physical device (or a suitable rooted emulator):

```sh
./gradlew :app:generateBaselineProfile
```

Then measure the profile-installed `benchmarkRelease` variant on a representative physical device:

```sh
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.alarmcontrol.baselineprofile.StartupBenchmark
```

The test records ActivityManager `TotalTime` for ten force-stopped launches. It intentionally avoids
AndroidX's trace-processor timing metric because that metric opens a localhost HTTP socket and would
require `android.permission.INTERNET` in the test APK. The offline rule has no benchmark exception.
Performance numbers are device-specific and are not inferred from APK-only compilation.

## Troubleshooting

- **Unable to locate a Java Runtime** — set `JAVA_HOME` to JDK 17.
- **SDK location not found** — set `ANDROID_HOME` or add `local.properties`.
- **Offline guard failure** — inspect the named merged manifest or dependency coordinate; do not
  suppress or weaken the guard.
- **No compatible LLM model** — the optional MediaPipe model is not part of the build. Import a
  compatible local quantized model from Settings; rules and bundled TFLite still work without it.
