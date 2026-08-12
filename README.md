# Orbit Assistant v0.6.4.3

See **CHANGELOG.md** for the complete version-by-version development history.



## 0.6.4.3: Saved places for Routine automation

- Added **Settings → Personalization & data → Saved places** for reusable named locations such as Home, Work, or Gym.
- A saved place can be created from the phone's current precise location or entered manually with latitude/longitude coordinates. Saved coordinates remain local to Orbit.
- **Location triggers** can now choose a saved place, which fills the trigger's name and coordinates while preserving its own arrive/leave event and radius.
- **Location and Time + location IF conditions** can use the same saved-place presets, while current-location capture and manual coordinates remain available.
- Saved places act as presets rather than live references. Once a trigger or IF condition is saved, it retains its own coordinates even if the reusable preset is later changed or deleted.

## 0.6.4.2: Routine IF popup positioning fix

- Fixed the IF-condition editor's popup menus so selectors such as **Condition**, **AM/PM**, **Radius**, and **Apply condition to** stay on-screen inside the dialog instead of falling off the bottom edge.
- Orbit's themed popup positioning now anchors against the visible display frame, which lets menus flip above the field when there is not enough room below.
- The patch is deliberately narrow and does not redesign the IF-condition workflow or the rest of the Routine editor.

## 0.6.4.1: Conditional Routine steps

- Added **If condition** as a first-class Routine step rather than creating a separate workflow engine.
- Conditions can test a **time window**, a **saved location/radius**, or **time + location together**.
- Each condition can gate the next **1–5 steps**. If the condition is false, Orbit reports those steps as skipped and continues with the rest of the Routine, allowing multiple independent IF blocks in one workflow.
- Time windows support overnight ranges; matching start/end times mean all day. Location conditions can capture the phone's current coordinates and reuse the existing Orbit location-permission infrastructure.
- Conditional execution remains inside the shared **`OrbitActionEngine`**. Automatic Routine runs also account for currently-false branches so a skipped foreground-only action does not unnecessarily force a continuation handoff.
- Location IF steps remain local and use the phone's system location when the Routine reaches the condition. Background automatic use still follows Android's existing precise/background-location requirements.

## 0.6.4.0: Location-triggered Routines

- Added **arrive** and **leave** triggers for saved Routines alongside the existing time-trigger system.
- Each location trigger stores a user label, latitude/longitude, radius, enter/exit event, enabled state, and last-run result. Radius choices range from **100 m to 5 km**.
- The location editor can capture the phone's current precise location or accept coordinates for another place. Orbit stores the saved trigger coordinates locally; it does not create a location-history log for this feature.
- Location triggers use Android's system proximity monitoring and are restored through Orbit's existing trigger scheduler after reboot, app update, relevant location-service changes, and normal Orbit reconciliation.
- Automatic location runs reuse the existing **`RoutineTriggerExecution` → `OrbitActionEngine`** path. Background-safe steps keep the same safety/preflight behavior as time triggers, while foreground or confirmation-dependent steps continue through Orbit's trigger-alert handoff.
- Added precise/background-location readiness and Set up / Manage controls to **Permissions & capabilities** and the Routine trigger manager. Enabled triggers can remain saved while access is incomplete and are re-armed after setup is completed.
- Duplicate, edit, enable/disable, conflict detection, and delete behavior now work for both time and location triggers without creating a second automation engine.





## 0.6.3.13: Deterministic Samsung font resolution

- Reworked **Light** and **Monospace** to select actual registered Android system font files instead of relying on OEM family aliases that resolved to Orbit Default on the Galaxy test device.
- Added subtle per-font spacing safeguards so Light and Monospace stay visibly distinct even if an OEM substitutes part of the typography stack.
- Restored **Casual** to the cleaner `casual` family behavior from v0.6.3.10.
- Font-picker rows still preview their respective resolved fonts, and the existing immediate Settings refresh behavior is preserved.
- No font files are bundled with Orbit; the resolver uses fonts already registered by Android on the device.

## 0.6.3.12: Font previews and stronger font resolution

- Fixed **Monospace** to use Android's canonical `Typeface.MONOSPACE` path instead of a family-name lookup, preventing OEM aliasing back to the default sans face.
- Made **Light** deliberately more distinct by using Android's light sans family with an explicit lightweight regular face while preserving stronger headings.
- The **App font** popup now renders every option label in the font it represents, so the choices can be compared before selecting them.
- The selected font field also previews its own typeface while keeping the existing Look & Feel layout unchanged.

## 0.6.3.11: Font reliability and Settings refresh

- Fixed **Light**, **Condensed**, and **Casual** so they remain visibly distinct on Samsung/OEM Android builds instead of silently falling back to Orbit Default.
- Light now uses explicit Android font weights, Condensed adds a narrow-scale fallback to the condensed system family, and Casual uses Android's generic cursive family.
- Fixed the parent Settings hub so it immediately adopts the newly selected font when returning from **Look & Feel**.

