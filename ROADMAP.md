# Orbit roadmap

## Completed 0.5 personalization and context intelligence

### 0.5.5
- Score-based screen context classifier
- Category-specific contextual actions
- Classification diagnostics

### 0.5.6
- Camera, Gallery, File, Current screen, and Clipboard attachments
- Full PDF text extraction plus visual preview
- Persistent attachment metadata and regeneration context

### 0.5.7
- Local NotificationListenerService history
- Notification dashboard and per-app exclusions
- Natural notification queries with time-window parsing

### 0.5.8
- Intelligent Auto routing across Fast, Balanced, and Deep
- Last-Auto routing diagnostics
- AMOLED true-black theme option

### 0.5.9
- Memory 2.0
- Relevance-filtered memory context
- Suggested memories with explicit user approval
- Memory search
- Pin/importance
- Per-memory enable/disable
- Duplicate detection
- Optional used-memory indicator
- Expanded natural memory commands

### 0.5.11.x
- Voice Beta and overlay/chat polish
- Reply-context reliability work
- 0.6 readiness dashboard

## 0.6 power assistant

### 0.6.0.0
- Shared action engine
- Ordered chained-action execution
- Per-step action results
- Safer stop/continue behavior for multi-step plans
- Local chained command parsing
- Brightness control foundation
- Do Not Disturb control foundation


### 0.6.0.1
- More robust chained-command parsing for punctuation-free voice transcription
- Persistent action-result cards across overlay/full chat
- Inline flashlight reversal control and persisted off-state UI
- In-chat current-information web-search path

### 0.6.0.2
- Voice/typed hosted-search reliability parity
- Fast-model retry for transient hosted-search overloads
- Native persistent tappable source links for web-search answers

### 0.6.0.1-0.6.0.3
- Chained-command parsing refinements
- Persisted action result cards
- Flashlight undo state
- In-chat hosted web search with native source controls
- Voice/text source-link consistency work
- Overlay source-link browser handoff polish


### 0.6.0.4-0.6.0.9
- Voice web answers keep in-chat search while suppressing unreliable voice source buttons
- Brightness and DND special-access setup moved into Orbit's permission/capabilities flow
- Inline Grant access actions for missing special permissions
- Main Settings kept cleaner in preparation for a larger organization pass

### 0.6.2.0-0.6.2.2
- Saved Routines on top of the shared Action Engine
- Local routine manager and durable routine storage
- Ordered routine step editor and deterministic action catalog
- Natural-language routine triggering, including safe unique fuzzy matching
- Manual execution with per-step status feedback
- Smoother Orbit popup/dialog motion for routine management and Settings confirmations
- Reversible DND/flashlight result controls shared across routine execution surfaces


### 0.6.3.0
- Automatic time triggers for saved routines
- Multiple triggers per routine
- Once, daily, weekday, weekend, weekly, monthly, biweekly/fortnightly, and custom recurrence
- Custom every-N-days/weeks/months schedules
- Multi-weekday selection for weekly recurrence
- Exact-alarm readiness with inexact fallback
- Reboot/app-update/time/time-zone/exact-alarm-grant rescheduling plus launch-time recovery
- Background-safe automatic execution with notification continuation for foreground-only steps

### 0.6.3.1
- Routine automation readiness added to Permissions & capabilities
- Precise timing and trigger-alert access can be checked and managed centrally
- Capability state refreshes immediately after returning from Android Settings

### 0.6.3.2
- Expanded Set up / Manage controls across Android-backed permission rows in Permissions & capabilities
- Brightness and DND retain visible Manage controls after access is granted
- Time triggers labeled Available to distinguish feature availability from permission state
- Settings category renamed from Appearance & feedback to Look & Feel


### 0.6.3.3
- Normalize Permissions & capabilities row spacing after Manage-button expansion
- Remove phantom source controls from non-web assistant replies
- Add real local one-time reminders with durable scheduling and notifications
- Add pending-reminder management in Settings
- Reschedule reminders after reboot/app update/time changes and Orbit relaunch

### 0.6.3.4
- Add a dedicated Personalization & data Settings category
- Surface Reminders, Orbit Memory, app profiles, and Notification Intelligence together
- Keep Assistant setup focused on default-assistant and Side-button configuration
- Replace generic Settings subtitles with short category-specific labels


### 0.6.4.4
- Add versioned local Backup & Restore under Personalization & data
- Use Android's system file picker without broad storage permission
- Restore important local state safely and reconcile reminders plus time/location Routine triggers
- Exclude account credentials, Android permission state, and other security-sensitive/transient data

### 0.6.4.3
- Add reusable named Saved places for Routine location automation
- Manage Home/Work/Gym-style presets under Personalization & data
- Let location triggers and location IF conditions choose saved places without removing current-location/manual-coordinate options

### 0.6.4.2
- Clean up IF-condition popup menu positioning inside the Routine editor
- Keep selector popups on-screen for Time, Location, and Time + location flows
- Let Orbit's shared popup helper flip menus above the field when the dialog runs out of room below

