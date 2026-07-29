# AlarmControl User Guide

**English** | [한국어](USER_GUIDE.ko.md)

This guide covers installing AlarmControl from GitHub Releases, setting it up safely, using its
main features, and recovering from common problems.

AlarmControl is an offline notification filter. It has no account, cloud service, telemetry,
automatic update, or runtime network access. It evaluates notifications after Android posts them.

## Requirements

- Android 8.0 (API 26) or later
- A certified Android device that can grant notification-listener access
- Enough free storage for the universal APK and installed app
- A trusted way to obtain the APK from this repository's GitHub Releases page

The GitHub APK contains all supported native ABIs. Normal filtering needs no separately downloaded
model. An optional generative LLM file is unrelated to initial setup.

## Install from GitHub Releases

### 1. Download the correct files

Open the repository's [Releases page](../../../releases) and download both files from the same release:

```text
AlarmControl-<version>-universal.apk
AlarmControl-<version>-universal.apk.sha256
```

Do not download GitHub's automatically generated **Source code (zip)** or **Source code (tar.gz)**
as an installer. If a release does not contain both APK and checksum assets, it is not an
installable AlarmControl release.

### 2. Check the download

On macOS:

```sh
shasum -a 256 -c AlarmControl-<version>-universal.apk.sha256
```

On Linux:

```sh
sha256sum -c AlarmControl-<version>-universal.apk.sha256
```

On Windows PowerShell, display both values and compare them:

```powershell
Get-FileHash -Algorithm SHA256 .\AlarmControl-<version>-universal.apk
Get-Content .\AlarmControl-<version>-universal.apk.sha256
```

The hashes must match. A checksum detects a changed or incomplete download and confirms that the
APK matches the checksum file in that release. It does not independently prove who published both
files, so obtain them only from the repository you trust.

### 3. Install the APK

1. Open the APK on the Android device.
2. If Android asks, allow **Install unknown apps** for the browser or file manager opening it.
3. Confirm the installation.
4. Afterward, you may turn that per-app installation permission off again.

Android wording and menu paths vary by manufacturer and OS version. Grant the installation
exception only to the app you are currently using to open the verified APK.

## Update without losing data

Download and check the new release, then install its APK over the existing AlarmControl
installation.

Do **not** uninstall first. Uninstalling removes local rules, profiles, records, settings, encrypted
details, and imported model files. Android accepts an in-place update only when:

- the new APK has a higher `versionCode`; and
- it is signed by the same release key as the installed app.

If Android reports a signature conflict, stop and confirm the download source. A locally built
Debug APK and an official Release APK use different keys and cannot update each other. If you
intentionally need to replace a Debug installation:

1. Open **Settings → Backup & restore**.
2. Create a local backup.
3. Confirm that the backup file is readable and stored somewhere safe.
4. Uninstall the Debug build.
5. Install the Release APK.
6. Restore the selected data from the backup.

Encrypted notification details, the automation token, and an imported LLM are not portable and will
be lost during this Debug-to-Release replacement.

AlarmControl never checks GitHub and never downloads an update itself.

## First launch and notification access

AlarmControl opens on the **Rules** screen. The essential setup is notification access:

1. Select **Open settings** from the notification-access card.
2. In Android's notification-access screen, enable **AlarmControl notification filtering**.
3. Read Android's warning and confirm only if you accept that a notification listener can inspect
   notifications.
4. Return to AlarmControl.
5. Check **Settings → App status**. It should show **Notification access: ready**.

Notification access is powerful because Android sends new notification metadata and content to the
listener for local evaluation. AlarmControl processes it on the device and has no `INTERNET`
permission.

Removing notification access stops new filtering and recording. It does not delete existing local
data; use **Settings → Data & privacy** for deletion.

### Battery settings

The Settings screen also reports the current battery policy. Exempting AlarmControl from battery
optimization is optional. With normal optimization enabled, periodic daily summaries may run late,
but the notification listener and live Records view remain separate from that schedule.

## Safe first-rule setup

Use this sequence before allowing any automatic cancellation:

1. Open **Rules** and select **Add rule**.
2. Choose a recently observed app or channel. If no source has been observed, enter a valid Android
   package name such as `com.example.app`.
3. Choose an action.
4. Leave the execution mode as **Monitor**.
5. Optionally add a time window or repeated-notification threshold.
6. Give the rule a recognizable name and save it.
7. Wait for representative notifications.
8. Open **Insights → Records** and inspect the predicted action and condition trace.
9. Edit the rule and change it to **Active** only after the matches are safe.

Nothing in this checklist enables a destructive rule automatically.

## Actions and execution modes

### Actions

| Action | Effect |
|---|---|
| **Cancel** | Dismisses a matching posted notification through Android |
| **Snooze** | Asks Android to hide the notification for the selected duration |
| **Keep** | Records an allow decision and prevents a lower-priority matching Active rule from acting |
| **Log only** | Records the decision without changing the notification |

Keep and Log only do not mark another app's notification as read and have no other hidden platform
side effect.

### Monitor and Active