## 0.6.3.10: App font selector

- Added **Settings → Look & Feel → App font** with **Orbit Default**, **Times New Roman**, **Light**, **Condensed**, **Monospace**, and **Casual** choices.
- Orbit Default preserves the existing interface typography. The Times New Roman choice uses Android's built-in serif family for a similar classic serif appearance without bundling a font asset.
- Applied the selected font through Orbit's shared `UiKit` typography path so companion-app screens, controls, menus, and the Side-button overlay use the same saved choice.
- Font changes apply immediately in Settings and refresh the main Chats surface when returning from Look & Feel.

## 0.6.3.9: Chats divider visual polish

- Kept the v0.6.3.8 divider at the exact top boundary of the scrolling chat-history viewport.
- Upgraded the plain rule to a restrained live-accent divider with a soft edge-faded haze above a crisp edge-faded core line.
- Kept the glow above the functional cutoff, so chat cards still clip at the divider instead of underneath a floating decoration.
- Preserved the established Chats layout and spacing; no other dashboard controls or cards were redesigned.


## 0.6.3.8: Chats divider boundary fix

- Moved the Chats divider onto the actual top boundary of the scrolling conversation area instead of leaving a fixed gap beneath it.
- Replaced the center-heavy fade with a simple, subtle 1 dp live-accent rule so it reads as a section boundary rather than a glowing control.
- Moved the divider down to the actual chat-history viewport edge by transferring the old gap beneath it to the fixed-controls side, preserving the established overall spacing while making cards clip directly beneath the divider.
- No other Chats controls or card styling were changed.


## 0.6.3.7: Chats divider alignment cleanup

- Simplified the companion-app divider beneath Search into one symmetric accent fade so it reads as a clean section break instead of a layered glowing control.
- Removed the separate glow/core layers that could look visually offset on-device.
- Kept the existing Chats layout, search controls, history cards, spacing model, and live accent behavior otherwise unchanged.


## 0.6.3.6: Chats divider polish

- Reworked the companion-app divider between the fixed New Chat/Search controls and the scrolling chat history into a cleaner Orbit-styled section break.
- Replaced the extra-thin accent rule with a wider two-layer divider that uses a soft glow and brighter core band for a more intentional visual cutoff.
- Rebalanced the spacing above and below the divider so the transition into Recent Chats feels less cramped and more premium without reintroducing dashboard clutter.


## 0.6.3.5: Chats dashboard cleanup

- Removed the redundant **Memory / Apps / Notifications** shortcut row from the main Chats screen now that those managers have a dedicated **Settings → Personalization & data** home.
- Reclaimed the vertical space above Recent Chats so the companion app focuses on **New chat**, search, and conversation history.
- Added a thin accent-aware fade divider between the fixed chat controls and the scrolling conversation area for a cleaner visual cutoff without adding another heavy card or toolbar.
- The divider follows Orbit's live accent because the Chats surface already rebuilds when the accent changes.


## 0.6.3.4: Settings information architecture polish

- Added a dedicated **Personalization & data** Settings category for **Reminders, Orbit Memory, app profiles, and Notification Intelligence**.
- Assistant setup is focused again on default-assistant, Side-button, and core setup tasks.
- Added direct manager buttons for reminders, Memory, app profiles, and notification intelligence.
- Replaced generic category subtitles with short page-specific labels such as **Core setup**, **Models & access**, **Input & awareness**, **Local context**, **Chat behavior**, **Style & feedback**, **Developer tools**, and **Automation**.
- Updated nested headers for Capabilities, Reminders, and Apps for a more consistent Settings hierarchy.


## 0.6.3.3: Capabilities cleanup, reliable reminders, and source-link hygiene

- Normalized row heights in **Permissions & capabilities** so rows with **Manage** buttons and rows without buttons use consistent vertical rhythm.
- Updated the Capabilities header copy now that Power Assistant features are live rather than merely being prepared.
- Fixed phantom **Open source · Source** controls on non-web answers. Orbit no longer scavenges arbitrary URLs from generic response-completion metadata, and source URLs must now have a valid public-style host before a source control is shown.
- Added a real local **SET_REMINDER** action. When a reminder request has both a date and time, Orbit schedules a one-time local reminder notification instead of merely saying it will create one. If timing information is missing, Orbit should ask a short clarification first.
- Reminder delivery reuses Orbit's exact-alarm readiness when available and clearly falls back to approximate timing when Android's **Alarms & reminders** access is off.
- Added **Settings → Assistant setup → Manage Orbit reminders** so pending reminders can be reviewed and cancelled before they fire.
- Pending reminders are restored after reboot, app update, clock/time-zone changes, exact-alarm access changes, and the next Orbit launch.

