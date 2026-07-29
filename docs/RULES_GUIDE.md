# AlarmControl Rules Guide

**English** | [한국어](RULES_GUIDE.ko.md)

AlarmControl evaluates notifications after Android posts them to `NotificationListenerService`.
It can cancel or snooze a posted notification, but cannot pre-block another app's notification,
silently mark it as read, or directly change another app's notification channel.

## Guided creation

New rules open in a guided flow: choose a recently observed app or channel, choose Cancel/Snooze/
Keep, optionally add a time window or burst threshold, then name the rule. The source picker is
searchable, so users normally do not need to discover an Android package or channel id.

Cancel and Snooze drafts start in **Monitor** mode; Keep protection drafts start **Active** at a
high priority. The screen explains this before save. If an app has not produced a notification yet,
the user can enter a validated package name such as `com.example.app`. Switching to the advanced
builder is one-way for that draft because the advanced tree may not fit the simpler guided model.
Editing an existing complex rule always opens the lossless advanced tree.

## Active and monitor modes

- **Active** rules are sorted by priority. The first definite match supplies the real action.
- **Monitor** rules are sorted and evaluated independently. Their first match records only an
  expected action, such as “would cancel”; it never changes the notification or blocks an active
  rule.
- Disabled rules are evaluated in neither lane. A monitor rule can safely trial an action against
  real traffic before the user changes it to Active.
- `UNKNOWN` is not a match. Missing ML/LLM/ranking/frequency information therefore cannot trigger a
  destructive action.

## Conditions and compound logic

Rules may use package, title/text substring, Android category, channel id, ongoing state, ML label,
time window, semantic intent, conversation, foreground-service state, ranking importance, and
frequency conditions. `AllOf`, `AnyOf`, and `Not` can be nested up to 32 levels and 256 total nodes.
Evaluation short-circuits while preserving three-state logic.

The visual editor preserves the full tree and allows sibling nodes to move up or down. Node order
does not change Boolean meaning, but it controls evaluation order and can make common failures
short-circuit sooner. Invalid or empty nodes are highlighted and cannot be saved as a valid rule.

## Frequency rules

- Scope is either **package** or **package + channel**.
- The window is 1–1,440 minutes; the threshold is 2–1,000 posts.
- The post currently being evaluated is included in the count.
- On listener connection, AlarmControl reads at most 24 hours of content-free occurrence metadata
  once. Each accepted post is then committed to the bounded local occurrence store before its
  count is exposed; rule evaluation reads the in-memory index rather than querying Room.
- A listener disconnect, process restart, queue overflow, or persistence failure can leave an
  unobservable interval. Each frequency signal then remains `UNKNOWN` until its own complete window
  has been rebuilt: a one-minute rule can recover after about one minute of continuous observation,
  while the longest rule can take up to 24 hours. Other conditions continue to work and frequency
  conditions fail open during this warm-up.
- If initialization fails, or a channel-scoped rule sees a notification without a channel id, the
  result is `UNKNOWN`.

Rate rules count posted callbacks represented by the local event metadata. They do not inspect or
retain notification title/body content.

## Runtime safety

The listener tracks at most 64 notification jobs and evaluates at most four concurrently. A newer
post replaces pending work for the same notification. Rule or privacy-setting changes revoke stale
jobs before any cancel/snooze Binder call, and an unavailable startup cache fails open after two
seconds. These limits favor leaving a notification untouched over performing an action from stale
or overloaded state.

## Protection templates

Protection templates create visible, editable **Keep** drafts for alarms, conversations,
foreground services, or high-importance notifications. A draft receives a saturated priority 100
above the current maximum but is never saved automatically. Activity history can also create a
package or package+channel Keep draft. On Android/OEM paths where ranking information is absent,
ranking-dependent conditions evaluate to `UNKNOWN`.

## Channel controls

Activity and daily cards can open the exact Android notification-channel settings page. If the
channel id is missing or an OEM does not expose that Activity, AlarmControl opens the source app's
notification settings instead. Only the user can mute or block that channel there. This system
screen is the supported route for preventing future channel heads-up alerts.

## Semantic intent

The bundled local semantic encoder classifies notifications into seven values:

| Intent | Meaning |
|---|---|
| `MARKETING` | Promotional or sales intent |
| `TRANSACTIONAL` | Account, payment, order, or other user-initiated transaction |
| `SECURITY` | Authentication, fraud, or security alert |
| `DELIVERY` | Shipping or delivery status |
| `SOCIAL` | Person/social interaction |
| `OTHER` | Clear intent outside the classes above |
| `AMBIGUOUS` | Missing, contradictory, malformed, timed-out, or low-confidence result |

Only a trusted encoder result may satisfy an Active semantic condition. When semantic intent could
change the Active winner, the bounded real-time inference runs before that action. Monitor-only
semantic work runs after the Active decision and cannot change it. `AMBIGUOUS`, low-confidence,
timed-out, missing, or invalid output is unavailable and fails open.

The bundled seven-intent classifier is enabled by default. Turning it off in **Settings → On-device
semantic analysis** prevents both real-time and Monitor inference calls. Notifications whose action
depends on semantic or advertisement conditions remain unchanged; other notifications continue
with the remaining rule signals.

`IsAdvertisement(true)` remains compatible and means `MARKETING`; it is not a separate eighth
class. User corrections update a local seven-class shrinkage prior; no gradients or notification
text are exported.

AlarmControl v0.1.0 does not run an imported generative LLM because no compatibility profile is
enabled. If a future verified profile enables this optional path, its output remains
observation-only: it may enrich future local corrections, statistics, or suggestions, but it can
never trigger or retroactively change a notification action.

## Analysis, explanations, and suggestions

The rule analyzer reports only provable issues within the same execution mode: exact duplicates,
same-condition shadowing, mutually exclusive package/category/channel requirements, Boolean
contradictions, `X AND NOT X`, double negation, and one-child groups. Warnings do not block save and
the analyzer does not guess arbitrary string implication.

For the selected active and monitor winners together, history may store up to 128 trace nodes
containing only condition kind, `MATCH`/`NO_MATCH`/`UNKNOWN`, depth, position, and lane. Predicate
values, notification content, and LLM reasoning are excluded.

Local 7-day SQL aggregations can propose only:

- reviewing a channel with at least 10 events and at least 80% actually silenced; or
- opening a Monitor + Cancel marketing-rule draft after at least 3 marketing corrections at a 75%
  or higher share for one package.

Suggestions are never saved or enabled automatically, exclude structurally equivalent existing
rules, and remain hidden after the user dismisses their stable local key.
