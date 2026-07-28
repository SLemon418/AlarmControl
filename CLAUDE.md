# CLAUDE.md

**English** | [한국어](CLAUDE.ko.md)

Project guidelines for an **offline-first notification & alarm filtering app** with on-device AI.
Conceptually similar to FilterBox, with rule-based filtering, local ML categorization, and
automation hooks (Tasker/Samsung Routines).

> This file merges with the generic behavioral guidelines (see **§10**). When in doubt, the
> behavioral rules and the **HARD RULES** below win over convenience.

---

## 0. Locked decisions (do not re-litigate without asking)

- **AI: hybrid, classical-first.** Insights come from SQL/Room analytics; categorization from a
  lightweight bundled TFLite classifier. A generative on-device LLM is an *optional,
  device-gated* enhancement layered on top — never a hard dependency of the core filtering path.
- **On-device LLM engine: MediaPipe Tasks GenAI** (`com.google.mediapipe:tasks-genai`), running a
  **local quantized model** (e.g. Gemma) via `LlmInference` (Milestone 4). This supersedes the earlier
  ML Kit GenAI / AICore plan. The model is **not bundled in the APK** (far too large); it lives in the
  app's private `filesDir` after the user imports it through the Storage Access Framework; the app
  never downloads it (§3). When it is absent or fails to load, `OnDeviceLlmManager` reports
  `Unavailable` and the app falls back to rules + the TFLite classifier (§5). Verified offline-clean:
  `tasks-genai` adds no `INTERNET` permission and no blocklisted networking client to the classpath
  (the §3 offline guard passes with it present).
- **Alarm scope: notification filtering only.** "Alarms" means alarm-*category notifications* we
  detect/mute/manage via `NotificationListenerService`. We do **not** schedule alarms and do **not**
  use `AlarmManager`/exact-alarm/full-screen-intent permissions. We cannot intercept another app's
  real system alarm unless it appears as a notification — do not design as if we can.
- **Offline is enforced, not promised.** The app ships with **no `INTERNET` permission** (see §3).
- **Compose stays in `:app`, never in `:core`.** The Material 3 design system / theme lives in the
  `:app` module so the `:data`/`:ml`/`:notifications` layers (which depend on `:core`) can never
  transitively pull UI code into a lower layer. `:core` is deliberately Compose-free; that absence is
  what keeps the layering rule structurally enforceable. Extract a `:core:designsystem` (or
  `:feature:*`) module only when multiple UI consumers justify it — not before (see §4).

---

## 1. Non-negotiables (HARD RULES)

These are the reason the project exists. A change that violates any of these is wrong, even if it
"works."

1. **No network in the installed app. Ever.** No cloud calls, telemetry, crash-reporting upload, or
   remote model fetch from Android runtime code. Developer-only build/training tools and approved
   synthetic or rights-cleared training jobs may use the network; they must never receive user
   notification data, and none of that code or state ships in the APK.
2. **All runtime AI/ML runs on-device.** Inference and user-feedback learning happen locally. The
   compact classifier is bundled; an optional large LLM may only be imported by the user from local
   storage. Developer-only synthetic SFT and conversion are outside the app runtime.
3. **User data stays local.** Notification content never leaves the device, including in logs.

If a task seems to require violating one of these, **stop and ask** — do not work around it.

---

## 2. Tech stack (the agreed baseline — don't substitute silently)

| Concern            | Choice                                                                 |
|--------------------|------------------------------------------------------------------------|
| Language           | Kotlin only; Coroutines + Flow for all async/reactive code             |
| UI                 | Jetpack Compose + Material 3, single-Activity, Navigation-Compose       |
| Architecture       | MVVM + unidirectional data flow; repository pattern                     |
| DI                 | Hilt                                                                    |
| Database           | Room (KSP)                                                              |
| Settings           | DataStore (Preferences)                                                 |
| Background work    | WorkManager (periodic local insights and retention housekeeping)         |
| Notification core  | `NotificationListenerService`                                           |
| On-device ML       | Bundled LiteRT classifier; optional local MediaPipe Tasks GenAI LLM      |
| Automation         | Exported intents + Tasker/Locale plugin; Quick Settings tiles + Shortcuts |
| Build              | Gradle Kotlin DSL + version catalog (`libs.versions.toml`), KSP, R8     |
| Quality            | detekt + ktlint (jlleitschuh), enforced on `check`/`build` (§9)         |
| minSdk / target    | minSdk 26; compile/target = latest stable                              |