| Mode | Effect |
|---|---|
| **Monitor** | Records what the rule would do; never cancels or snoozes |
| **Active** | May apply the first trusted matching action |

Active and Monitor rules are evaluated in independent lanes. Within each lane, higher numeric
priority runs first and the first definite match wins. Disabled rules are not evaluated.

If required information is unavailable, the condition result is `UNKNOWN`. `UNKNOWN` is not an
actionable match, so the notification is left alone.

## Create and test rules

The guided editor covers the most common flow:

- choose a whole app or one observed channel;
- choose Cancel, Snooze, or Keep;
- optionally restrict the rule by local time;
- optionally require repeated notifications from the app or app/channel;
- name and save the rule.

Use **Test this rule** to enter sample metadata and inspect `MATCH`, `NO MATCH`, or
`SIGNAL UNAVAILABLE`. The simulator never changes a real notification.

Templates are editable, unsaved starting points. Protection templates create visible,
high-priority Keep drafts for alarms, conversations, foreground services, or important alerts.
Review every template before saving it.

### Advanced conditions

The advanced editor supports:

- package, title, text, Android category, and channel;
- local ML category and seven-way semantic intent;
- ongoing, conversation, and foreground-service state;
- Android importance;
- local time and notification frequency;
- nested ALL, ANY, and NOT groups.

Sibling order controls evaluation order, while rule priority controls which matching rule wins.
See the [Rules Guide](RULES_GUIDE.md) for limits, frequency semantics, and detailed examples.

## Use Insights and Records

The **Insights** area has three tabs:

- **Overview:** today's counts, recent activity, daily cards, and local suggestions.
- **Analysis:** retained date-range trends and app, rule, channel, category, time, semantic, and
  local-learning breakdowns.
- **Records:** searchable local decision records, action/source filters, and details.

Records list metadata without decrypting notification content. Open **Details** for one record to
read eligible encrypted title/text only when optional detail storage had already been enabled for
that notification.

From a record you can:

- inspect the Active result, Monitor prediction, confidence, and bounded condition trace;
- correct a local category or semantic label;
- open Android's app or channel notification settings;
- open a rule draft for that source;
- open a high-priority Keep draft;
- exclude the record from statistics.

**Exclude from statistics** changes future local aggregates only. It cannot restore a notification
that was already cancelled or snoozed.

Rule suggestions are local drafts. They are never saved or enabled automatically.

## Manage profiles

A profile groups one or more existing rules:

1. Open **Profiles**.
2. Select **Add profile**.
3. Enter a unique name and select at least one rule.
4. Save the profile.

Toggling an inactive or partially active profile enables all of its member rules. Toggling a fully
active profile disables all of them. Deleting a profile keeps the underlying rules.

The same profile behavior is used by the app, launcher shortcuts, Quick Settings, Samsung
Routines, and authenticated external automation.

## Pause all filtering

Use **Settings → Filtering enabled** to pause or resume AlarmControl without changing individual
rule switches. While paused:

- notifications are not filtered;
- new activity history is not recorded;
- existing rules and profiles remain stored.

The Quick Settings tile and first-party shortcuts control this same master switch.

## Automation

### Samsung Modes and Routines

Samsung Routines can invoke AlarmControl's **Enable filtering**, **Pause filtering**, and published
profile App Shortcuts. This first-party path does not require **Allow external automation** or an
authentication token.

Menu names vary by One UI version. See the tested setup flow in the
[Automation Guide](automation.md).

### Quick Settings and launcher shortcuts

- Add the **AlarmControl filtering** tile from Android's Quick Settings edit screen.
- Long-press the AlarmControl launcher icon to access filtering and available profile shortcuts.

These first-party controls do not need the external automation opt-in.

### Tasker and MacroDroid

Tasker, MacroDroid, and compatible tools use a separate exported Intent contract:

1. Open **Settings → Automation**.
2. Enable **Allow external automation**.
3. Show and copy the current per-install token.
4. Configure an explicit AlarmControl package or receiver component.
5. Add the token as the required String extra.

Regenerating the token immediately invalidates old automations. Do not place it in logs,
screenshots, implicit broadcasts, or shared task files. Exact actions and extras are documented in
the [Automation Guide](automation.md).

## Notification detail history

By default, AlarmControl stores content-free decision metadata only.

To opt into encrypted details:

1. Open **Settings → Data & privacy**.
2. Enable **Store notification title and text**.
3. Open **Manage app exclusions** and turn storage off for any sensitive app.

This setting affects eligible future notifications only. Content is:

- length-bounded and encrypted with an Android Keystore AES-256-GCM key;
- unavailable to list and analytics queries;
- never stored for Android `SECRET` notifications or excluded apps;
- automatically removed after seven days;
- never included in portable backup.

When turning the feature off succeeds, AlarmControl deletes all stored ciphertext and the
non-exportable key before showing the setting as off. If deletion fails, the setting remains on and
the app asks you to retry. Details may also be unavailable because storage was off when the
notification arrived, the record expired, the app was excluded, Android marked it secret, or the
local key was removed.

## Retention and deletion

