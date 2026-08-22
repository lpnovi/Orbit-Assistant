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

### 0.6.5.3
- Add initial Custom Commands that map exact local phrases and aliases to existing saved Routines
- Preserve Routine safety by reusing the established Routine and Action Engine execution path
- Include Custom Commands in versioned Backup & Restore with safe missing-Routine references
- Add a native What's New view backed by cached public stable GitHub Release notes

### 0.6.5.x follow-up direction at the time
- Expand Custom Commands only after real-device validation; arbitrary scripting remained intentionally unsupported
- Continue focused Quick Access refinements based on device testing

## 0.6.6 Release readiness

### 0.6.6.0
- Add built-in Routine Templates that create normal editable Routines through the existing Routine and Action Engine architecture
- Add capability-aware previews and optional Custom Command phrase suggestions without silently creating shortcuts
- Harden Orbit-owned dialog foregrounds, typography, surfaces, actions, AMOLED behavior, and animation through one shared styling path
- Make the Routine overflow-to-delete-dialog handoff deterministic instead of delay-based

### 0.6.6.1
- Add first-run onboarding with safe legacy-user migration and versioned completion state
- Reuse ChatGPT connection, default-assistant guidance, Quick Settings, progressive capabilities, Backup & Restore, normal appearance settings, and Routine Templates
- Keep setup resumable, skippable, and safely re-runnable from Assistant setup

### 0.6.6.2
- Complete release-candidate and fresh-install onboarding polish based on phone/tablet validation
- Share the full Accent and conversation-color catalogs between onboarding and Look & Feel
- Simplify setup navigation with live state-aware Skip for now / Continue labels and a hardened manual-exit confirmation
- Surface the existing private HTTPS-relay fallback alongside recommended ChatGPT setup without adding raw API-key storage
- Consolidate active-provider configuration under AI & account while preserving ChatGPT authentication independently of provider selection
- Separate Diagnostics into its own Advanced developer-tool card
- Keep the remaining minor Routine overflow-to-delete-confirmation entrance motion as a non-blocking cosmetic follow-up unless a deterministic fix is identified

### 0.6.6.3
- Keep Quick Access onboarding self-contained until the later starter-Routine step
- Expose existing runtime permissions, special access, context toggles, and automation readiness directly in grouped onboarding controls
- Share Android capability destination and location-automation progression logic with the full Capabilities dashboard
- Refresh direct permission/access status automatically after returning to the persisted onboarding step

## 0.6.7 Screen precision

### 0.6.7.0
- Add one shared native Screen Selection editor for the Side-button assistant and full chat
- Support Crop creation, move, edge/corner resize, freehand Mark up, Undo, Reset, and combined final rendering from the immutable original screenshot
- Preserve one-tap Use screen while adding Select/reselect and an in-editor full-screen fallback
- Isolate selected-region visual context from text outside the user's chosen area
- Keep temporary editor images app-private and reuse existing screenshot, attachment, history, provider, and request systems

## 0.6.8 Rich Chat + composer parity

### 0.6.8.0
- Shared native rich assistant responses across full chat and the Side-button overlay
- Markdown headings, emphasis, lists, quotes, code blocks, links, horizontal rules, and native scrollable tables
- Safe inline rendering for concrete public HTTPS image sources, without claiming general image search
- Matching Attach / text / Voice Beta / Send composers
- Full-chat pause-friendly Voice Beta and Side-button Camera, Gallery, File, Screen, and Clipboard access
- One shared explicit-attachment model and preserved local conversation/history request paths

### 0.6.8.1
- Add one preferred compatible Gallery app setting shared by full chat and the Side-button overlay
- Keep Android's System picker as the safe default and automatic fallback
- Move future product direction from Capabilities into a dedicated Roadmap under About & updates
- Add shared Small, Default, Large, and Extra large chat-content sizing with rich Markdown hierarchy preserved

## 0.6.9 Home-screen widgets

### 0.6.9.0
- Ship native Ask Orbit, Run Routine, and Quick Actions home-screen widgets
- Reuse full chat, the saved Routine runner, OrbitActionEngine, reminder creation, and device-action permission paths
- Add safe per-widget configuration, normal launcher resizing, Accent/AMOLED refresh, and deleted-Routine fallback
- Keep launcher surfaces limited to explicit safe labels without exposing private Orbit data

## 0.7 Orbit Extensions

