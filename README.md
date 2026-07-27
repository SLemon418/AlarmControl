# AlarmControl

**English** | [한국어](README.ko.md)

**A privacy-focused, offline-first notification & alarm filtering app for Android, with on-device AI.**

AlarmControl filters, mutes, and categorizes your notifications entirely on-device. Conceptually
similar to FilterBox, it pairs a rule-based filtering engine with a lightweight on-device ML
classifier that learns from your corrections — and it does all of this **without any network access
whatsoever**. There is no cloud, no telemetry, and no account. Your notification data never leaves
your phone.

> "Alarms" here means alarm-*category notifications* detected via `NotificationListenerService`. The
> app does not schedule alarms or use `AlarmManager`; it can read, **cancel**, and **snooze**
> notifications (and record a local decision log), but it never blocks or pre-empts another app's
> notification.

---

## Key features

### 🔕 Rule-based notification filtering
- User-authored rules combine conditions — package, title/text keywords, Android category, channel,
  time, frequency, ranking/importance, conversation/foreground-service state, ML category, and
  semantic intent — with arbitrarily nested composites (`AllOf` / `AnyOf` / `Not`).
- Rules can run as **Active** or **Monitor**. Active rules may perform the first matching action;
  monitor rules are evaluated independently and record only what *would* have happened.
- Burst rules count package or package+channel posts over a 1-minute to 24-hour window from an
  in-memory tracker seeded once from content-free Room metadata. The current post is included and a
  missing signal resolves to `UNKNOWN`, never to a destructive match.
- Each rule maps to an honest action: **Cancel**, **Snooze**, **Log only**, or **Keep** (allow-list).
  "Log only" has no platform side effect; Android does not let a notification listener silently
  mark an arbitrary notification as read.
- The matching engine is **pure Kotlin** (input: a notification snapshot + rules → output: a
  decision), so the `NotificationListenerService` stays a thin, testable shell.
- New rules start in a guided editor that searches recently observed apps/channels, offers safe
  action explanations and optional time/frequency limits, and defaults destructive drafts to
  **Monitor**. A validated package-name fallback and the full recursive condition tree remain
  available when needed.
- Every decision is recorded to a local activity log for insights, with one-tap exclusion from
  statistics. This never claims to restore a dismissed notification. List and analytics rows store
  only metadata (package/channel, category, action, timestamps); optional encrypted detail content
  is isolated under the explicit privacy control described below.

### 🧠 On-device ML categorization with an incremental feedback loop
- A small **Unicode bag-of-words → softmax** classifier (LiteRT / TensorFlow Lite), bundled in the app,
  sorts notifications into categories (e.g. promotion / social / news / alarm). The vocabulary and
  labels ship as assets next to the model, so they can never drift from it. Its deterministic
  training and held-out checks cover both English and Korean notification text.
- **Degrades gracefully:** if the model is missing or low-confidence, categorization simply returns
  nothing and the rules engine carries on unaffected.
- **Continuous learning via a shrinkage prior (no retraining at runtime).** When you correct a
  category, that feedback is stored locally and blended into future predictions for that package:

  ```
  blended[label] = (1 − β) · model[label] + β · (corrections_for_label / n)
  β = n / (n + K)          // n = corrections for the package, K = smoothing (3)
  ```

  One stray correction barely moves a confident model; consistent feedback gradually dominates. It's
  pure arithmetic over SQL-aggregated counts — no gradients, no backprop, nothing exported.

### 🔎 Optional on-device semantic ad analysis
- A **MediaPipe Tasks GenAI** layer can distinguish hidden promotional intent from transactional
  notices such as bank withdrawals, shipping updates, and security codes.
- Its strict local JSON contract exposes exactly seven intents: `MARKETING`, `TRANSACTIONAL`,
  `SECURITY`, `DELIVERY`, `SOCIAL`, `OTHER`, and `AMBIGUOUS`. The legacy advertisement signal is a
  compatibility view of `MARKETING`.
- It is explicitly **opt-in and off by default**. The user selects a compatible quantized local model
  through Android's Storage Access Framework; the app atomically copies it into private storage and
  never downloads one.
- A reproducible Gemma 3 fine-tuning, held-out evaluation, dynamic-INT8 conversion, and MediaPipe
  bundling kit lives in **[ml/llm-training](ml/llm-training/README.md)**. Base weights and generated
  artifacts remain external because access requires user-accepted terms and the model must pass
  physical-device validation before release.