Adding a major dependency (anything new in the table's spirit) is an architectural decision —
propose it first, don't just add it.

---

## 3. Offline enforcement (how the HARD RULES are kept honest)

- **No `INTERNET` permission** in any manifest, including modules and `debug`/test manifests. Its
  absence is the guarantee — the app cannot open a socket. Treat re-adding it as a release blocker.
- **No networking dependencies in Android runtime modules**: no Retrofit, OkHttp, Ktor client,
  Volley, Apollo, Firebase, analytics, or crash-upload SDKs. Build-only tools under model-training
  directories may use developer-installed download clients, but they are never Gradle/runtime
  dependencies or APK content.
- **Classifier models are bundled** in `:ml/src/main/assets/` and loaded from there. No
  download-at-runtime.
- **The generative LLM model is not bundled** (too large for the APK): the user selects a compatible
  local file through the Storage Access Framework, and the app atomically copies it to private
  `filesDir` storage (`MlConfig.LLM_MODEL_FILE`). The app **never downloads** it and declares no
  `INTERNET`. Import atomically records a SHA-256 sidecar; every later initialization hashes the
  complete file before native loading and rejects a missing or mismatched record. This detects
  changes after import but does not certify the model publisher. A missing, invalid, or changed file
  degrades gracefully (§0/§5).
- **User-directed backup/model file pickers request local providers only** with
  `Intent.EXTRA_LOCAL_ONLY`; AlarmControl never offers or implements a cloud upload path.
- **Portable backup v6 is local and bounded.** It contains rule modes and supported condition
  trees, named profiles, richer channel/app/hour/semantic daily summaries, selected settings, and
  optional seven-way semantic votes, while still restoring v1–v5 files. Package-level learning votes
  are optional and may appear only inside a password-derived AES-256-GCM envelope; notification
  content, LLM reasoning, and the per-install
  automation token are never exported. Restore is previewed, validated, and applied transactionally.
- **Notification detail history is explicit opt-in and local.** It is off by default. When enabled,
  non-`SECRET` title/body payloads are bounded, encrypted with an Android Keystore AES-256-GCM key,
  stored in a cascade child row, and removed after seven days; users may exclude packages. Lists,
  search, analytics, logs, and backups never read or contain plaintext. Turning the setting off
  deletes every ciphertext row and the non-exportable key immediately.
- **Enforced by automated gates:** `OfflineManifestGuardTest` (Robolectric, real merged manifest)
  and `OfflineGuardTest` (classpath) fail the JVM test gate on violations. The Gradle
  `:app:offlineGuard` task also scans both debug/release merged manifests and resolved runtime
  dependency graphs during `check`, APK assembly, and bundle creation. The build-only
  `:baselineprofile:offlineManifestGuard` applies the same rule to both Baseline Profile test APKs.
  These gates reject `INTERNET` and networking libraries
  (OkHttp/Retrofit/Ktor/gRPC/Volley/Apollo/Firebase). WorkManager's read-only
  `ACCESS_NETWORK_STATE` is explicitly allowed because it cannot move data. Never weaken this gate.

---

## 4. Module architecture

Boundaries exist to keep features small and to make the offline rule structurally enforceable
(only `:ml` touches runtime model assets; no Android runtime module touches the network).

```
:app           Compose UI host, navigation, DI wiring, the NotificationListenerService entry point
:core          framework-free domain models, repository contracts, dispatchers, Result types
:data          Room v13 + DataStore, repositories, backup, mappers (the only module that persists)
:ml            bundled classifier, optional local LLM, feature extraction, feedback/learning
:notifications notification matching/filtering engine (pure, testable logic)
:automation    exported intents, Tasker/Locale plugin, QS tiles, App Shortcuts
:baselineprofile build-only profile generator + startup benchmark (never packaged into the app)
```

- The six runtime modules remain fixed. `:baselineprofile` is the one build-only test module and has
  no runtime dependency edge. Don't create another module until it earns its place (§10).
- **Dependency direction:** `:app` → features → `:data`/`:ml`/`:notifications` → `:core`. Lower
  layers never depend on `:app` or on Compose.
- `:notifications` matching logic is **pure Kotlin** (input: a notification snapshot; output: a
  decision). The `NotificationListenerService` is a thin shell that delegates to it — so the engine
  is unit-testable without Android.

---

## 5. AI/ML rules

- **Don't reach for ML when SQL will do.** "Statistical insights" = Room aggregations. Only
  categorization and pattern-learning use a model. If a feature is expressible as a query, query it.
- **Runtime inference is local and deterministic in tests.** The compact classifier is bundled and
  tests pin its model + fixtures to exact labels. The optional LLM is user-imported, device-gated,
  and tested through deterministic engine doubles; neither runtime path may fetch a model.
- **In-app learning is on-device and incremental** (user feedback adjusts local weights/data).
  Never export app training data or gradients off the device.
- **Semantic intent is a closed seven-value contract:** `MARKETING`, `TRANSACTIONAL`, `SECURITY`,
  `DELIVERY`, `SOCIAL`, `OTHER`, and `AMBIGUOUS`. Strictly reject malformed/contradictory output;
  legacy advertisement=true/false feedback maps to marketing/transactional. `IsAdvertisement`
  remains only a compatibility view of `MARKETING`.
- Only a trusted result from the bundled lightweight semantic encoder may become an active-rule
  signal; low-confidence or `AMBIGUOUS` output fails open. Generative LLM results are observation-only
  inputs for future correction, statistics, and suggestions and never change an already handled
  notification. Automatic background LLM work remains disabled until the exact imported model has a
  verified compatibility profile.
- **Categorization must degrade gracefully**: if the model is unavailable or low-confidence, fall
  back to rule-based filtering. The rules engine works without ML; ML only improves it.
- Keep model I/O behind interfaces in `:ml` so LiteRT and the optional MediaPipe runtime can change
  without touching callers.

---

## 6. Notification & filtering engine rules

- The engine is rule-first: a notification is evaluated against user rules; ML categorization is one
  signal among the rule conditions, not a replacement for rules.
- Enabled rules are compiled into independent **ACTIVE** and **MONITOR** lanes. The first active
  match alone may perform a platform action; the first monitor match records an expected action and
  can never shadow or block an active rule.
- Listener work is bounded to 64 tracked notifications and four concurrent evaluations. Queue
  freshness follows the notification's actual post time, so a late callback for an older post
  cannot evict fresher waiting work; running work is never an overflow victim. Newer posts
  invalidate older work for the same notification, and rule or permission changes revoke stale
  actions before their Binder commit. An unavailable startup cache fails open after two seconds.
- Frequency conditions are package or package+channel scoped, include the current post, and support
  1 minute–24 hours with thresholds 2–1000. Seed the content-free in-memory tracker once from Room;
  never query Room per notification. Missing seed/channel/ranking signals evaluate to `UNKNOWN`,
  which is not actionable.
- Channel controls only deep-link to Android's exact channel settings (falling back to app-level
  notification settings). A notification listener cannot directly modify another app's channel or
  prevent a heads-up notification before it appears; do not imply otherwise.