### 0.7.0.0
- Ship the Orbit Extensions manager and versioned declarative `.orbitext` manifest format
- Add explicitly reviewed Open URL and bounded HTTPS GET/POST actions without arbitrary executable code
- Integrate extension-provided actions into saved Routines through stable extension/action IDs and the existing OrbitActionEngine
- Preserve safe unavailable states for disabled, removed, malformed, or missing extension actions
- Support compatible headless execution from widgets, Quick Settings, and automatic time/location triggers
- Include safe manifests and enabled state in Backup & Restore, with credentials and secrets excluded

### 0.7.1.0
- Add backward-compatible configurable Extensions v2 without executable plugin code
- Encrypt extension secrets with Android Keystore-backed storage and no plaintext fallback
- Add bounded Routine parameters, structural request templates, and safe manifest-declared authentication headers
- Ship generic-engine Discord Webhook and ntfy Notifications first-party extensions
- Preserve non-secret configuration in Backup & Restore while requiring secret setup again after restore
- Preserve Extensions screen position after Enable, Disable, Remove, and configuration refreshes

## 0.7.3 series — Natural Routine creation

### 0.7.3.0
- Create Routines from natural-language descriptions through **Create with Orbit**
- Convert requests into validated existing Action Engine steps, never new capabilities
- Review generated drafts in the existing Routine editor before anything is saved
- Surface unsupported and ambiguous requested actions instead of inventing them
- Keep automatic trigger and condition creation for later 0.7.3.x expansion

### 0.7.3.1
- Draft automatic time and location triggers from the same natural-language description
- Draft IF conditions using only Orbit's existing time and location condition model
- Never schedule anything automatically; propose triggers for review after the routine is saved
- Report vague timing, unknown places, and unsupported branching instead of guessing
- Keep concise, user-facing GitHub release notes generated from the changelog entry

### 0.7.3.2
- Reliability patch for natural-language Routine drafting
- Give planning its own request path instead of the chat response format
- Accept ordinary provider formatting variations before, never instead of, action validation
- Retry an unreadable planning response once, and never for an unsupported request
- Distinguish planner, mapping, and provider failures in the builder
- Record a local Routine planning trace in Diagnostics

### 0.7.3.3
- Add an optional **Start listening when overlay opens** setting for the Side-button assistant overlay
- Reuse the existing microphone path, listening UI, and permission handling rather than a second voice implementation
- Keep the setting independent from Hands-free voice follow-ups so each voice stage is controlled separately
- Start the first turn only for a genuinely fresh assistant invocation, never on an internal overlay resume

### 0.7.3.4
- Hand control straight to typing when the composer is tapped while Orbit is listening
- Disown the abandoned voice turn so a late recognition result cannot overwrite typing, submit, or reopen the microphone
- Keep the words already recognised in the composer for editing, without sending them
- Replace checkbox-style binary preferences with a shared animated `OrbitSwitch` that follows Accent and AMOLED
- Keep real checkboxes where a control selects or edits rather than switching a stored setting

### 0.7.3.5
- Keep the Side-button composer usable across consecutive typed messages within one invocation
- Give composer focus somewhere to rest so the editor genuinely re-attaches to the input method
- Stop response completion in full chat from rewriting window state that a live typing session depends on
- Understand qualitative relative brightness and media-volume requests locally, without asking for a percentage
- Preserve explicit percentages, saved Routines, and the established Hands-free voice follow-up behaviour

### 0.7.3.6
- Fix the Side-button composer crashing on tap, caused by a focus-gained callback that moved focus again
- Separate observing focus from acquiring it, and refresh an existing input connection through the input method rather than through focus
- Keep multi-turn overlay typing, the full-chat typing session, voice handover, and relative level commands exactly as released

### 0.7.3.7
- Share one small deterministic text normalizer across the local language routers
- Recognise notification shorthand and more conversational catch-up requests without changing notification storage
- Accept more natural read, save, update, and explicit delete wording for Orbit Memory
- Accept more everyday phrasing for existing timers, alarms, Do Not Disturb, and app launches
- Keep topic questions, ambiguous pronouns, and unrepresentable alarm dates out of the deterministic action path

### 0.7.3.8
- Track typing intent separately from editor focus, since a focused editor proved to be no evidence of a working keyboard
- Rebuild the composer's input connection in place after clearing it on send and when a turn ends, including device-action replies
- Bound that refresh per turn and never move focus, so the v0.7.3.5 focus recursion cannot return
- Treat an unexplained keyboard dismissal as the user ending the typing session

### 0.7.3.9
- Restore the pre-v0.7.3.8 window and insets behaviour, including the full-chat inset listener that
  v0.7.3.8 had displaced, and withdraw that release's input-connection revalidation approach
- Add an on-device composer trace and an input-connection-recording editor, retrievable from
  Diagnostics, which showed Android creating connections correctly while focus was lost 39-42 ms
  after each response and inset event