- Import records a SHA-256 sidecar atomically with the model. Every later initialization verifies the
  complete local file before native loading; Settings shows the full fingerprint and clearly notes
  that integrity-after-import is not publisher certification. If the process stops midway through a
  replacement, the next initialization restores the last verified model; an incompatible activated
  replacement is also rolled back before inference resumes.
- Inference runs only when an enabled rule actually needs the advertisement signal. Missing/corrupt
  models, malformed output, low confidence, or an opted-out setting all resolve to "no signal", so
  the classical classifier and deterministic rule engine continue to work.
- Monitor rules may use an enabled LLM analysis path without changing a notification. Active
  automatic actions still require the separate **LLM automatic actions** opt-in. Corrections use a
  seven-class local shrinkage prior; old binary ad votes migrate to marketing/transactional votes.

### 📊 On-device insights, analysis & notification records
- The Insights screen is organized into **Overview**, **Analysis**, and **Records**. Overview keeps
  today's result and expandable daily cards; Analysis combines any retained date range into
  day/week/month trends plus app, rule, category, channel, hour, semantic-intent, monitor, and local
  learning/correction breakdowns. Every visualization uses native Compose `Canvas` — no chart SDK.
- Records includes all locally analysed decisions, including **Keep**, with SQL-backed action,
  app/channel, and text filters. Installed-app labels and icons are resolved only for presentation.
  Lists never decrypt notification content; an individual payload is read only after the user opens
  its detail view.
- History is pre-aggregated by the periodic worker into a small Room table, so the screen reads
  summarized data instead of rescanning the log. Every metric is a plain SQL aggregation, never ML,
  and empty days/first-run states are handled explicitly.
- Activity rows expose channel settings, active and monitor outcomes, matched rule names,
  confidence values, and a bounded content-free condition trace (`MATCH` / `NO_MATCH` / `UNKNOWN`).
  Channel controls open Android's exact system channel screen when available and safely fall back
  to app notification settings; AlarmControl never edits another app's channel directly.
- Local suggestions are conservative: heavily silenced channels can prompt a system-channel review,
  and repeated marketing corrections can open a **Monitor + Cancel** rule draft. Suggestions are
  never auto-saved or auto-enabled and dismissed suggestions stay dismissed locally.

### 🤖 External automation with secure gating
- **Samsung Modes and Routines** directly invokes AlarmControl's dynamic App Shortcuts through
  **Applications → Open an app or do an app action**. Enable/Pause filtering and published profile
  shortcuts use the first-party path, so they need no external-automation opt-in or token.
- A separate exported `BroadcastReceiver` lets Tasker, MacroDroid, and compatible tools
  enable/disable filtering through a documented intent contract. Users can create named profiles
  that group several rules; `ENABLE_PROFILE` / `DISABLE_PROFILE` target a profile id or name through
  the optional `PROFILE_ID` extra. Omitting it controls the independent master switch while
  preserving every individual rule state.
- **Hardened external boundary:** the receiver is inert until the user enables *Settings → "Allow
  external automation"* (off by default), and every request must carry the random per-install
  `AUTH_TOKEN` shown there. The sender must explicitly target the AlarmControl package/component so
  the token is never placed on an implicit broadcast. Requests are rate-limited to 12 per minute;
  the token can be rotated at any time and is never backed up.
- A bounded, content-free local audit shows recent source/action/result/count metadata for
  troubleshooting. It never records tokens, target names, or notification content.
- **Quick Settings tile** for a first-party master switch straight from the notification shade,
  plus dynamic launcher shortcuts for the master switch and the available named profiles.
- Full Samsung Shortcut and authenticated-Intent setup (including an `adb` smoke test): see
  **[docs/automation.md](docs/automation.md)**.

### 🔁 Local backup & restore
- Export your **rules tree, named profiles, selected settings, and daily history** to a structured
  JSON file — or restore from one — through a **local-only Storage Access Framework** request.
  AlarmControl itself never uploads it.
- Backups can optionally be protected with **PBKDF2-HMAC-SHA256 + AES-256-GCM**. Plain JSON remains
  available for portability and must be treated as readable. New encrypted exports require an
  8-character password; shorter legacy passwords remain accepted for restore. Package-level
  category/ad learning votes may be included only when encryption is enabled; notification text,
  LLM reasoning, passwords, and automation tokens are never exported.
- Restore first presents a validated preview, then supports merge or replacement and section-level
  selection. Rules/profiles remain one referential unit, and malformed or unauthenticated input is
  rejected before any local state changes.

