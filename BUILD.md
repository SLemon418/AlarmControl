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

The repository includes JVM/Robolectric suites plus connected Room, LiteRT, and app-runtime
instrumented suites. CI also runs the connected set on an API 34 Managed Device where configured.
Release notes must report results from the release commit's executed test reports rather than
copying a historical test count from this guide.

## Build artifacts

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease  # App Bundle compatibility regression; may be unsigned
```

APK and App Bundle paths both remain buildable with AGP resource shrinking. `bundleRelease`
deliberately remains usable without a keystore for local and CI compatibility checks, but an AAB is
not the current publication artifact and its successful compilation does **not** make a release.

For GitHub Releases distribution, provide all four signing environment variables (never commit a
keystore or its credentials) and run the explicit release-candidate gate:

```sh
export ALARMCONTROL_KEYSTORE_FILE="/absolute/path/to/release.jks"
export ALARMCONTROL_KEYSTORE_PASSWORD="..."
export ALARMCONTROL_KEY_ALIAS="..."
export ALARMCONTROL_KEY_PASSWORD="..."
./gradlew :app:releaseCandidate
```

Providing only some of the variables fails configuration intentionally. `releaseCandidate` runs
all device-independent quality/offline checks, compiles the instrumented-test and Baseline Profile
variants, caps the raw semantic classifier at 45 MiB, the physical non-semantic payload at 140 MiB,
and the complete physical APK at 185 MiB. It also verifies the four semantic assets and their
manifest hashes, then runs `apksigner` verification for minSdk 26. The verified universal APK is the
only APK under `app/build/outputs/apk/release/`. Only that APK is a GitHub distribution candidate;
`bundleRelease` remains a CI/format-regression artifact with its existing AAB limits.

The current release APK is universal: it contains native libraries for every supported ABI. GitHub
Releases does not inspect a device and choose an ABI-specific asset as Play does, so users download
the one universal APK. The ABI-independent lightweight semantic classifier is bundled in that APK
for every installation.

### GitHub Release publication

The release workflow runs only when a `vMAJOR.MINOR.PATCH` tag is pushed. The tag's version must
exactly match the APK `versionName`, and its commit must be an ancestor of the repository's default
branch. Every published APK must also increase `versionCode` above the previous release; Android
will not install an equal or lower code as an update. Configure the `github-release` Environment
with:

- `ALARMCONTROL_KEYSTORE_BASE64`
- `ALARMCONTROL_KEYSTORE_PASSWORD`
- `ALARMCONTROL_KEY_ALIAS`
- `ALARMCONTROL_KEY_PASSWORD`

To encode the keystore without a platform-specific clipboard command, pipe it directly from
standard input into the GitHub CLI:

```sh
base64 < /absolute/path/to/release.jks |
  gh secret set ALARMCONTROL_KEYSTORE_BASE64 --env github-release
```

After the quality, offline, test-APK compilation, and signature gates pass, the workflow creates a
release with these assets:

- `AlarmControl-<version>-universal.apk`
- `AlarmControl-<version>-universal.apk.sha256`

An existing tag, release, or same-named asset is never overwritten. Unlike a Play upload key, this
keystore is the actual app-update signing identity trusted by installed APKs. Keep an independent,
encrypted offline backup of the keystore and credentials; a GitHub secret alone is not a backup.
Losing or changing the key makes an in-place update impossible, and uninstalling before reinstalling
can erase AlarmControl's local data unless the user exported a backup first.

Release assets inherit repository visibility. If the repository is private, ordinary users cannot
download the APK without signing in to an authorized GitHub account. Public direct distribution
therefore requires a public repository or a separate public location containing the exact verified
APK and checksum. Users install updates themselves; AlarmControl has no GitHub client, update
checker, or `INTERNET` permission.

Google Play closed testing is not part of this distribution path. Android developer verification
is a separate platform requirement being rolled out for apps installed outside Play. Before it is
enforced in the target regions, follow the current
[Android developer verification](https://developer.android.com/developer-verification) guidance
and register `com.alarmcontrol` plus the long-term release signing key through Android Developer
Console (or through Play Console if the developer maintains one).

The optional generative LLM is never packaged with the app release or counted as app payload. If a
compatible model is made available, distribute it separately with its license and hash; the user
then imports it through the Storage Access Framework. The GitHub app-release workflow does not
upload an LLM.

## Instrumented tests

The JVM suite does not replace tests that require the real Android runtime. With a connected device
or emulator, run:

```sh
./gradlew :data:connectedDebugAndroidTest  # supported Room v1/v2/v3/v10/v12 -> v13 migrations
./gradlew :ml:connectedDebugAndroidTest    # bundled TFLite runtime/asset compatibility
./gradlew :app:connectedDebugAndroidTest   # Activity/Hilt, listener, automation, LLM fallback, WorkManager
```

> **Use a dedicated test device/profile.** AGP's `connectedDebugAndroidTest` lifecycle may uninstall
> the target debug package after the run, which also erases that package's local app data. Export a
> local backup first if the device contains anything valuable. On a data-bearing development phone,
> install the already-built target/test APKs with `adb install -r`, invoke the test runner with
> `adb shell am instrument`, and remove only the `.test` package afterward. Listener tests publish
> controlled notifications through `com.android.shell`; neither APK requests `POST_NOTIFICATIONS`.

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
strict SHA-256 verification of resolved Gradle artifacts. The root `verifyCiActionPins` gate scans
workflow, reusable-workflow, and composite-action YAML: remote actions require a full 40-character
commit SHA and container actions require a SHA-256 image digest. Failure reports are uploaded as CI
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
  To create the app-specific Gemma candidate, follow
  [`ml/llm-training/README.md`](ml/llm-training/README.md); do not import safetensors, GGUF, or the
  intermediate `.tflite`.
- **LLM integrity record missing/mismatched** — re-import the trusted local model from Settings.
  AlarmControl intentionally will not load a model whose import-time SHA-256 record cannot be
  verified.
