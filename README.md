# AlarmControl

**English** | [한국어](README.ko.md)

An offline-first Android notification filter with rules, local analytics, and on-device AI.

AlarmControl evaluates notifications on your phone and can cancel, snooze, keep, or record them
according to rules you control. The installed app has no account, telemetry, cloud service, model
download, or `INTERNET` permission. Notification data stays on the device.

> **Scope:** AlarmControl handles notifications only after Android posts them. It does not schedule
> alarms, intercept a system alarm that has no notification, mark another app's notification as
> read, or guarantee that a heads-up notification never appears.

> **Distribution:** GitHub Releases is the intended binary channel. Install only a release that
> contains both `AlarmControl-<version>-universal.apk` and its `.sha256` file. GitHub's source
> archives are not Android installers. If those assets are absent, there is no installable public
> release for that version.

[Releases](../../releases) · [User guide](docs/USER_GUIDE.md) ·
[Privacy](docs/PRIVACY.md) · [Build from source](BUILD.md)

## Quick start

1. Use Android 8.0 or later.
2. Download the signed universal APK and matching checksum from the same GitHub Release.
3. Allow **Install unknown apps** only for the browser or file manager opening the APK.
4. Open AlarmControl and grant **Notification access**.
5. Create a **Monitor** rule first.
6. Review its predictions under **Insights → Records**.
7. Change the rule to **Active** only after its matches are safe.

The bundled classifiers work without any additional model file. The optional generative LLM is not
required for normal filtering.

See the [user guide](docs/USER_GUIDE.md) for checksum commands, updates, backup, safe rule setup,
automation, and troubleshooting.

## What it does

- **Rules first:** match an app, channel, title or text, Android category, time, frequency,
  importance, conversation state, foreground-service state, local ML category, or semantic intent.
- **Safe evaluation modes:** Monitor records what would happen without changing the notification.
  Active may perform the first matching action, ordered by rule priority.
- **Honest actions:** Cancel and Snooze call Android notification APIs. Keep and Log only are
  record-only decisions with no hidden platform side effect.
- **On-device categorization:** bundled local models classify broad categories and seven semantic
  intents. Missing, ambiguous, timed-out, or low-confidence results fail open.
- **Local learning:** explicit label corrections adjust package-level predictions locally without
  runtime backpropagation or data export.
- **Insights and records:** review activity, date-range trends, app/channel/rule breakdowns,
  decision traces, and conservative rule suggestions.
- **Profiles and automation:** group rules into profiles and control them from the app, Quick
  Settings, launcher shortcuts, Samsung Modes and Routines, Tasker, or MacroDroid.
- **Local backup and restore:** export rules, profiles, selected settings, and daily summaries to a
  user-chosen file with optional password-based encryption.

## A safe rules workflow

AlarmControl has independent Active and Monitor lanes:

- **Monitor** never cancels or snoozes. It records the predicted action so you can inspect it.
- **Active** may cancel or snooze the notification after a trusted match.
- Higher priority rules run first within each lane.
- An unavailable signal is `UNKNOWN`, not a match.
- High-priority Keep rules can visibly protect alarms, calls, conversations, ongoing services, or
  important alerts.

New destructive drafts start in Monitor mode. Templates and suggestions open editable drafts; they
are never saved or enabled automatically. The rule editor also includes a simulator that does not
touch real notifications.

The detailed condition and priority behavior is documented in the
[rules guide](docs/RULES_GUIDE.md).

## Privacy by construction

- Final merged app and test APK manifests do not grant `android.permission.INTERNET`.
- Runtime modules contain no network client, analytics uploader, crash uploader, or remote model
  fetcher.
- Ordinary activity records contain metadata, not notification title or body text.
- Optional notification detail storage is off by default. When enabled, eligible future title/body
  content is length-bounded, encrypted with an Android Keystore AES-256-GCM key, and removed after
  seven days. Secret notifications and excluded apps are never stored.
- Notification content, LLM reasoning, automation tokens, passwords, and encryption keys are never
  included in portable backups.
- Android OS cloud backup is disabled. User-directed backup and model pickers request local
  providers only.

See [Privacy and local data](docs/PRIVACY.md) for the complete storage and deletion boundaries.

## On-device AI