- Replace `fullScroll` focus-navigation auto-scrolling with focus-safe position scrolling in both
  surfaces, since scrolling to a new reply was handing focus to the response controls beside it
- Physically verify consecutive typed turns in the Side-button overlay and in full chat before
  releasing, through a signed candidate build

## 0.7.4 conversational Voice

### 0.7.4.0
- Tag each spoken reply so an interrupted utterance cannot open the microphone again, on either surface
- Keep Start listening when overlay opens and Hands-free voice follow-ups separate, with their defaults unchanged
- Understand exact relative level changes such as "lower brightness by 10%", while an explicit
  percentage still names a level
- Resolve a bare follow-up against the single device target Orbit last acted on, and restore a
  previous level only when Orbit actually observed it
- Give every OrbitSwitch one light confirmation tick from the shared component, so no screen has to
  add its own and no tap can produce two

### 0.7.4.1
- Colour links against the bubble surface they are actually drawn on, so an Accent link on an
  Accent bubble stays readable, while keeping the underline as a second cue
- Add **Show Stop button while replying**, on by default, turning the existing composer Send
  control into Stop for the duration of a reply rather than adding a second button
- Give `OrbitRequestManager` one authoritative `cancel`, used by both surfaces, which ends the
  durable request, cancels its unique WorkManager work, and owns partial persistence
- Add a `CANCELLED` terminal request state that is neither active nor a failure, so a stopped reply
  offers no Retry and no error, and is pruned like any other finished request
- Put every irreversible completion step behind one `completeIfNotCancelled` gate so an accepted
  Stop and a completion landing at the same instant are mutually exclusive, leaving no path to a
  late answer, a delayed device action, a completion notification, or a spoken reply
- Keep a stopped reply's partial text as an ordinary finished answer with Copy and Regenerate,
  written down exactly once so it survives reopening the conversation
- Leave the v0.7.3.9 focus architecture untouched: stopping is an ordinary button press that moves
  no focus, opens or closes no keyboard, and rebuilds no composer

### 0.7.4.2
- Add `OverlayLaunchTrace`, a bounded app-private record of Side-button overlay launches that
  survives process death, force stop and reboot, because the rare startup failure it exists for may
  end the process before anyone can reach Diagnostics
- Instrument the Orbit-owned startup path from `OrbitSessionService.onNewSession` through a real
  view-tree first-frame milestone, so a failed launch names the last stage that actually succeeded
- Distinguish an Orbit-initiated dismissal, an internal Screen Selection or full-chat transition, a
  hide Orbit never asked for, an incomplete launch, and an exception, keeping evidence separate from
  inference rather than declaring a crash
- Record lifecycle stages, booleans and counts only, through a closed API that cannot carry message,
  voice, screen, clipboard, memory or credential content
- Add **Copy overlay launch diagnostics** beside the existing report and typing traces, and a single
  last-launch line in the ordinary diagnostic report
- Make no speculative lifecycle change: the audited startup path already guards every nonessential
  step, so this release adds instrumentation and nothing else
- Correct the in-app Roadmap, which still offered natural-language Routine creation, automation
  history and richer quick access as upcoming after all three had shipped

## 0.7.5 Routine branching

### 0.7.5.0
- Give an IF condition an optional ELSE path: `elseSteps` alongside the existing `nextSteps`, so a
  routine stays one flat ordered list and a condition written before v0.7.5.0 describes exactly the
  execution it always did
- Add `RoutineBranch` as the one authority on branch geometry, replacing the three separate copies
  of the old "skip the gated steps" arithmetic that the engine and the background scans each kept
- Run exactly one path, then continue with the rest of the routine, through the existing
  `OrbitActionEngine` rather than any second automation path
- Report the untaken path as skipped rather than letting run history imply it ran
- Teach the background trigger scan the same geometry, so a foreground-only or confirmation step on
  the path that will not run can no longer defer or block an automatic run
- Show each step's path in the Routine editor, mark where the ELSE begins, and refuse to save a
  branch that does not fit its own steps or that nests inside another
- Draft a simple "otherwise" in Create with Orbit, and drop the branch with a warning rather than
  rebuilding it from a list a rejected step has shifted
- Keep the release bounded: one level, no nesting, no loops, no jumps, no scripting

### 0.7.5.1
- Author a branch as two visible paths — one IF block holding a THEN and an OTHERWISE section —
  instead of asking which of the "next 1-5 steps" each path covers
- Keep `nextSteps` and `elseSteps` exactly as the persisted and executed representation, and demote
  them to bookkeeping `RoutineBranch` maintains through every add, remove, duplicate and reorder