- Protection behavior is represented by visible, editable, high-priority `Keep` rules — never by a
  hidden exception. Conversation, foreground-service, importance, and alarm templates open drafts
  and do not auto-save.
- Condition matching and explanation tracing share one short-circuiting traversal. Persisted
  explanations contain only lane, condition kind, three-state result, depth, and position, capped
  at 128 nodes across active and monitor lanes. Trace rows never persist predicate values,
  notification content, or LLM reasoning.
- Rule suggestions are SQL-derived local drafts only. They must never auto-save or auto-enable, must
  exclude an existing structurally identical rule, and must honor locally persisted dismissals.
- Map only what we can actually act on: **cancel** and **snooze**. `Keep` and the legacy `MarkRead`
  action are record-only decisions with no platform side effect. Don't design UI/affordances that
  imply silently marking another app's notification as read, blocking a system alarm, or preventing
  a heads-up notification before it appears — we can't (see §0).
- Persist a content-free local event record for each decision (for insights and optional statistics
  exclusion). Optional detail title/body is a separate, bounded, Keystore-encrypted seven-day child
  payload and is never used by list queries or analytics. Exclusion cannot restore a dismissed notification.
- Raw history is bounded by both its configured age and the newest 10,000 rows; condition traces are
  retained only for the newest 1,000 events. Daily/today analytics use the local day captured when
  the notification was posted, with timestamp fallback only for legacy rows.