### 0.6.4.1
- Add IF conditions directly inside saved Routine step chains
- Support time, location, and combined time + location checks
- Gate the next 1–5 steps and continue the Routine when a condition is false
- Keep conditional execution on the shared OrbitActionEngine path

## 0.6.5 Quick Access & Custom Commands

### 0.6.5.0
- Polish updater dialog readability using Orbit's shared dark-surface colors and selected typography
- Add determinate accent-colored APK download progress
- Add the Ask Orbit Quick Settings tile using the existing assistant-session entry path
- Add a configurable Routine Quick Settings tile using the existing Routine runner and Action Engine

### 0.6.5.1
- Make default-assistant setup reliable with supported Android/OEM fallbacks
- Move Weather into Personalization & data and correct its live accent styling
- Propagate Accent, app font, and AMOLED changes across active Settings surfaces
- Add an Update notifications preference and successful-install APK cache cleanup

### 0.6.5.2
- Simplify default-assistant setup to one reliable Android Settings control
- Move Quick Settings tile configuration from Routines into Assistant setup
- Restore the Routines page focus and spacing around saved automation

### Future 0.6.5.x
- Custom Commands
- Continue focused Quick Access refinements based on device testing

### Planned 0.6.x
- Home-screen widgets
- Screen-region selection
- Deeper Android automation
- Richer chained plans with saved presets and more advanced branching

## 1.0 direction
- Reliable daily-driver overlay and chat experience
- Strong privacy controls and predictable automation behavior
- Fast draft/research/help flows that feel polished, not experimental
- Routines and context tools mature enough to trust every day


### 0.6.1.3
- Settings hub and category-based navigation
- Focused Settings subpages instead of one giant scroll
- Light Settings haptics controlled by the existing haptic-feedback toggle
- Settings architecture ready for Routines management

### 0.6.1.x complete
- Settings reorganization and appearance/haptic polish completed
- Settings architecture established for Routines management


### 0.6.3.5
- Remove duplicate Memory / Apps / Notifications shortcuts from the Chats dashboard now that Personalization & data is established in Settings
- Tighten the fixed dashboard area and visually separate it from scrolling Recent Chats with a subtle live-accent divider


### 0.6.3.6
- Polish the Chats dashboard divider with a cleaner, wider accent section break
- Improve the spacing between the fixed chat controls and the scrolling Recent Chats list

### 0.6.3.7
- Simplify the Chats divider into a single optically centered accent fade after the layered 0.6.3.6 treatment proved visually uneven on-device
- Preserve the established Chats layout while removing the divider's slider-like appearance

### 0.6.3.8
- Align the Chats divider with the actual top edge of the scrolling conversation viewport
- Remove the fixed gap beneath the divider by transferring it above the line, preserving the established dashboard spacing while making the cutoff and divider the same boundary
- Simplify the divider to a subtle full-width live-accent rule without redesigning the Chats screen

### 0.6.3.9
- Preserve the corrected divider/chat-history boundary from v0.6.3.8 while giving the divider a slightly more polished Orbit treatment
- Use a restrained live-accent haze above a crisp edge-faded core so the decoration remains functional rather than floating away from the scroll cutoff
- Leave the rest of the Chats screen unchanged

### 0.6.3.10
- Add an app-wide font selector under **Settings → Look & Feel** while preserving Orbit Default as the existing typography
- Route font selection through shared `UiKit` typography handling so the companion app and Side-button overlay stay visually consistent
- Keep the feature lightweight by using Android system font families rather than adding bundled font assets

### 0.6.3.11
- Make Light, Condensed, and Casual typography choices reliably distinct across Samsung/OEM font-family fallbacks
- Refresh the parent Settings hub typography immediately after returning from Look & Feel

### 0.6.3.12
- Make Monospace use Android's canonical monospace Typeface so it cannot collapse to Orbit Default through an OEM family alias
- Strengthen the Light option so it reads visibly lighter than Orbit Default on-device
- Preview each font directly inside the App font popup while preserving the existing Look & Feel layout

### 0.6.3.13
- Resolve Light and Monospace from Android's registered system font files so Samsung/OEM aliases cannot collapse them back to Orbit Default
- Restore the cleaner v0.6.3.10 Casual family behavior after the generic cursive replacement proved too script-heavy
- Preserve per-option font previews and the existing Look & Feel layout

### 0.6.4.0
- Add opt-in **arrive / leave** triggers to saved Routines with configurable 100 m–5 km radii
- Reuse the shared Routine trigger storage, `RoutineTriggerExecution`, and `OrbitActionEngine` rather than introducing a parallel automation path
- Add current-location capture plus saved labels/coordinates for location areas
- Integrate precise/background-location and location-service readiness into Permissions & capabilities and Routine trigger management
- Re-arm enabled location triggers after reboot, Orbit updates, relevant location-service changes, and normal trigger reconciliation
- Preserve the existing background-safe execution and trigger-alert continuation rules for automatically fired routines