### 🧹 Local privacy controls
- Settings include independent retention windows for the activity log and daily summaries.
- Optional **notification detail history** is off by default. If enabled, non-secret title/body
  payloads are length-bounded and protected by a non-exportable Android Keystore AES-256-GCM key,
  can be disabled per app, and expire after seven days. Turning it off deletes all ciphertext and
  the key immediately. Content never enters aggregates, logs, or backup files.
- Users can clear activity, feedback, insights, or all local app data with explicit confirmation.
  Clearing everything also removes the optional imported LLM model and resets local preferences.

### 🎨 Material You & interactive rule building
- **Material You dynamic color** (Android 12+): every surface and the native-`Canvas` charts recolour
  from the system wallpaper, with full Dark Mode support; older devices fall back to the bundled
  light/dark scheme.
- The nested condition builder edits deeply nested AND/OR/NOT logic visually — add, remove, and
  **reorder sibling nodes** to change their evaluation order — with inline validation hints when a
  condition is left empty or malformed.

---

## Architecture & modules

Clean, layered architecture (MVVM + unidirectional data flow + repository pattern) split into six
runtime Gradle modules plus one build-only performance-test module. The boundaries make the offline
and on-device rules **structurally enforceable**.

| Module | Responsibility |
|--------|----------------|
| `:app` | Compose UI (Material 3, single-Activity, Navigation-Compose), DI wiring, and the `NotificationListenerService`. **The only module with Compose.** |
| `:core` | Framework-free domain: models, repository interfaces, dispatchers, `Result` types. No Android UI, no Compose. |
| `:data` | The only module that persists — Room (KSP) + DataStore, repository implementations, mappers. |
| `:ml` | Bundled LiteRT classifier, Unicode feature extraction, feedback blender, and optional local MediaPipe LLM. Model I/O stays behind interfaces. |
| `:notifications` | The pure, framework-free matching engine (rules → decision). Unit-testable without Android. |
| `:automation` | Exported intents, profile-toggle controller, Quick Settings tile, and dynamic launcher shortcuts. |
| `:baselineprofile` | Build-only Baseline Profile generator and offline connected startup benchmark; never packaged into the app. |

**Dependency direction:** `:app` → `:data` / `:ml` / `:notifications` / `:automation` → `:core`.
Lower layers never depend on `:app` or on Compose, and the feature modules don't depend on each
other — shared contracts live in `:core`. This is why, for example, the ML feedback blender can read
user corrections through a `:core` interface without `:ml` ever touching `:data`.

Tech stack: **Kotlin** (Coroutines + Flow), **Jetpack Compose / Material 3**, **Hilt**, **Room v13**,
**DataStore**, **LiteRT (TensorFlow Lite)**, Gradle Kotlin DSL + version catalog. minSdk 26,
compile/target SDK 36.

---

## Privacy & security

Privacy isn't a policy here — it's enforced by the build:

- **No `INTERNET` permission. Anywhere.** Not in the app, any module, or any debug/test manifest.
  Its absence is the guarantee: the app cannot open an Internet socket, so notification data cannot
  leave through an app network connection. Re-adding it is treated as a release blocker.
- **No networking dependencies** on the classpath (no Retrofit/OkHttp/Ktor/Firebase/analytics/
  crash-upload SDKs).
- **All AI/ML runs locally.** The lightweight classifier is bundled in `:ml/src/main/assets/`. The
  optional, much larger LLM is selected by the user from local storage and copied into app-private
  storage. Neither model is ever downloaded by the app; all inference and feedback stay on-device.
- **Data stays local and minimal.** Decision lists, analytics, feedback, backup, and logs never
  contain notification titles or bodies. The optional detail feature stores only bounded
  AES-GCM ciphertext in a separate Room child table for seven days and decrypts one selected row
  locally; it is off by default, excludes `SECRET` notifications, and supports per-app exclusion.
- The exported automation receiver carries no network capability. It requires opt-in plus a
  per-install token, rate-limits request storms, and changes only the local master switch or an
  explicitly targeted profile/rule.

The bundled compact model is produced by an **offline training pipeline** (`ml/training/`, a
build-time dev tool — not shipped and not part of the Gradle build). The optional LLM has a separate
local-only model pipeline in [`ml/llm-training/`](ml/llm-training/README.md); neither its base weights
nor generated artifacts are committed or packaged in the app.

---

## Build & test

Full local setup (JDK 17 and Android SDK 36; the Gradle wrapper is included) is documented in
**[BUILD.md](BUILD.md)**. In short:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