Normal filtering includes two bundled local paths:

1. A lightweight category classifier for labels such as promotion, social, news, and alarm.
2. A seven-intent semantic encoder for `MARKETING`, `TRANSACTIONAL`, `SECURITY`, `DELIVERY`,
   `SOCIAL`, `OTHER`, and `AMBIGUOUS`.

Only trusted bundled semantic results may become an Active-rule signal. The model and its
vocabulary, labels, and manifest are hash-bound assets. Classification failure leaves the
notification unchanged.

A separate MediaPipe `.task` generative LLM can be imported manually from local storage for
compatibility work. AlarmControl never downloads it. The current build has no verified automatic
background LLM profile, and generative results are observation-only: they cannot retroactively
cancel or snooze a notification.

Training, conversion, and evaluation details live in
[ml/semantic-training](ml/semantic-training/README.md) and
[ml/llm-training](ml/llm-training/README.md).

## Installation and updates

Verify the APK checksum before installation when practical. A checksum confirms that the APK
matches the file listed in the same release; it does not independently certify the publisher.
Android's package signature then enforces signing-key continuity for updates.

Install a newer APK over the existing app. Do not uninstall first: uninstalling removes local app
data. Android accepts an in-place update only when the new APK has a higher `versionCode` and the
same signing key. AlarmControl does not check GitHub or update itself.

The universal GitHub APK contains every supported native ABI because GitHub does not perform
device-specific delivery. The optional generative LLM is separate and is never bundled in the APK.

Detailed installation, checksum, development-build migration, and recovery guidance is in the
[user guide](docs/USER_GUIDE.md).

## Automation

Samsung Modes and Routines uses AlarmControl's first-party App Shortcuts and does not require an
automation token. Quick Settings and launcher shortcuts use the same local profile controller.

Tasker, MacroDroid, and compatible tools use a separate explicit broadcast contract. That route is
off by default and requires the per-install token shown in Settings. Implicit broadcasts are
rejected and requests are locally rate-limited.

See [Automation: Samsung Routines, Tasker, and MacroDroid](docs/automation.md).

## Documentation

| Document | Purpose |
|---|---|
| [User guide](docs/USER_GUIDE.md) | Installation, first setup, everyday use, backup, and troubleshooting |
| [Rules guide](docs/RULES_GUIDE.md) | Conditions, priorities, Monitor/Active behavior, and platform limits |
| [Automation guide](docs/automation.md) | Samsung Routines, Quick Settings, Tasker, and MacroDroid |
| [Privacy and local data](docs/PRIVACY.md) | Stored data, encryption, retention, backup, and deletion |
| [Build guide](BUILD.md) | Toolchain, tests, signing, and GitHub Release preparation |
| [Architecture rules](CLAUDE.md) | Locked offline, privacy, module, and release constraints |

## Build from source

The project uses JDK 17, the Gradle wrapper, and Android SDK 36.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew assembleDebug
./gradlew check
```

`assembleDebug` creates an automatically debug-signed development APK. A distributable GitHub APK
must instead use the long-lived release key and pass `:app:releaseCandidate`; see [BUILD.md](BUILD.md).
Never commit a keystore or signing credentials.

## Architecture

AlarmControl uses Kotlin, Coroutines/Flow, Jetpack Compose, Hilt, Room, DataStore, WorkManager,
LiteRT, and optional MediaPipe Tasks GenAI.

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, dependency wiring, and notification-listener entry point |
| `:core` | Framework-free models and repository contracts |
| `:data` | Room, DataStore, backup, encryption, and repository implementations |
| `:ml` | Bundled classifiers, local feedback, and optional local LLM boundary |
| `:notifications` | Pure Kotlin rule matching and explanation logic |
| `:automation` | Authenticated intents, shortcuts, profiles, and Quick Settings |
| `:baselineprofile` | Build-only startup profile and benchmark tooling |

The release gates verify formatting, static analysis, tests, dependency integrity, offline
manifests/classpaths, model assets, APK payload limits, and APK signature validity. A compiled or
unsigned APK/AAB is not a publishable release.

Device validation has included a Galaxy Note20 5G on Android 13 / One UI 5.1. Device-specific
results are evidence for that configuration, not a performance guarantee for every Android device.