Under **Settings → Data & privacy**, configure activity-history and daily-summary retention
independently. Raw activity is also capped at the newest 10,000 events, and detailed condition
traces at the newest 1,000 events.

You can separately delete:

- encrypted notification details;
- activity history;
- local learning feedback;
- daily insights; or
- all app data.

Deletion is local and cannot be undone. **Reset all app data** also removes rules, profiles,
settings, suggestions, imported LLM files, encrypted content, and keys.

## Backup and restore

Open **Settings → Backup & restore**.

### Create a backup

1. Optionally enter a password of at least eight characters.
2. If encryption is enabled, optionally include package-level learning feedback.
3. Select **Back up**.
4. Choose a device-local destination in Android's file picker.

Without a password, the exported JSON is readable and must be handled as a plaintext file.
Encrypted exports use password-derived AES-256-GCM protection.

A backup may contain rules, profiles, selected settings, daily summaries, and optional encrypted
learning votes. It never contains notification title/body content, LLM reasoning, an imported LLM
file, the automation token, the password, or encryption keys.

### Restore a backup

1. Enter the password first if the file is encrypted.
2. Select **Restore** and choose the local backup.
3. Review the counts and available sections.
4. Choose **Merge** or **Replace selected**.
5. Select the sections to restore.
6. Confirm **Restore selected**.

AlarmControl validates the backup before changing local state. Keep an independent copy of an
important backup and test that it can be opened before uninstalling or resetting the app.

## On-device AI and optional LLM

### Bundled classifiers

The category and seven-intent semantic classifiers are bundled in the APK. They run locally and
need no setup. Trusted semantic output may satisfy a rule condition; low-confidence,
`AMBIGUOUS`, unavailable, or timed-out output leaves the notification unchanged.

Corrections made from Insights update local package-level feedback only. No notification corpus,
gradient, or model update is sent elsewhere.

### Optional generative LLM

**Settings → On-device semantic analysis** also exposes a separate MediaPipe `.task` import for
advanced compatibility work:

- the app never downloads a model;
- only import a file from a source you trust;
- the file is copied into private app storage and checked against its recorded SHA-256 after import;
- that integrity check detects later changes but does not certify the model publisher;
- the current build has no verified profile for automatic background LLM analysis;
- generative results are observation-only and cannot change an already handled notification.

No optional LLM is required for rules, bundled semantic classification, Insights, profiles,
automation, or backup.

## Troubleshooting

### Nothing is filtered or recorded

- Confirm **Settings → App status → Notification access: ready**.
- Confirm **Settings → Filtering enabled** is on.
- Confirm at least one rule is enabled.
- Remember that Monitor records only predictions; use Active for an actual Cancel or Snooze.
- Post a new representative notification after granting access.

### A rule does not match

- Check whether a higher-priority rule matched first.
- Confirm app package and channel selection.
- Use **Test this rule** and inspect the condition trace in Records.
- Ranking, conversation, foreground-service, channel, frequency, or ML signals may be unavailable.
  An unavailable signal is `UNKNOWN` and fails open.

### Daily Analysis is late or empty

Periodic aggregation may be delayed by Android battery policy. Live Records do not depend on that
daily schedule. Review the battery status under Settings if timely summaries matter.

### Notification details are missing

Detail storage may have been off when the notification arrived, the app may be excluded, Android
may have marked it `SECRET`, seven days may have elapsed, or the encryption key may have been
deleted. Metadata can remain after content expires.

### An update will not install

- Confirm that the new version is newer.
- Download the APK and checksum again from the same trusted release.
- A signature conflict usually means the installed build and new APK use different keys.
- Do not uninstall until you have created and checked a backup.

### External automation is rejected

- Enable **Allow external automation**.
- Use the latest token as a String extra.
- Explicitly target the AlarmControl package or receiver component.
- Check the recent local automation results in Settings.
- Wait if requests exceeded the local rate limit.

Samsung App Shortcuts, launcher shortcuts, and Quick Settings do not use this token.

### The imported LLM is unavailable

The file may be missing, changed, incompatible, too large for the device, or unsupported by the
current build. Remove or re-import it only from a trusted source. Core rules and bundled
classification continue to work without it.

### Data was deleted or the app was uninstalled

AlarmControl has no cloud account or OS cloud backup. Restore is possible only from a portable
backup you created beforehand.

## Android platform limits

- Filtering happens after Android posts a notification; a heads-up may appear briefly.
- Only Cancel and Snooze have notification-platform side effects.
- AlarmControl cannot intercept another app's real alarm unless it appears as a notification.
- It cannot silently mark another app's notification as read.
- It cannot directly change another app's notification channel; it can open Android's settings.
- Excluding a record from statistics cannot restore a dismissed notification.
- Low-confidence or missing local signals leave the notification alone.
- The app has no automatic updater or remote model downloader.

## Related documentation

- [Rules Guide](RULES_GUIDE.md)
- [Automation Guide](automation.md)
- [Privacy and Local Data](PRIVACY.md)
- [Build Guide](../BUILD.md)
- [Project README](../README.md)