- Show `None`, not "Empty", for a path with no actions: the path is valid, it simply does nothing
- Add actions directly to a path, and give each path its own limit rather than one shared count
- Make reordering branch-aware — a step moves only within its own path, an ordinary step steps over
  a whole branch rather than into it, and moving an IF carries both of its paths with it
- Refuse the two shapes the stored model cannot express: an ELSE with no THEN, and emptying a THEN
- Clamp an overrunning legacy gate only when the user actually edits that branch, so opening a
  routine never rewrites it

### 0.7.5.2
- Fix Remove branch doing nothing on a branch that held actions: v0.7.5.1 built and styled the
  confirmation but never called `show()`, which `UiKit.styleOrbitDialog` does not do for the caller
- Confirm before removing a populated branch, and remove an empty one without asking
- Cover the whole path in tests that drive `RoutineEditorActivity.confirmRemoveBranch` itself, so
  the assertion that the dialog reaches the screen is the thing that would regress
- Audit every `styleOrbitDialog` call site in the app for the same missing `show()`; this was the
  only one

### 0.7.5.3
- Fix the intermittent Side-button overlay disappearance, reproduced on device with
  `OverlayLaunchTrace` active: the overlay drew a real frame and was hidden 184 ms later
- Root cause: `onCloseSystemDialogs()` on an already-hidden session still ran `dismissAnimated`,
  arming a 225 ms exit animation whose end action calls `hide()` on the session Android reuses for
  every invocation. The Side-button press itself delivers that callback to the hidden session, so
  the animation completed on top of the invocation the press had just opened
- A hidden session now arms nothing and only records the callback
- `OverlayDismissOwnership` holds the ownership token: rebuilding the sheet or returning it to its
  hidden state invalidates dismissal work armed by a finished invocation
- Cancel the outgoing sheet and scrim in `buildSheet` before `root.removeAllViews()`, while the old
  references still exist
- Fix `OverlayLaunchTrace` classification so ordering decides the cause: a dismissal recorded after
  the hide can no longer relabel a `SYSTEM_HIDE` as `EXPECTED_DISMISS`
- Keep the 450 ms Samsung fresh-show stabilization window exactly as it was

## 0.7.6 Smart Hands-free Voice

### 0.7.6.0
- Make the hands-free handover conversational: after an eligible spoken reply, reopen the
  microphone only when the reply is actually waiting for an answer
- `VoiceFollowUpPolicy` is the single shared decision, called by both `OrbitSession` and
  `VoiceInputController`, so the overlay and full chat cannot grow separate heuristics
- Deterministic and local: the decision reads the reply Orbit already produced. No second provider
  request, no extra billing, and no dependence on the network when a turn ends
- Only the last sentence counts, and quoted text, code, links, and blockquotes are removed first,
  so a rhetorical or quoted question does not hand the turn back
- Ownership is consulted before preferences and preferences before text, so an interrupted or
  superseded utterance is refused however clearly the reply asked something
- The delayed reopen re-asks the same policy when it actually runs, so typing, dismissal, or a
  newer reply in the gap cancels a handover that was already scheduled
- `Prefs.SMART_FOLLOW_UPS` defaults on, is subordinate to `AUTO_LISTEN`, and joins Backup &
  Restore; a backup written before this version restores onto the default
- `AUTO_LISTEN_ON_OPEN` stays completely independent, and Smart off restores the previous
  always-follow-up behaviour exactly

### 0.7.6.2
- Add a Jump to latest control in full chat when the newest messages leave the viewport
- Smooth-scroll to the newest message on tap through existing `FocusSafeScroll`, with the shared
  light haptic tick
- Hide the control once the conversation is at the bottom again, including a manual scroll back
- Keep automatic follow-the-bottom scrolling instant and leave the Side-button overlay unchanged

### 0.7.6.3
- Polish Copy and Regenerate under assistant replies into the shared message-action language
- Long-press a user message to copy it through Orbit's existing popup, in full chat and the overlay
- Keep regenerate on the latest assistant turn only, and copy the real message text rather than chrome

## Future direction

The in-app Roadmap in `RoadmapActivity` is future-only and is audited against this history whenever
it changes. Anything released belongs to the sections above and to What's New, never to the list
below.

### Next up
- Conditions beyond time and location, and more than one branch point in a single routine

### Planned
- Deeper Android actions
- Custom Commands that accept variation and detail beyond today's exact wording
- Additional AI providers

### Exploring
- Proactive screen intelligence
- Image retrieval integrations with safe sourcing and attribution
- Local and on-device intelligence

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
