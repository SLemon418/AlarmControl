# AlarmControl Privacy and Local Data

**English** | [한국어](PRIVACY.ko.md)

AlarmControl is offline by construction. The final app has no `android.permission.INTERNET`, no
network client, no telemetry, and no account. Automated manifest/classpath guards fail debug and
release builds if that boundary changes. WorkManager's `ACCESS_NETWORK_STATE` is allowed only to
read system constraints; it cannot transmit data.

## Transient processing

Android supplies notification title/body content to the listener so local text rules and the
bundled classifier can evaluate it. AlarmControl v0.1.0 does not pass notification content to an
imported LLM: imported models remain disabled until the exact local model has a verified
compatibility profile. By default, notification content exists only in memory for the current
processing job. A timeout, model failure, or missing signal falls back to the classical rules path.

## Data stored locally

Depending on enabled features, app-private Room/DataStore/files storage may contain:

- rule names, modes, priorities, actions, condition trees, and predicate values entered by the user;
- named profiles, rule membership, settings, retention periods, and local opt-ins;
- decision metadata: package, channel id, Android/ML category, ML confidence, actual/monitor rule
  ids and actions, timestamps, and the statistics-exclusion flag;
- bounded frequency state: a non-reversible HMAC of the transient listener key, a random occurrence
  id, package/channel, and latest post time. The raw listener key is never stored; state is capped
  at 10,000 occurrences and local housekeeping removes rows outside the longest 24-hour window;
- bounded explanation nodes: active/monitor lane, condition kind, result, depth, and position;
- daily aggregate totals and action, active/monitor rule, category, package/channel, app, local-hour,
  semantic-intent, ML-coverage, and correction counts;
- package-level category and seven-way semantic correction/observation counts and confidences;
- stable suggestion-dismissal keys and a bounded automation audit containing source, operation,
  target type, outcome, changed count, and time;
- a random per-install automation authentication token in app-private Preferences when external
  automation is enabled. It is never logged, included in the audit, or backed up;
- the optional quantized LLM model the user explicitly imports into app-private storage.
- only after explicit opt-in, a separate title/body payload that is length-bounded and encrypted
  with AES-256-GCM under a non-exportable Android Keystore key. `SECRET` notifications and
  user-excluded packages are never stored; ciphertext expires after seven days.

Installed-app names and icons are resolved at display time and are not notification content.

## Data never exported or stored as plaintext notification content

AlarmControl never writes notification content to list/analytics rows, logs, feedback, traces, or
backup files. It never persists:

- rule trace predicate/comparison values copied from a notification;
- LLM prompts, generated reasoning, or free-form model output;
- backup passwords or encryption keys;
- gradients or a runtime training corpus.

Optional detail ciphertext is read only for a user-selected record. A successful opt-out deletes
all ciphertext rows and the key before the setting becomes off; if deletion fails, the setting
remains on and the app asks the user to retry. Fixed error messages are used in logs so
notification content cannot enter logcat.

## Local AI and learning

The compact TFLite model, vocabulary, and labels ship with the app. The optional MediaPipe model is
never downloaded: the user selects a local file and AlarmControl copies it atomically to private
storage. Inference remains on-device. Corrections adjust SQL counts and a shrinkage prior; the app
does not perform cloud or runtime backpropagation.

## Backup v6

Storage Access Framework export/import requests are local-only. Backup v6 can contain rules,
profiles, selected settings, richer channel/app/hour/semantic daily summaries, and supported
condition types, including the bundled-classifier preference, semantic-analysis scope, and
breakdown-completeness metadata. It can
optionally include package-level learning votes only inside a password-derived PBKDF2-HMAC-SHA256 +
AES-256-GCM envelope. Plain backup is intentionally portable and should be treated as readable by
anyone who receives the file. New encrypted exports require at least eight password characters;
shorter legacy passwords remain accepted for restore. Restore previews and validates data before a
transactional merge or replacement, and v1–v5 backups remain supported.

The per-install automation token, frequency occurrence state and HMAC key, imported LLM model,
notification content, and LLM reasoning are never backed up. Android OS cloud backup is disabled
for the app.

## User control and platform boundaries

Retention settings independently bound activity and daily history. Raw activity is additionally
capped at the newest 10,000 rows and condition traces at the newest 1,000 events. Daily/today
analytics use the local day captured at notification post time, falling back to timestamps only for
legacy rows. Optional notification detail history has a fixed seven-day maximum and supports
per-package exclusion. Settings can clear
encrypted details, activity, feedback, insights, or all local data; full deletion also removes
suggestion dismissals, imported model files, keys, and preferences.

AlarmControl cannot modify another app's notification channel. Channel buttons open Android system
settings so the user can make that choice. Exported automation is off by default and requires both
explicit opt-in and a rotatable per-install token, with local rate limiting.