./gradlew assembleDebug        # build the universal local debug APK
./gradlew :app:bundleRelease   # release-shape compile check; may be unsigned
./gradlew test                 # all modules' 564 JVM unit and Robolectric test methods
./gradlew check                # tests + detekt + ktlint (also run as part of `build`)
./gradlew build                # complete device-independent build and quality gate
```

For a distributable, signed bundle, configure the four `ALARMCONTROL_*` signing environment
variables documented in [BUILD.md](BUILD.md) and run `./gradlew :app:releaseCandidate`.

### Tests run entirely on the local JVM — no emulator required

- **Unit tests** across every module: the pure matcher (`:notifications`), repositories and mappers
  (`:data`), the classifier decision logic and feedback blender (`:ml`), and ViewModels with
  Turbine + MockK (`:app`).
- **Compose UI tests via Robolectric** (`:app`) — real Compose screens, dialogs, and dropdown menus
  exercised on the JVM. Run them with:

  ```sh
  ./gradlew :app:testDebugUnitTest
  ```

  They use `@RunWith(RobolectricTestRunner::class)` with `@GraphicsMode(NATIVE)` and a pinned
  `@Config(application = Application::class, sdk = [34])` so Compose lays out without booting Hilt.
- **ML determinism:** classification tests pin the bundled model + fixtures and assert exact labels.
- **564 local JVM test methods** across the runtime modules, plus **19 instrumented tests**
  (Room/data 7, real TFLite 4, app runtime 8); they pass on the connected Galaxy and CI runs the
  same suite on an API 34 Managed Device.
- **Instrumented tests** (run on a device/emulator) cover the paths the JVM can't:
  - `:ml/src/androidTest` validates the real TensorFlow Lite runtime against the bundled model.
  - `:data/src/androidTest` contains **Room migration tests** that upgrade seeded v1, v2, v3, v10,
    and v12 databases to v13 and assert rules/events/feedback survive while
    daily-history, profiles, local LLM observations, imported priors, and automation audit tables
    remain usable.
  - `:app/src/androidTest` launches the real Activity, verifies the Hilt/resources/navigation smoke
    path, exercises monitor/cancel/snooze decisions, a rapid 20-post burst, and forced Doze through
    the real notification listener, confirms Android Ranking importance and the Hilt WorkManager
    rollup path, exercises authenticated exported automation without exposing its token, and checks
    that a missing user-imported LLM fails open through the production DI graph.

  ```sh
  ./gradlew :ml:connectedDebugAndroidTest
  ./gradlew :data:connectedDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest
  ```

  Run `connectedDebugAndroidTest` only on a dedicated test device/profile: AGP may uninstall the
  target debug package after the run and erase its local data. Back up valued data first, or install
  the built APKs manually and invoke `adb shell am instrument` so only the `.test` package is removed
  afterward. Listener tests use controlled `com.android.shell` notifications, so neither APK requests
  `POST_NOTIFICATIONS`.

- **Baseline Profile and startup benchmark:** `:baselineprofile` compiles as part of normal builds
  without requiring a device. Generate/refresh the real profile on a connected API 33+ device with
  `./gradlew :app:generateBaselineProfile`; run the connected `StartupBenchmark` on a representative
  physical device. It measures ten ActivityManager cold starts without granting the test APK
  `INTERNET`. A compiled generator APK is not reported as a measured performance result.

### Code quality is enforced, not optional

**detekt** and **ktlint** (via the jlleitschuh plugin) are wired into every module's `check` task —
and therefore `build` — so a style or static-analysis violation fails the build. Auto-format with:

```sh
./gradlew ktlintFormat          # apply formatting fixes
./gradlew detekt ktlintCheck    # verify (no findings permitted)
```

---

## Contributing

Project conventions, the locked architectural decisions, and the non-negotiable rules (no network,
on-device only, data stays local) are documented in **[CLAUDE.md](CLAUDE.md)**. Please read it before
making changes — the offline and on-device guarantees are the reason this project exists.

---

## Milestone 2 — completed

- **WorkManager periodic insights.** A daily on-device worker aggregates muted-app metrics and
  anomaly spikes via SQL, purges expired log rows, and persists a headline surfaced on the Insights
  screen — all local, no network.
- **Dynamic App Shortcuts.** Long-press the launcher icon for "Enable filtering" / "Pause
  filtering", routed through an invisible trampoline that changes the independent master switch
  without overwriting individual rules.
- **Automated offline guard.** `./gradlew test` and `check` **fail** if
  `android.permission.INTERNET` appears in an app or Baseline Profile test APK, or a networking
  library (OkHttp/Retrofit/Ktor/gRPC/…) lands on the classpath, while explicitly allowing
  WorkManager's read-only `ACCESS_NETWORK_STATE`. This makes §3 a machine-checked guarantee.

## Milestone 3 — completed

- **Advanced compound & time-window rules.** Arbitrarily nested `AllOf` / `AnyOf` / `Not` conditions
  plus time-window constraints, authored through a recursive visual rule builder that preserves the
  full condition tree instead of flattening it.
- **Engine performance & scaling.** Rules compile once (enabled-only, priority-sorted) into a cached
  set the listener reuses, so each notification evaluates against an in-memory snapshot rather than a
  per-event DB read; condition evaluation short-circuits, with JVM benchmarks guarding the speed.
- **Daily insight history.** A Room `DailyInsight` rollup (per-day totals, top rules, category
  breakdown) aggregated by the periodic worker via SQL, surfaced on the native-`Canvas` Insights UI
  with reactive rule-name resolution and graceful empty states.
- **Release hardening.** detekt + ktlint enforced on `build`; Room v1/v2/v3/v10/v12 → v13 migration
  test; previewed merge/replace backup and selective encrypted learning-vote restore via the Storage
  Access Framework; named filtering profiles; and UI/UX polish — Material You dynamic color,
  sibling node reordering, inline condition validation, and destructive-action confirmation.

## Milestone 4 — on-device context analysis

- **MediaPipe LLM foundation.** A user-supplied local quantized model is loaded asynchronously and
  exposes explicit idle/installing/loading/ready/unavailable state. Import is size-bounded,
  progress-aware, atomically activated, SHA-256 verified before later loads, and rolled back if
  compatibility loading fails. The model is never fetched by the app.
- **Safe ad signal.** Bounded, injection-resistant prompts and strict JSON parsing produce a
  confidence-gated advertisement signal only when a rule requests it and the user has opted in.
- **Production hardening.** R8/resource shrinking, app bundles with Play-managed ABI delivery,
  adaptive/monochrome launcher
  icons, explicit OS-backup exclusion, English/Korean resources, compact bottom navigation plus an
  expanded-width navigation rail, accessibility semantics, and CI verification are enabled.
- **Startup performance infrastructure.** An official AndroidX Baseline Profile generator exercises
  startup and all top-level destinations. A socket-free connected test measures ten profile-installed
  ActivityManager cold starts; profile generation remains an explicit physical-device step.
- **Offline gate at every release path.** Unit tests plus the Gradle `offlineGuard` task scan both
  debug/release merged manifests and runtime dependency graphs during `check`, APK assembly, and
  bundle creation.
- **Immutable CI inputs.** `verifyCiActionPins` scans workflow, reusable-workflow, and local
  composite-action YAML. Remote actions must use full commit SHAs and container actions must use
  SHA-256 image digests.

## Milestone 5 — advanced local control and explainability

- **Channel-aware control and burst filtering.** Events and daily rollups retain channel ids and
  counts; frequency rules support package/channel scopes, bounded custom windows, and safe
  three-state evaluation backed by a once-seeded in-memory tracker.
- **Active + monitor lanes.** Active and monitor rule sets evaluate independently from one compiled
  cache. Only the active winner can perform an action; the monitor winner records an expected action
  for safe real-notification trials and separate analytics.
- **Protection and rule quality.** Editable high-priority Keep templates cover conversations,
  foreground services, high-importance notifications, and alarms. A conservative pure-Kotlin
  analyzer reports provable duplicates, shadowing, contradictions, double negation, and redundant
  groups without blocking save.
- **Explainable outcomes.** Activity history stores only condition kind/result/depth for the selected
  active and monitor rules, capped at 128 nodes. It never stores predicate values, notification
  content, or LLM reasoning.
- **Local suggestions and seven-way semantics.** SQL-only 7-day thresholds produce dismissible rule
  drafts, while semantic corrections and priors cover all seven intent classes. Nothing is trained,
  enabled, or exported automatically.
- **Compatibility and supply-chain gates.** Room migrations and versioned backups preserve prior data.
  Pull requests run JVM/Robolectric, quality, offline, release, and test-APK checks; main/nightly
  runs additionally execute API 34 Gradle Managed Device tests. Gradle wrapper validation and
  strict SHA-256 dependency verification are required.

Detailed behavior and privacy fields are documented in the
**[rules guide](docs/RULES_GUIDE.md)** and **[privacy guide](docs/PRIVACY.md)**.

## Milestone 6 — guided control and richer local history

- **Guided rule creation.** Search observed apps and channels instead of guessing identifiers, see
  the effect of Active versus Monitor before saving, add optional time/burst limits, and use inline
  validation for the advanced package fallback. Existing complex rules always reopen in the
  lossless advanced tree editor.
- **Range analysis and organized records.** The three-tab Insights experience adds retained-range
  day/week/month aggregation, app/rule/channel/hour/semantic and learning metrics, and an indexed
  activity browser that includes Keep decisions without loading content into list queries.
- **Optional encrypted detail.** Users may opt in to seven-day, per-app-excludable notification
  title/body history protected by Android Keystore AES-256-GCM. `SECRET` notifications are never
  stored; disabling the feature deletes ciphertext and its key.
- **Compatibility.** Room v13 preserves legacy data from every supported migration origin. Backup
  v6 adds semantic-analysis scope and breakdown-completeness metadata, restores v1–v5, and still
  excludes notification content, LLM reasoning, keys, and automation tokens.

## Release stabilization — completed

- **Bounded notification pipeline.** The listener tracks at most 64 posts, evaluates four
  concurrently, replaces stale work for the same notification, and revokes pending actions when
  rules or privacy settings change. Cache initialization fails open after two seconds.
- **Correct, bounded analytics.** Daily and today metrics use the local day captured at post time
  (legacy rows fall back to timestamps). WorkManager backfills at most seven missing days, while raw
  history is capped at 10,000 events and detailed traces at 1,000 events.
- **Efficient explainability.** Matching and trace creation share one short-circuiting tree walk,
  with active and monitor explanations capped at 128 content-free nodes in total.
- **Security and UX hardening.** Sensitive windows use reference-counted screenshot protection,
  automation token copies are marked sensitive and expire after 60 seconds, data deletion steps are
  failure-isolated, duplicate profile names are rejected case-insensitively, and rule warnings stay
  collapsed until requested.
- **Release gates.** The release AAB must remain at or below 60 MiB, and detekt, ktlint, all local
  tests, migration/test APK compilation, dependency verification, and offline guards remain
  mandatory. `bundleRelease` may intentionally be unsigned for CI compilation; the publishable
  path is `:app:releaseCandidate`, which requires the complete signing configuration and
  cryptographically verifies signed AAB payload entries.

## Physical Galaxy validation

On 2026-07-27, a Galaxy Note20 5G (`SM-N981N`, Android 13 / API 33, One UI 5.1) completed:

- all 19 physical-device instrumentation tests (`:data` 7, `:ml` 4, `:app` 8);
- real notification-listener binding, independent monitor prediction, active cancellation, Samsung
  snooze storage, stored decision traces, and the exact One UI channel-settings deep link using
  content-free test posts;
- notification-listener permission removal, immediate unbinding, re-grant, and automatic service
  recreation after the app process was stopped;
- a rapid 20-notification burst and active cancellation while the device was forced into deep idle;
- Android Ranking importance reaching a real protection condition, authenticated profile automation
  rejecting a bad token without throttling the next valid request, and missing-LLM fail-open behavior;
- Samsung Modes and Routines with Routine+ 1.0.60 discovering the real AlarmControl App Shortcuts,
  with manual Pause/Enable routines changing the master switch off and back on;
- the Hilt-created WorkManager aggregation path and persisted `DailyInsight`;
- release APK/AAB offline gates and generated Baseline/Startup Profiles (25,859 rules each); and
- the offline connected startup test: 10 cold launches, 206 ms minimum, 213 ms median, 215.4 ms
  mean, and 233 ms maximum. These figures describe this device/run, not all supported devices.

The temporary test rule and activity records were removed after validation.

## Roadmap

- Validate Doze/battery behavior and notification ranking across additional One UI/API versions, and
  run the authenticated exported-Intent contract from actual Tasker/MacroDroid sender apps.
- Run the new v13 migration, guided editor, range analysis, Records detail, retention, and Keystore
  deletion scenarios on the connected Galaxy before publishing this milestone.
- Validate representative MediaPipe quantized models and latency/thermal behavior across a physical
  device matrix; this cannot be proven by local JVM or managed-emulator tests.
- Expand the bilingual classifier dataset with anonymized, hand-authored fixtures for more languages
  and additional transactional categories without collecting user notification content.