- All `NotificationListenerService` callbacks delegate immediately to pure logic; no business rules
  inside the service class.

---

## 7. Automation & Samsung Routines rules

- **There is no public Samsung "Modes and Routines" third-party action SDK.** Direct Samsung
  integration uses AlarmControl's dynamic App Shortcuts through **Applications → Open an app or do
  an app action**. On the validated One UI 5.1 / Routine+ 1.0.60 device, Routine+ does not expose a
  generic "send broadcast" action. Tasker, MacroDroid, and Locale-compatible tools use the separate
  authenticated exported-intent contract. Don't claim or build against a native Samsung API.
- Exported intents are a **documented, stable contract** (e.g. `ENABLE_PROFILE` / `DISABLE_PROFILE`
  with a `profileId` extra). Treat them like a public API: require the explicit in-app automation
  opt-in **and the per-install `AUTH_TOKEN` extra**, validate actions/extras, rate-limit broadcast
  storms, and don't break them casually. A custom Android permission is intentionally not used
  because external automation tools cannot hold an app-defined signature permission. Keep only a bounded,
  content-free local audit (source/operation/target type/outcome/count; never target names or tokens).
  Require senders to target the AlarmControl package or receiver component explicitly; reject
  implicit broadcasts so another app cannot subscribe to the public action and observe the token.
- Provide Quick Settings tiles + App Shortcuts for manual toggles of the same actions. Samsung
  Routines invokes these first-party shortcuts without the external-automation opt-in or token.
- Named profiles are persisted groups of rule ids. UI, launcher shortcuts, and automation must all
  delegate to the same `ProfileController`; do not duplicate profile-toggle semantics.

---

## 8. Coding conventions

- ViewModels expose a single immutable UI-state `data class` via `StateFlow`; Composables are
  stateless and hoist state. No business logic, I/O, or DB access in Composables.
- Structured concurrency only: inject dispatchers, scope work to `viewModelScope`/WorkManager. No
  `GlobalScope`, no blocking calls on the main thread.
- Errors via sealed `Result`/domain types, not exceptions across layers. No empty catch blocks.
- KDoc the public surface of `:ml` and `:automation` (the non-obvious parts). Elsewhere, prefer
  clear names over comments; comment *why*, not *what*.
- Match the existing style of the file you're in (see §10 "Surgical Changes").

---

## 9. Testing & goal-driven execution

Turn tasks into verifiable goals and loop until green (see §10).

- **Filtering rules**: every rule/condition change ships with unit tests over the pure matcher.
- **ML**: deterministic classification tests against bundled fixtures.
- **Repositories/DB**: Room tests (in-memory or Robolectric).
- **ViewModels/Flows**: Turbine + MockK.
- **Offline**: JVM tests plus the Gradle `offlineGuard` assert no `INTERNET` permission and no
  networking artifact on debug or release runtime classpaths (§3) — this is itself a success
  criterion.
