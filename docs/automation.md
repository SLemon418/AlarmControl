# Automation: Samsung Routines, Tasker, and MacroDroid

**English** | [한국어](automation.ko.md)

AlarmControl provides two separate local automation paths:

1. **Samsung Modes and Routines (recommended):** select AlarmControl's dynamic App Shortcuts. This
   first-party path needs no automation token and works while external automation is disabled.
2. **Tasker, MacroDroid, and compatible tools:** send an explicit, authenticated broadcast to
   `com.alarmcontrol.automation.ProfileToggleReceiver`.

Both paths delegate to the same `ProfileController` and stay entirely on-device. AlarmControl
declares no `INTERNET` permission and makes no network calls.

## Samsung Modes and Routines setup

1. Open AlarmControl once so it publishes its dynamic shortcuts.
2. Open **Settings → Modes and Routines → Routines**, then create a routine.
3. Under **If**, choose a trigger. Use **Run manually** for a safe first test.
4. Under **Then**, choose **Applications → Open an app or do an app action**.
5. Expand **AlarmControl**, then choose:
   - **Enable filtering**
   - **Pause filtering**
   - a named-profile shortcut, when one is published
6. Save the routine. For a stateful trigger such as work hours, create a matching routine for the
   opposite action.

This path invokes AlarmControl's non-exported shortcut trampoline through Android's
`ShortcutManager`; it does **not** require **Allow external automation** or `AUTH_TOKEN`.

Verified on 2026-07-27 with a Galaxy Note20 5G (Android 13, One UI 5.1), Samsung Modes and Routines,
and Routine+ 1.0.60: a manual **Pause filtering** routine changed the master switch from on to off,
and **Enable filtering** changed it back on. Routine+ on this device did not expose a generic
**Send broadcast** action; it is not required for the App Shortcut route.

## Authenticated Intent contract

Use this route only for Tasker, MacroDroid, or another tool that can send explicit broadcasts.

| | Value |
|---|---|
| Enable filtering | action `com.alarmcontrol.automation.action.ENABLE_PROFILE` |
| Disable filtering | action `com.alarmcontrol.automation.action.DISABLE_PROFILE` |
| Required extra | `com.alarmcontrol.automation.extra.AUTH_TOKEN` (String) |
| Optional target | `com.alarmcontrol.automation.extra.PROFILE_ID` (String) |
| Required destination | package `com.alarmcontrol` **or** component `com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver` |

The per-install `AUTH_TOKEN` is shown only after enabling **Settings → Allow external automation**.
Copy it exactly as a String extra. Regenerating it immediately invalidates every existing Routine or
Tasker task that still uses the old token; the token is never included in backup files or audit rows.
The destination is mandatory: AlarmControl rejects implicit broadcasts so another app cannot
subscribe to the public action and observe the token.

**`PROFILE_ID` behavior**
- **Omitted / blank → independent master switch** (the usual Routines setup). Pausing filtering
  preserves every individual rule's enabled state, so resuming restores the same rule selection.
- Present → the named profile whose **id** or **name** matches (name matching is case-insensitive).
  For compatibility with automation created before named profiles existed, an unmatched profile
  value falls back to a rule id or rule name.

Unknown actions, malformed targets, missing/wrong tokens, and unmatched ids never crash the caller.
Accepted external requests are limited to 12 per rolling minute to contain broadcast storms.

Enable **AlarmControl → Settings → Allow external automation**, reveal the per-install token, and
configure the sender with the action, explicit package/component, and String extras shown above.
Never put the token in an implicit broadcast, logs, screenshots, or a shared automation export.

## Quick test with adb

```sh
# pause filtering while preserving individual rule states
adb shell am broadcast \
  -a com.alarmcontrol.automation.action.DISABLE_PROFILE \
  -n com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver \
  --es com.alarmcontrol.automation.extra.AUTH_TOKEN "<token-from-settings>"

# enable the named profile "Work"
adb shell am broadcast \
  -a com.alarmcontrol.automation.action.ENABLE_PROFILE \
  -n com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver \
  --es com.alarmcontrol.automation.extra.AUTH_TOKEN "<token-from-settings>" \
  --es com.alarmcontrol.automation.extra.PROFILE_ID "Work"
```

## Quick Settings tile

For manual toggling without a Routine, add the **AlarmControl filtering** tile to your Quick Settings
panel (edit the panel → drag the tile in). Tapping it flips the independent master switch and
preserves individual rule states. This is a first-party control, always available — it is not gated
by the automation opt-in below.

## Launcher shortcuts

Long-press the AlarmControl launcher icon for **Enable filtering**, **Pause filtering**, and as many
named-profile toggles as the launcher supports. These first-party shortcuts use the same controller
as the Quick Settings tile and external intents. They are not gated by the external-automation
opt-in.

## Security note

The receiver is exported **without** a custom permission because third-party automation tools run as
separate apps and cannot hold AlarmControl's signature permission. It is instead protected by four
local controls: an explicit package/component destination, the off-by-default opt-in, a
cryptographically random per-install token compared in constant time, and a 12-request-per-minute
process-local rate limit. Existing automations created without a Package must add
`com.alarmcontrol` after upgrading. Samsung's App Shortcut route does not use this exported receiver
or token.

AlarmControl keeps at most 200 content-free audit outcomes (time, source, operation, target *type*,
result, and changed count). It never records the token, profile/rule name, or notification content.
The five most recent outcomes are visible in Settings for troubleshooting.