## 0.6.3.2: Permission management and Settings naming

- Expanded **Permissions & capabilities** so Android-backed permission rows expose a compact **Set up / Manage** control where there is a meaningful system settings destination.
- Added management controls for **Microphone**, **Notification intelligence**, **Approximate location**, **Flashlight control**, and **Contact lookup**, while keeping the existing controls for **Brightness**, **Do Not Disturb**, **Precise timing**, and **Trigger alerts**.
- Brightness and Do Not Disturb now keep a visible **Manage** button after access is granted instead of hiding the control.
- Runtime permission rows switch from **Set up** to **Manage** once access is granted and refresh when returning to Orbit.
- Clarified that **Time triggers** are **Available**, not a separate Android permission.
- Renamed the Settings category **Appearance & feedback** to **Look & Feel** without changing any underlying preference keys or stored settings.
- Canonical navigation path: **Settings → Voice, context & permissions → Permissions & capabilities**.

## 0.6.3.1: Routine automation capabilities

- Added a dedicated **Routine automation** section to **Permissions & capabilities**.
- Shows **Time triggers**, **Precise timing**, and **Trigger alerts** readiness in one place.
- Precise timing reports **Ready** when Android's Alarms & reminders special access is granted and **Approximate** when Orbit is using the safe inexact fallback.
- Trigger alerts report whether Orbit can actually display routine continuation/failure notifications, including Android runtime and channel-level blocking.
- Added **Set up / Manage** controls so precise timing and trigger-alert access can be opened directly from the capabilities screen.
- The capability rows refresh when returning from Android Settings, so revoke/re-enable testing is reflected immediately.

## 0.6.3.0: Routine time triggers

Orbit Routines can now run from durable local time schedules while continuing to reuse the shared `OrbitActionEngine`.

### Included in this release
- Multiple automatic triggers can be attached to the same saved routine. New triggers start as one-time schedules so recurrence is always an explicit choice.
- Time-trigger presets for **Once, Daily, Weekdays, Weekends, Weekly, Monthly, and Every 2 weeks (biweekly / fortnightly)**.
- **Custom interval** schedules for every N days, N weeks, or N months.
- Weekly/custom-week schedules can select one or more weekdays.
- Each trigger has an independent enabled/disabled state, start/anchor date, selected time, next-run status, and last-run result. One-time schedules show as Finished after they fire. Duplicated triggers start disabled, and Orbit blocks identical enabled schedules for the same routine to avoid accidental double-runs.
- Trigger schedules persist locally and are restored after device reboot, Orbit updates, manual clock changes, time-zone changes, and the next Orbit launch after a force-stop.
- Orbit uses exact AlarmManager scheduling when Android grants **Alarms & reminders** access, with a safe inexact fallback when that special access is unavailable.
- The trigger manager exposes an **Allow precise timing** shortcut when needed, and Orbit reconciles saved schedules when Android grants that special access.
- Scheduled routines execute background-safe steps automatically. Orbit preflights known brightness/DND special access before changing the device.
- If a routine reaches a foreground/confirmation-dependent step such as opening an app, opening an Android panel, or taking durable flashlight control while Orbit is not visible, Orbit preserves action order, stops there, and can post a tap-to-continue notification rather than silently skipping or forcing the step. If trigger alerts are unavailable, Orbit avoids partially applying that mixed routine in the background.
- Trigger-alert readiness is shown in the trigger manager. Orbit detects both the Android 13+ runtime notification permission and notification/channel-level blocking, with a direct shortcut to the relevant alert settings when needed. Failure alerts can return directly to the failed step for review/retry.
- Routine cards now expose a compact **Automatic triggers** control and the overflow menu includes the same destination.
- Deleting a routine also cancels/removes all triggers that reference it.
- Time-trigger screens use Orbit styling, haptics, popup motion, and unsaved-change protection. Routine-runner retries now resume at the failed step instead of replaying already successful steps.

### Background-safe automatic steps
These can currently run without Orbit visible:
- Brightness
- Do Not Disturb
- Media volume

Foreground/UI-dependent actions and scheduled flashlight-on steps are intentionally handed back to the user through a continuation notification when Orbit cannot guarantee them safely from a short-lived background trigger.

### Safety and recurrence behavior
- Missed schedules do not replay in a burst. Orbit calculates the next future occurrence.
- One-time triggers automatically disable after firing.
- Repeating triggers establish their next occurrence before routine execution, so an action failure does not silently erase the future schedule.
- Custom monthly schedules preserve the anchor day where possible and use the month's final day when needed (for example, a schedule anchored to the 31st).

## Expected Windows APK
`Orbit-Assistant-v0.6.4.3-debug.apk`