- **Room migrations**: schema upgrades ship with an **instrumented** migration test
  (`:data/src/androidTest`, using `MigrationTestHelper` over the exported schema JSONs) that seeds
  data in old versions and asserts it survives upgrades from v1, v2, v3, v10, and v12 to v13,
  including legacy binary-ad feedback migration to semantic intents.
  Instrumented tests run on a device/emulator (`./gradlew :data:connectedDebugAndroidTest`),
  complementing the JVM unit suite — they are **not** part of the default `./gradlew test` run.
- Run AGP `connectedDebugAndroidTest` tasks only on a dedicated test device/profile: their teardown
  may uninstall the target debug package and erase its local data. Back up valued data first, or
  install the built APKs manually and invoke `adb shell am instrument`; remove only the `.test`
  package afterward. Listener tests use controlled `com.android.shell` notifications, so neither
  product nor test APK requests `POST_NOTIFICATIONS`.
- **Android runtime tests** in `:app` verify the real Activity/Hilt/navigation setup, controlled
  monitor/cancel/snooze decisions, a rapid 20-post burst, forced-Doze cancellation through the
  notification listener, Android Ranking importance, authenticated exported automation, missing-LLM
  fail-open behavior, and the Hilt WorkManager daily rollup path; `:ml` validates the bundled TFLite
  runtime and assets. Compile all test APKs when no device is available.
- **CI has two tiers.** Every pull request runs JVM/Robolectric, quality/offline gates, release
  APK/AAB compilation, all instrumented-test APKs, and Baseline Profile variant compilation. Main
  changes, nightly runs, and manual dispatch additionally execute the `pixel2Api34` `aosp-atd`
  Gradle Managed Device tests for `:data`, `:ml`, and `:app`.
- **A compiled release bundle is not automatically distributable.** CI may run the intentionally
  unsigned `bundleRelease` path. A bundle intended for distribution must instead pass
  `:app:releaseCandidate`, which requires all four release-signing environment variables, runs the
  device-independent gates, enforces the AAB size limit, and cryptographically validates signed
  payload entries. Never commit a keystore or signing credentials.
- **Supply-chain verification is mandatory.** CI validates the Gradle wrapper and resolves artifacts
  with strict SHA-256 checks from `gradle/verification-metadata.xml`. `verifyCiActionPins` scans
  workflow, reusable-workflow, and composite-action YAML; remote actions require a full 40-character
  commit SHA and container actions require a SHA-256 digest. Never bypass or weaken these gates.
- **Startup performance** uses the build-only `:baselineprofile` generator and a socket-free
  connected benchmark that records ActivityManager `TotalTime`. AndroidX trace-processor metrics
  are intentionally excluded because they require `INTERNET` for localhost HTTP even in the test
  APK. Run `./gradlew :app:generateBaselineProfile` only with a connected API 33+ device; normal
  `build` and `check` remain device-independent.
- **Style/static analysis is a build gate, not advisory.** `detekt` and `ktlint` (jlleitschuh) are
  applied to every module and wired into `check` — and therefore `build` — so no style or lint
  violation is permitted: `./gradlew build` (or `check`) fails on any finding. Auto-fix formatting
  with `./gradlew ktlintFormat`; the detekt rules live in `config/detekt/detekt.yml`.

---

## 10. Behavioral guidelines (carried over — apply on every change)

**Think before coding.** State assumptions; if multiple interpretations exist, present them; ask
when unclear instead of guessing. Push back when a simpler approach exists.

**Simplicity first.** Minimum code that solves the problem. No speculative features, abstractions,
configurability, or error handling for impossible cases. If 200 lines could be 50, rewrite it.

**Surgical changes.** Touch only what the request requires. Don't refactor or reformat unrelated
code; match existing style. Mention unrelated dead code — don't delete it. Remove only the orphans
*your* change created. Every changed line should trace to the request.

**Goal-driven execution.** Define success criteria, write the check (often a test), then make it
pass. Loop independently against strong criteria rather than asking "is this what you meant?" after.
