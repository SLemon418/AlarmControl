# Automation: controlling AlarmControl from Samsung Galaxy Routines

**English** | [한국어](automation.ko.md)

AlarmControl exposes a single exported `BroadcastReceiver`
(`com.alarmcontrol.automation.ProfileToggleReceiver`) so a Routine can turn notification filtering
on or off. This is the documented, stable contract (CLAUDE.md §7) — the action/extra names won't
change casually.

Everything stays on-device: the receiver changes either the independent filtering master switch or
one matching named profile. It declares no `INTERNET` permission and makes no network calls (HARD
RULE §1/§3).

## The contract

| | Value |
|---|---|
| Enable filtering | action `com.alarmcontrol.automation.action.ENABLE_PROFILE` |
| Disable filtering | action `com.alarmcontrol.automation.action.DISABLE_PROFILE` |
| Required extra | `com.alarmcontrol.automation.extra.AUTH_TOKEN` (String) |
| Optional target | `com.alarmcontrol.automation.extra.PROFILE_ID` (String) |
| Target component | `com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver` |

The per-install `AUTH_TOKEN` is shown only after enabling **Settings → Allow external automation**.
Copy it exactly as a String extra. Regenerating it immediately invalidates every existing Routine or
Tasker task that still uses the old token; the token is never included in backup files or audit rows.

**`PROFILE_ID` behavior**
- **Omitted / blank → independent master switch** (the usual Routines setup). Pausing filtering
  preserves every individual rule's enabled state, so resuming restores the same rule selection.
- Present → the named profile whose **id** or **name** matches (name matching is case-insensitive).
  For compatibility with automation created before named profiles existed, an unmatched profile
  value falls back to a rule id or rule name.

Unknown actions, malformed targets, missing/wrong tokens, and unmatched ids never crash the caller.
Accepted external requests are limited to 12 per rolling minute to contain broadcast storms.

## Samsung Modes & Routines setup (via Good Lock → RoutinePlus)

Samsung's "Modes and Routines" has no built-in "send custom intent" action, so use the **RoutinePlus
(Routines+)** module from **Good Lock**:

1. Install **Good Lock** (Galaxy Store) → open **RoutinePlus**.
2. In AlarmControl, enable **Settings → Allow external automation** and copy the displayed token.
3. Create/edit a Routine. Under **Then**, add a RoutinePlus custom action → **Send broadcast**.
4. Set:
   - **Action**: `com.alarmcontrol.automation.action.DISABLE_PROFILE` (or `…ENABLE_PROFILE`)
   - **Package** (optional but recommended for reliable delivery): `com.alarmcontrol`
   - **Extra** (required): key `com.alarmcontrol.automation.extra.AUTH_TOKEN`, type String, value =
     the token copied from AlarmControl.
   - **Extra** (optional): key `com.alarmcontrol.automation.extra.PROFILE_ID`, type String, value =
     the profile name or id (e.g. `Work`). Leave it out to control the master switch.
5. Add the matching **If** trigger (e.g. "When connected to Work Wi-Fi") and a paired Routine to
   re-`ENABLE_PROFILE` when it ends.

Example: *At work → DISABLE_PROFILE (no extra)* pauses filtering without rewriting rule states;
*Leaving work → ENABLE_PROFILE* resumes it.

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

The receiver is exported **without** a custom permission, because RoutinePlus (like Tasker) runs as
its own app and cannot hold one — a signature permission would block the integration. It is instead
protected by three local controls: the off-by-default opt-in, a cryptographically random per-install
token compared in constant time, and a 12-request-per-minute process-local rate limit.

AlarmControl keeps at most 200 content-free audit outcomes (time, source, operation, target *type*,
result, and changed count). It never records the token, profile/rule name, or notification content.
The five most recent outcomes are visible in Settings for troubleshooting.
