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

### 0.7.6.4
- Move Copy and Regenerate off persistent under-message icons onto a long-press Orbit menu
- Add Edit & resend, which returns a user message to the composer without rewriting history
- Keep code-block Copy, draft-reply actions, and Jump to latest unchanged

### 0.7.6.5
- Replace the barely visible long-press bubble growth with `OrbitMessageHighlight`: an accent
  ripple travelling out from the press point, a faint wash carrying it through the message, and one
  edge pulse settling into the held selection
- Draw the whole effect in the message's foreground, so no bubble is resized, no text reflows, and
  no surrounding message moves
- Schedule frames from the drawable itself as `OrbitListeningHalo` does, building one radial
  gradient per press and moving it with a reused matrix rather than allocating per frame
- Drop a released selection's foreground exactly once on a fixed schedule, never from the
  drawable's own last frame or detach path, so no message can be left looking selected and
  `setForeground` is never re-entered
- Give the message-action menu its own surface — an accent-warmed sheet on Orbit's largest radius,
  each action's icon on an accent chip, larger touch rows — instead of a utility list
- Rebuild Edit & resend as a real composer state: a compact accent "Editing previous message" pill
  above the composer, the recalled message with the caret at its end, and the ordinary Send path
- Hold an unsent draft that Edit & resend displaced and put it back if the mode is cancelled,
  rather than destroying it silently
- Edit the existing `Editable` in place and move no window state, so the keyboard is not closed and
  reopened and typing needs no second tap
- Keep the Side-button overlay's Edit & resend deliberately simple, acknowledged on the state line
  it already uses for Copy

## 0.7.7 hybrid, provider-agnostic AI

### 0.7.7.0
- Introduce the internal provider layer: `AiProvider` contract, explicit `AiCapabilities`
  metadata, one normalized `AiRequest` shape, and the `AiProviders` registry as the only place
  that knows which backends exist
- Move the ChatGPT/Codex path and the API-relay path behind that contract unchanged —
  `ChatGptAuth`, `ChatGptClient`, streaming, tools, and background completion untouched
- Route every surface — chat, overlay, Routines planning, background worker — through the
  registry; the request pipeline no longer contains provider name checks
- Ship the AI Providers management screen: active-provider selection, per-provider status,
  capability strengths and honest gaps, and per-provider manage flows
- Ship Orbit Local's first release on Google's MediaPipe/LiteRT LLM runtime with one supported
  model, Qwen 2.5 1.5B Instruct (Apache-2.0, ~1.6 GB, downloaded on request, never bundled)
- Resumable, checksum-verified model downloading with pause, resume, cancel, delete, and
  storage-aware failure states that never leave a corrupted model marked ready
- Honest device capability assessment from facts Android exposes: architecture, OS version,
  memory, and storage
- Local streaming chat through the normal conversation UI, cancellable, offline once installed,
  with no silent fallback to a cloud provider
- Provider-aware AI strength: providers without real reasoning levels show the provider name
  instead of a strength menu that would do nothing
- OpenRouter configuration shell with Android Keystore-only API-key storage (no plaintext
  fallback, excluded from backups); its chat path deliberately does not run yet
- Temporarily withdraw the unreliable Edit & resend message action, keeping Copy and the
  composer-side machinery so the action can return once resending is dependable

### 0.7.7.1
- Fix the AI Providers entry clipping its status line: the row sizes to its two-line content
  instead of borrowing a fixed one-line selector height
- Make card actions follow provider state through one tested rule: READY earns "Use this
  provider", anything not ready offers only the step that would make it usable
- Rebuild the provider cards for scanning — name, status dot, one sentence, wrapping capability
  chips, one honest limitation line — with the single primary action full width and secondary
  actions compact
- Hide the ChatGPT Sign in button entirely while connected; Sign out becomes a restrained
  destructive action behind an Orbit-styled confirmation
- Mark the active provider with accent stroke, a faintly accent-blended surface, and an Active
  pill

### 0.7.7.2
- Rebuild model removal in the Extensions-manager idiom: a full-width restrained destructive
  "Delete local model" row, separated from the primary action, replacing the tacked-on corner
  button
- Precise removal language and a confirmation that states the size removed, the storage freed,
  the fallback to ChatGPT when Orbit Local was active, and that nothing else in Orbit is touched
- Deletion sweeps the model file, partial bytes, and stray temp siblings by model-file prefix,
  and never touches another component's files
- Key `LocalModelStore` by `ModelSpec` (id, pinned URL/size/SHA-256, per-model state keys) so a
  future device-action model installs beside the chat model without shared or clobbered state
- Re-adopt a complete pinned-size model file whose state mark was lost, instead of demanding a
  fresh multi-gigabyte download
- Gate Orbit Local's selectability on a ready model: it can neither be chosen nor silently stay
  active without one; requests fall back to ChatGPT
- Show the active state on the Orbit Local model card with the AI Providers screen's selected
  treatment, and add a percentage to download progress
- Replace "Orbit works the same whichever provider answers" with capability-honest messaging;
  Orbit Local now says its compact model gives simpler answers than cloud AI

### 0.7.7.3 — Kitchen utilities
Opens the cooking line. A deliberately small, deterministic foundation rather than a cooking mode:
the arithmetic and the quantity model that later cooking intelligence will reuse.

- Add `KitchenQuantity`: one exact rational quantity shared by every kitchen calculation, parsing
  decimals, `3/4`, `1 1/2`, and Unicode vulgar fractions, and reading back as a measure a cook
  owns a spoon for — so scaled amounts never surface as `1.1249999999999998`
- Add `KitchenUnit`: volume and mass as separate dimensions with the exact legal factors (a cup is
  473176473/2000000 ml, a pound is 45359237/100000 g), plus every ordinary spelling of each
- Add `KitchenMath`: pure conversion, temperature, and scaling, plus the presentation rules —
  metric reads as decimals, US measures as fractions, and an awkward spoon amount splits into the
  spoons that exist (`1/3 cup = 5 tbsp + 1 tsp`). Every answer says whether it is exact or rounded
- Add `KitchenMathRouter` to the provider-independent pipeline beside the memory, routine, device
  and notification routers, so kitchen arithmetic is answered locally and offline whichever
  provider is active, and `AiProvider` gains no knowledge of cooking
- Deliberate restraint: only complete conversion or scaling grammar is intercepted. Technique,
  substitutions, what to cook, and food safety all reach the provider untouched, and anything in
  Orbit's device-command vocabulary is never read as arithmetic
- Volume never silently becomes mass. With no ingredient named Orbit says what is missing in one
  sentence; with one named the question goes to the provider rather than to an invented density
- Improve cooking timer labels while leaving the timer itself alone: the named subject of the
  request becomes the Clock app's label ("Steak", "Potatoes"), and Android's
  `AlarmClock.ACTION_SET_TIMER` remains the one execution path
- Merge a bare trailing duration back into the clause before it when splitting chained commands,
  so "Timer for the potatoes, 20 minutes" is one request rather than two broken ones

### 0.7.7.4 — Stable / Beta update channels
Makes GitHub Releases a practical distribution system for testing as well as shipping, without a
second update server, a second app, a second package name, or a weaker build.

- Add `OrbitVersion` as the single authority on Orbit's version strings: `0.7.7.5` and
  `0.7.7.5-beta.1`, their tags, their display form ("0.7.7.5 Beta 1"), and their ordering. A
  malformed suffix (`-beta`, `-beta.0`, `-beta.zero`, `-test`) is not a Beta but an invalid version,
  so an unofficial tag can never masquerade as an Orbit prerelease
- Add the `update_channel` preference, defaulting to Stable for upgrades, fresh installs, and
  restores alike, and deliberately excluded from every Backup & Restore key set — Beta enrolment is
  a standing acceptance of risk belonging to the device it was made on
- Keep the Stable path exactly as it was: GitHub's own `/releases/latest`, which by definition
  already excludes drafts and prereleases
- Add Beta discovery as a bounded scan of a recent release page, ordered highest-version-first with
  a hard cap on manifest downloads, choosing the eligible candidate with the greatest `versionCode`
- Require the tag shape and GitHub's prerelease flag to agree. A Beta tag published as a normal
  release, or a Stable tag flagged as a prerelease, is refused in both channels
- Beta includes Stable: an enrolled tester is offered the finished release when it lands, then the
  next Beta, purely by `versionCode`
- Never downgrade. Switching Beta → Stable while running a newer Beta build offers nothing and says
  so, until a Stable release with a higher `versionCode` exists
- Make update state channel-aware: a channel change drops the cached candidate, the notified-version
  bookkeeping, and the check throttles, while deliberately preserving an install already in flight
- Add the Orbit-styled Update channel selector and the one-time Join Beta confirmation, plus a
  restrained BETA marker on Beta update cards and a Beta build pill in About & updates. The selected
  channel and the installed build type are shown as the separate facts they are
- Extend the one release workflow to both tag shapes: validated tag/source equality including the
  suffix, GitHub's prerelease flag derived from the validated tag rather than hand-set, and the
  readable release title. Every existing verification step is unchanged
- Preserve `orbit-update.json` schema 1 and the existing APK asset naming, so the updater already
  shipped in v0.7.7.3 can install v0.7.7.4 normally

### 0.7.7.5 — Modular Orbit Local
Validated on real hardware across three Betas (`beta.1`-`beta.3`) and released as `0.7.7.5` Stable.

- Add the optional `:local` Gradle module, package `com.orbit.assistant.local`, and move
  `com.google.mediapipe:tasks-genai` and the inference engine into it. The main Orbit APK no longer
  depends on the runtime at all, so an install without local AI never carries its native libraries
- `ReleaseModularityTest` fails the build if the runtime returns to the main module, if the
  component gains a launcher entry or an Activity, or if its service loses its signature permission
- One versioned AIDL contract in `ipc/`, compiled into both sides so the two ends cannot drift.
  `IOrbitLocalService` covers protocol version, status, model download lifecycle, model import,
  streaming generation, cancellation, and engine unload — and nothing else
- Trust is mutual and checked every time: the service re-verifies its caller's package and signing
  certificate on every transaction behind a signature-level permission, and Orbit verifies the
  component's package, Orbit's permanent certificate, and its version before binding. Signature is
  checked before version, so a hostile package can never pose as merely out of date
- `OrbitLocalProvider` stays in Orbit and keeps owning the prompt, memory, history, and
  conversation; it now reaches the runtime through `OrbitLocalClient` instead of MediaPipe directly
- A failed local request is a local error. A prompt aimed at on-device AI is never silently
  re-sent to a cloud provider, at any failure point including the component's process dying
- The component owns the model: new downloads land in its own storage, run by its own WorkManager
  worker, so a download no longer depends on Orbit's screen or process
- Existing models are migrated, never discarded: streamed over a file descriptor, verified against
  the pinned size and SHA-256 by the component, and Orbit's copy deleted only after READY. On a
  device without room for two copies Orbit offers an explicit replace-and-redownload instead
- Rebuild the Orbit Local screen around four cards — device, component, model, storage — plus
  separate "Delete local model" and "Remove Orbit Local" actions. Android owns every install and
  uninstall confirmation, and the real package state is reconciled on resume
- Extend the one release workflow to build, sign, verify, and publish both APKs. `orbit-update.json`
  stays schema 1 with an additive `component` block, so the updater shipped in v0.7.7.4 is
  unaffected

### 0.7.7.6 — Orbit Local lifecycle reliability
Validated on a Galaxy S25 Ultra across three Betas (`beta.1`-`beta.3`) and released as
`0.7.7.6` Stable. No new features; the modular architecture is unchanged.

- **Removal actually happens.** `Remove Orbit Local` did nothing at all: Orbit fired
  `ACTION_DELETE` without holding `REQUEST_DELETE_PACKAGES`, which Android has required of any app
  targeting API 28+ since Android 9. The platform refuses such a request by finishing its
  uninstaller without drawing anything, so `startActivity` returned normally and a silent catch
  discarded the rest. The permission is declared, and the request now goes through
  `PackageInstaller.uninstall` with a status receiver
- **Nothing is deleted before Android confirms it.** The old order deleted the model, the legacy
  copy, and the installer cache and *then* asked Android to uninstall — so a cancelled confirmation
  left a component installed with its 1.6 GB model already gone. Orbit now asks first, treats the
  package manager as the only proof, and cleans up only after a confirmed absence
- **Removal outcomes are distinguishable and never silent**: opened, cancelled, succeeded, refused,
  or could not be launched. `Uninstall component` is a separate, narrower action from
  `Remove Orbit Local`, and a standalone legacy model with no component has its own path
- **PAUSED means a pause somebody asked for.** It had been standing in for a WorkManager query that
  timed out, a dead network, an exhausted retry, and a truncated stream — which is how Orbit came to
  report a download as paused while its `.part` file was still growing. `ComponentModelStore`
  gains QUEUED, WAITING_FOR_NETWORK and INTERRUPTED, an explicit pause flag is the sole source of
  PAUSED, and `WorkState.UNKNOWN` preserves the last known state rather than inventing a decision
- **Pause → Resume is deterministic.** Unique work enqueued with `KEEP` let a cancellation that had
  not settled swallow the Resume that followed it. Starting REPLACEs, a redundant tap on a running
  download is a no-op, and a process-level lock stops a replacement worker overlapping the `.part`
  file its predecessor is still writing
- **Progress keeps moving.** `LocalAiActivity` decided whether to keep polling from the status it
  had not replaced yet, so polling stopped one tick into a fresh download. The next read is
  scheduled where the fresh status lands, one reader is outstanding at a time, and byte counts come
  only from the component's real on-disk state
- Protocol raised to 2, in lockstep across both APKs and the release manifest, because the status
  vocabulary grew and PAUSED's meaning narrowed
- Diagnostics gains an Orbit Local section: component state and version, uninstall stage and
  result, model state and bytes, WorkManager state, whether a pause was explicitly requested, and
  the last download failure category. No model contents, conversations, or paths

### 0.7.7.7 — Real Calendar control and one-answer requests
Validated on a Galaxy S25 Ultra across four Betas (`beta.1`-`beta.4`) and released as `0.7.7.7`
Stable. The final acceptance run reproduced the background-worker overlap on the device and
confirmed the replacement runs were superseded before either could answer twice.

Orbit exposed `CREATE_EVENT`, which only ever opened Android's event composer. Asked on a real
device to "put the Michigan football schedule on my calendar", Orbit produced confident text saying
it was adding the games and persisted nothing at all. The same device reports also exposed two
request-duplication failures, so this release closes both.

- **A real Calendar writer.** `ADD_CALENDAR_EVENTS` is a second, deliberately distinct action:
  `CREATE_EVENT` still opens Android's composer for a single event the user wants to edit, while
  `ADD_CALENDAR_EVENTS` has Orbit persist a whole batch through `CalendarContract` itself. No Google
  Calendar OAuth; it works with whatever writable calendars the device already exposes, including
  Google- and Samsung-backed ones
- **The implementation is a component, not a switch case.** `OrbitCalendarStore` owns writable
  calendar discovery, target resolution, and the remembered choice; `CalendarActionExecutor` owns
  validation, date/time/timezone conversion, duplicate detection, insertion, read-back verification,
  and result wording. `DeviceActionExecutor` stays a routing layer, so a future local action model
  reuses the same writer rather than a second Calendar path
- **Only execution may claim success.** A model may say what it found and what it can do; the counts
  in "Added 12 events to Personal." come from the provider after each inserted row has been read
  back. The ChatGPT, relay, and server prompts all state this explicitly
- **Nothing is invented.** An event whose start time is genuinely unannounced becomes an all-day
  entry marked Time TBA on the correct date, written with `CalendarContract`'s documented UTC
  all-day semantics so timezone conversion cannot move it onto an adjacent day. Impossible dates and
  unknown timezone ids are rejected rather than normalized into something plausible and wrong
- **Permission at the moment of use.** `READ_CALENDAR`/`WRITE_CALENDAR` are declared but never
  requested during onboarding or installation. `CalendarPermissionActivity` is an invisible bridge
  so the Side-button overlay, which cannot request runtime permissions itself, reaches the same
  Android prompt as full chat. A denial produces zero writes, enforced in the executor rather than
  in either surface
- **One confirmation for a batch**, naming its destination — `Add 12 events to Personal?` — with a
  date range, the first few events, and the count of Time TBA entries. The destination is a
  selectable Calendar field inside the confirmation rather than a button competing with Cancel and
  Add; the choice is remembered locally as an id, never as calendar contents
- **Permission before description, not after approval** (0.7.7.7-beta.2, from real-device Beta
  testing). Beta 1 built the first-ever confirmation from a provider that had not been unlocked yet,
  so it offered no destination, no chooser, and then failed at the executor; repeating the request
  worked only because permission was by then held. `CalendarTargetResolver` fixes the ordering for
  both surfaces: resolve permission, re-read the provider, resolve the target, then draw. Add is
  inert while the destination is genuinely ambiguous, and a card stranded without one recovers
  through `Choose calendar` rather than re-asking the model. Previews follow the device's own
  12/24-hour setting
- **Idempotent enough to retry.** Before inserting, Orbit looks for an equivalent event on the
  target calendar by normalized title, all-day state, and local day, and skips confirmed matches
  without ever modifying or deleting an existing user event
- **One send, one request.** The shared `SubmissionGate` replaces the full-chat send path's complete
  absence of a gate: an in-flight claim, a durable identity check against active requests, and a
  short timing window as secondary defense. The keyboard's Send key now follows the same Stop state
  as the visible control
- **One request, one answer.** `PendingRequestStore.claimCompletion` is a durable, synchronously
  written claim taken before an answer is appended, so a worker WorkManager re-runs after a process
  death abandons the turn instead of asking the model again and appending a second reply to one
  visible user message
- **One request, one model call.** The guard above stays permanent, but the overlap that kept
  triggering it is closed at the source: stopping a `Worker` does not end its thread, so a stopped
  execution and its replacement could both reach the provider. An in-process claim per request id
  makes the newcomer stand down, a stopped execution no longer starts a model call it cannot use,
  and a stopped execution's error is no longer written as a visible failure for a request that has
  not failed. Diagnostics reports WorkManager's count as the start count it actually is
- **Provider-aware onboarding.** The Connect Orbit step now presents ChatGPT as recommended, Orbit
  Local as optional with its real component/model state, and collapses OpenRouter and the renamed
  Private API relay behind More provider options. Availability and status come from `AiProviders`
  rather than a second set of hardcoded strings, and setup stays seven steps

### 0.7.7.8 — Thinking updates, stopped turns, and attachment continuity
Validated on a Galaxy S25 Ultra across four Betas (`beta.1`-`beta.4`) and released as `0.7.7.8`
Stable. The line grew as each Beta was tested: it began as Thinking updates alone, gained a real
stopped-turn state in Beta 2, moved that state onto the turn it belonged to in Beta 3, and closed
in Beta 4 with the follow-up attachment defect and the finished stopped mark. This release
deliberately took the
Beta slot ahead of Orbit Local device actions, which are unchanged and simply move down one place.
A long Deep request told the user only that something was happening; the orbital indicator says a
request is running and nothing more, and on a request that takes twenty seconds that is not enough.

**Beta 1 answered the open question on real hardware.** The design had to allow for the
ChatGPT-account Codex backend refusing `reasoning.summary`, because that is a server-side decision
Orbit cannot know in advance. A Galaxy S25 Ultra Deep request reported `Provider carries reasoning
summaries: yes`, `Backend has produced a summary: yes`, `Last thinking source: provider-summary`,
45 updates received, and `Last status handed over to an answer: yes`. So the safe summary path is
genuinely live, not a fallback dressed up as one, and the Beta 1 transport, event parsing,
coalescing, and request-safety architecture are frozen from that point.

- **An optional status line, off by default.** Settings > AI & account > Intelligence gains a
  **Thinking updates** switch. With it off, the thinking row is exactly what `0.7.7.7` shipped: the
  orbital indicator alone, no summary requested from any provider, and no extra output paid for
- **Two sources, kept apart on purpose.** `ThinkingUpdate` distinguishes a provider-published
  reasoning *summary* from Orbit describing its own execution. Only the provider factory can mint
  the former, so no Orbit code path can present its own wording as the model's
- **Never raw chain-of-thought.** Orbit reads only the `response.reasoning_summary_*` events, which
  are the summary a backend produces for display in answer to `reasoning.summary`. Reasoning text
  itself and encrypted reasoning content are not read, here or anywhere else, and the event match
  is anchored so a future event name cannot widen it by accident
- **Honest Orbit-progress fallback.** Every stage has a real producer: screen context genuinely
  attached, the resolved model a request was actually sent to, the hosted search tool reporting
  that it began and finished, and local generation running on the phone. Where Orbit knows nothing
  more specific the honest answer is "Thinking…" and the animation carries the rest
- **Provider-agnostic, and honest about the differences.** `AiCapabilities.reasoningSummaries` says
  which providers can carry a summary at all, and `ReasoningSummarySupport` observes at runtime
  whether the ChatGPT backend really honours the request, falling back for free on a refusal rather
  than surfacing an error for an optional status line
- **Coalesced, not streamed character by character.** `ThinkingUpdateStream` spaces updates, holds
  back fragments, and freezes a summary paragraph's opening line, so the status reads as a few
  coherent phrases rather than a caption rewriting itself under the reader
- **Stable geometry in the overlay.** Fixed width and two reserved lines of height, both settled
  before any text arrives, so no update can resize the sheet or move the composer beneath it
- **Ephemeral by construction.** Nothing is appended to `ConversationStore`, sent back as an
  assistant turn, spoken, notified, backed up, or written to Diagnostics or `RequestTrace`.
  Diagnostics records counts, a source token, and timestamps, and has no field for the text
- **Observational only.** The progress channel cannot enqueue, retry, complete, or persist
  anything. `SubmissionGate`, request identity, the WorkManager execution claim, superseded-worker
  protection, `PendingRequestStore.claimCompletion`, and `OrbitRequestManager.completeIfNotCancelled`
  are untouched, and stale updates are refused by request id rather than by comparing text
- **Auto is untouched.** Fast still means Luna and low, Balanced Terra and medium, Deep Sol and
  high. Asking for a summary never raises reasoning effort and never delays an answer

Beta 2 changed nothing about any of the above. It is three focused refinements on top of it:

- **On by default.** Beta 1 shipped the switch off because nothing was proven yet; Beta 2 makes it
  part of Orbit's normal experience. The change is one default value and no migration code, because
  the distinction that matters is between an absent preference and a stored `false`: an upgrade
  from `0.7.7.7` has never stored one and gets the new default, while anyone who turned it off
  keeps it off. The switch stays, so it can always be turned back off
- **A stopped turn now looks stopped.** Stopping mid-thought used to leave the question with
  nothing under it, which reads as a silent failure rather than as something the user did. Orbit
  now leaves an interrupted orbital mark: the same core and the same tilted ellipse as the thinking
  indicator, drawn as an arc with a clean cut and one particle at rest on the break. No error
  colour, no cross, no warning glyph, because stopping is not a failure. The mark itself was right
  and is unchanged; where Beta 2 got it wrong was in deciding where it belonged, which Beta 3
  replaces below. A partial answer is kept exactly as before, with the mark beneath it
- **Diagnostics gained progressive disclosure.** The report had grown to a wall of text that had to
  be read in full to find anything. The screen now opens on a compact Overview with every detailed
  block behind a collapsed section, and copying splits into a short support-shaped **Copy summary**
  and the unchanged verbose **Copy full diagnostics**. Nothing was deleted; the request-flow
  counters that diagnosed the duplication failures are intact. One privacy finding came out of the
  audit: the raw Routine planner response is model output derived from a description the user
  typed, so it could name people and places they mentioned, and it was being appended to the copied
  report. It is now on the device only, behind its own disclosure and its own labelled copy control

Beta 3 is narrower still. Beta 2's device validation was strong — Thinking updates, Auto routing,
the request lifecycle and the new Diagnostics layout all passed and are frozen — and it left one
reproducible defect and two points of polish.

- **A stopped turn keeps its own mark.** Beta 2 derived the mark from
  `PendingRequestStore.stoppedTailForConversation`: the conversation's newest request, if cancelled.
  That answers "did this conversation end on a stop", which is not the question. It could describe
  only one stopped turn, it stopped describing even that one as soon as the next turn was queued,
  and because the mark was drawn as a footer after the message list it landed below whatever
  happened to be last. On a real device that read as `prompt 1, prompt 2, mark, thinking` — the
  mark under a question it had nothing to do with. The anchor is now a property of the turn rather
  than of the conversation: `AssistantClient.History.stoppedRequestId` records which request was
  stopped at that point, written once by `OrbitRequestManager.cancel` after any partial answer has
  been persisted, and both surfaces draw the mark inside the same pass that draws the turn. Later
  turns are appended after it and cannot move it; several stopped turns each keep their own mark;
  identical prompts are distinguished by request identity rather than by their text; and a stale
  in-memory copy of a conversation cannot save the anchor away again. Still no fake assistant
  message anywhere, and the mark remains representation only — every cancellation guard is
  untouched and the mark is never lifecycle authority
- **The mark is easier to recognise.** At 22dp it was being read on a Galaxy S25 Ultra as a
  rendering artifact. It is now 28dp with slightly firmer strokes and a very faint halo behind it,
  which is enough to look deliberate while staying wordless, muted, un-bubbled and nothing like an
  error
- **Diagnostics separates a current problem from one Orbit already fixed.** Overview reported
  `attachment_bridge_stale_recovered` — a guard doing its job — as "Last error", so a healthy
  install looked broken indefinitely. Conditions whose names end in `_recovered` are now filed as
  resolved history rather than as failures, by a rule about the vocabulary rather than a list of
  known strings. Overview shows a dated current failure or `Status: OK`; the recovered condition
  keeps its place, its timestamp and its provenance in Advanced and in the full report

Beta 4 closed the last two items before Stable: one real defect and the finish on the stopped mark.

- **An attachment belongs to the turn it was shared with.** A screen shared in the overlay appeared
  to be attached all over again on the next question, and the cause was the opposite of the
  symptom. The overlay left `screenAttached` armed after a send, so the follow-up genuinely did
  pick the screen up a second time; meanwhile `ChatGptClient.requestBody` rebuilt history as role
  and text only, so the picture the conversation was actually about was not reaching the model at
  all. Both halves are fixed together. A hand-attached screen, selection, image, or file is now
  consumed by the message that carried it, exactly like sending a photo, while earlier
  attachment-bearing turns are reconstructed onto *their own* position in the request by
  `HistoryAttachments`, image bytes included. Orbit's transport is stateless, so those bytes do
  travel again — but as part of the question the user asked back then, never as a new attachment on
  the question they are asking now
- **Bounded, and it degrades honestly.** Only turns already inside the ten-turn history window are
  eligible, the newest few carry image bytes and older ones fall back to their extracted text, one
  stored file is never sent twice in a request, and no file is ever copied or re-saved. A stored
  image that has since been deleted costs the turn its picture and nothing else: the text survives
  and Orbit invents no replacement. Reconstructed attachments keep the identical
  `untrusted="true"` framing the current turn uses, and no local path ever reaches the model
- **Automatic screen context is deliberately untouched.** Attach screen by default and an app
  profile set to Attach are standing instructions to keep supplying the live screen, not one-shot
  shares, so they survive every send. The overlay now tracks which of the two armed the screen
  rather than treating one flag as both things
- **The stopped mark reached its intended form.** Beta 3's 28dp square still read as a small icon
  parked at the left of the response lane. It is now a wide, shallow interrupted orbit centred
  across that lane: two arcs broken symmetrically above and below centre, a core at rest in the
  break, and a particle stopped at each extreme. The settle went from 260ms to 620ms and became
  four overlapping phases — the particles decelerating, the orbit widening out of the live
  indicator's compact geometry, the breaks opening, the core landing — so a stop looks arrived at
  rather than swapped in. Beta 3's turn anchoring is unchanged, historical marks stay static, and
  the whole thing is skipped for the finished state when system animations are off

### 0.7.7.9 — Full-app gesture navigation
Validated on a Galaxy S25 Ultra across three Betas (`beta.1`-`beta.3`) and released as `0.7.7.9`
Stable. The line is recorded in full under **Near-term order** below, including the Beta 1 device
failure that shaped it; in short:

- **Beta 1** introduced the chat-card gestures — swipe left to delete with Undo, swipe right to
  pin, and a Pinned section — and attempted the conversation's back gesture as a subtraction, letting
  Android's own cross-activity predictive back own it. The chat gestures passed on the device. The
  back gesture did not: every off-device check passed and the conversation did not move at all
- **Beta 2** replaced that with Orbit's own progress-driven implementation, which moves the page as
  a function of the gesture's reported progress and reveals the real screen underneath rather than a
  picture of it. It also fixed the Undo bar's geometry so the offer floats over Chats instead of
  resizing the list, gave every Diagnostics section its own Copy control, and stopped Diagnostics
  describing a transition Orbit had only requested as one it had observed
- **Beta 3** generalized that working interaction into one shared engine and spread it across the
  app, with every Activity classified as predictive, guarded, local or root so that editors keep
  their unsaved-work protection and result-returning or bridge screens keep their own Back

### 0.7.8.0 — Orbit Local device actions, multi-attachments, and protected dialing
Validated on a Galaxy S25 Ultra across four Betas (`beta.1`-`beta.4`) and released as `0.7.8.0`
Stable. The line is recorded in full under **Near-term order** below; in short:

- **Beta 1** opened item 1 of the numbered list without finishing it: a second, much smaller model
  beside the chat model, so Orbit Local can read a phone command in the user's own words and
  *request* an existing Orbit action through the shared executor. It also added deterministic
  everyday utilities — a calculator, conversions beyond the kitchen, device-status reads, and media
  and ringer control — and the hidden Launch Sequence
- **Beta 2** corrected what the device found: status questions phrased as "what's" reaching the
  command parser, drafted replies routing to SMS from a Gmail screen, clarifications being offered
  send controls they should never have had, and the Launch Sequence not actually dragging
- **Beta 3** rebuilt attachments as one canonical ordered set rather than a slot, so several photos
  go in one message through whichever Gallery the user has; added `ShareToOrbitActivity` as a narrow
  exported share target that never sends on its own; and added the protected emergency and crisis
  dialing boundary after a model returned an unprompted `DIAL` for 911 on a real phone
- **Beta 4** fixed the four presentation faults only a phone could show: long photo filenames in the
  attachment strip, a strip that jumped to the far right after every redraw, a protected-dial
  confirmation that read as a system warning, and a reply claiming the dialer was open before the
  user had confirmed it

### 0.7.8.1 — Attachment viewer, Ask Orbit, and progressive responses
Validated on a Galaxy S25 Ultra across three Betas (`beta.1`-`beta.3`) and released as `0.7.8.1`
Stable. The line is recorded in full under **Near-term order** below; in short:

- **Beta 1** added the full-screen attachment viewer — zoom, pan, and swiping between the images on
  one message — and the `PROCESS_TEXT` doorway that turns text selected in almost any app into a new
  Orbit conversation without ever editing the app it came from
- **Beta 2** rebuilt how an answer is presented while it is being written: one parser and one block
  builder shared by streaming and completed replies, so headings, lists, quotes, tables and code
  format as they arrive instead of snapping into shape at the end, with gentler motion around it
- **Beta 3** corrected the four presentation defects only a screen could show: combined emphasis
  leaving stray asterisks, table rows breaking into cells of different heights, task syntax arriving
  as literal brackets, and a fully opaque jump-to-latest covering the message behind it
- **Stable** carries Beta 3's behaviour unchanged, plus one deliberate addition: the hidden Lelo
  mode that has existed since v0.4.6 now also changes what the companion app's header says. It is a
  title and a line of muted text behind the existing preference, and with the mode off Chats is
  built exactly as before

### 0.7.8.2-beta.1 — Orbit Deck
Introduced one new first-party surface and carried forward a small Lelo-mode correction. Chats
remains Orbit's home: Deck is one tap from it, is not a tab, and does not open at launch.

- **An optional Deck reached from the Chats header.** One restrained control, on by default and
  removable from Settings without a placeholder left behind. Deck also stays reachable from its own
  Settings section, so the shortcut can never be the only way in
- **A modular tile system rather than a fixed screen.** A registry of tile definitions, a separate
  store of the user's placed instances, a versioned layout schema, one execution boundary, and one
  resolver that decides whether a configured tile can still do its job. A future capability becomes
  a Deck tile by registering a definition, not by editing the screen
- **Tiles for what Orbit already does.** Orbit destinations, a saved Routine run through the existing
  Routine runner, an installed app launched through the launcher's own intent, direct Flashlight and
  Play/Pause actions, and prompt shortcuts that open a new chat with the user's text ready to edit
  and deliberately unsent
- **Standard and wide sizes, drag reordering, and live persistence.** Every edit is one atomic
  commit, so Deck holds no unsaved work and Back stays ordinary predictive navigation. Reordering is
  also available from a menu, so the drag gesture is never the only route
- **A small Suggested section, computed on the phone.** At most two shortcuts on a phone, drawn from
  a live media session, recent non-Orbit context, or a recently run Routine, capped and suppressed
  when the equivalent tile is already placed. It asks no provider anything, and it never rearranges
  My Deck
- **Responsive across phone and tablet** from one grid, and unchanged in behaviour under AMOLED,
  every accent, large text, and reduced motion
- **Lelo overlay title parity.** The Side-button overlay now takes its title from the same shared
  helper the full app uses, so the mode renames both surfaces together

### 0.7.8.2-beta.2 — Documents + Deck Refinement
Prepared as the focused device-validation Beta after real Galaxy S25 Ultra testing of Beta 1.

- **Real Deck drag reordering.** The original long press flows directly into pickup, a drag keeps a
  provisional in-memory order, and neighboring standard or wide tiles reflow before release. Drop
  commits once; cancellation restores the last committed order
- **Customize-header polish.** A restrained sliders/tune glyph replaces the pencil while preserving
  the Back, title, Customize, and Add structure and accessible touch targets
- **A dedicated native PDF viewer in full chat.** Android `PdfRenderer` keeps only the previous,
  current, and next pages rendered, while the existing zoom/pan arithmetic provides pinch,
  double-tap, panning, and edge-aware horizontal page swiping
- **Page navigation and selection.** Page count, accessible previous/next controls, and a compact
  page scrubber provide direct jumps without a permanently visible thumbnail rail
- **Local document search.** PDFBox extracts a bounded page-aware text index off the UI thread;
  case-insensitive results retain page identity and previous/next navigation jumps to the match
- **Ask Orbit about the current page.** The exact page becomes a visible, removable structured
  composer attachment. It remains local until Send, never auto-sends, and history preserves the
  document label, page number, page count, and bounded page text needed for regeneration
- **Reading-surface polish.** The viewer follows Orbit Accent, font, dark/AMOLED, reduced-motion,
  accessibility, and responsive tablet conventions while leaving the rendered PDF page faithful
  to the source

### 0.7.8.2-beta.3 — Robustness & Document Polish
A refinement Beta rather than a feature Beta, built entirely from Galaxy S25 Ultra testing of
Beta 2. No new surface was added; four working foundations were finished.

- **Final Deck drag stability.** The provisional tile order is now the single source of truth for a
  drag: logical slots are derived from it, every non-dragged tile animates from where it visibly is
  rather than from where it was last laid out, and hit testing asks which provisional slot contains
  the finger. Neighbours no longer appear to overlap, cross through one another, or snap, and the
  carried tile keeps exactly one reserved slot so nothing can look duplicated
- **Visible PDF search highlighting.** `PdfTextPageReader` captures per-character geometry through
  the same extraction the search index uses, normalized to fractions of the displayed page by
  `DocumentPageMapping`. Highlights are drawn over the page through the same transform as the image,
  so they stay welded to the word through fit, zoom, pan, double-tap and resize, and the cached
  render is never modified. Previous/Next moves between individual occurrences, including several on
  one page
- **Richer Ask Orbit page context.** The staged page is a structured object — document reference,
  page index, page count, bounded text, and one bounded rendering — presented as a real attachment
  card with a page thumbnail, the document's title and `Page N of M`. Tapping it reopens the
  document at that exact page; a sent turn keeps the same reference and reopens there too
- **Exact page visual context.** The same bounded rendering is both the card's thumbnail and the
  image a vision-capable provider receives, so a chart, diagram or scanned page can be asked about.
  A provider without image support simply carries the page text, and the context never claims an
  image was seen
- **Truthful PDF attachment wording.** How much text was actually extracted is recorded at load and
  drives both the card's caption and the default prompt, so a fully read PDF is no longer described
  as a preview
- **Robust natural timer durations.** One shared `DurationParser` sums every component of a duration
  instead of reading the first count and unit, and understands fractions and decimals. The chained
  command splitter no longer breaks a duration at the word "and"
- **Proactive notification channels.** `OrbitNotificationChannels.ensureAll` registers all four
  channels at start-up, under their existing IDs, posting nothing
- **Bounded large-document UI.** Page views as well as bitmaps are now windowed, so a
  multi-thousand-page PDF no longer measures thousands of children on every layout pass

### 0.7.8.2-beta.4 — Deck Wide-Tile Drag Polish
A single interaction fix from Galaxy S25 Ultra testing of Beta 3, which validated standard-tile
dragging and isolated the remaining defect to full-span tiles. No feature change.

- **Span-aware provisional insertion.** Choosing a neighbour and inserting beside it only packs into
  a valid grid when every tile is one column wide. A carried tile wider than one column is now asked
  where its span actually fits: `DeckGridLayout` enumerates the insertion points the packer can take
  without wrapping the span, packs each one, and picks by where the tile would really land. The
  provisional order is therefore always an arrangement the layout already produced
- **Complete rows at every step.** A full-span tile reserves its whole declared span at every visible
  provisional state rather than only after the drop, so it can never occupy half a row, share a row
  it owns, or leave a vacated cell above itself
- **Standard pairs move as a unit.** Both halves of a pair receive new slots in the same provisional
  calculation, so neither is stranded beneath the carried card while the other moves
- **Declared span, not phone geometry.** The rule reduces to row boundaries on a two-column phone and
  respects a two-column span on three- and four-column tablet grids, where Wide legitimately shares
  a row
- **Standard dragging untouched.** The one-column path is unchanged line for line; the span path is
  purely additive, and a regression test pins the neighbour model's exact ordering behaviour

### 0.7.8.2 — Orbit Deck, Documents, and natural timer durations
Validated on a Galaxy S25 Ultra across four Betas (`beta.1`–`beta.4`) and released as `0.7.8.2`
Stable. Each Beta is recorded in full above; in short:

- **Beta 1** introduced Orbit Deck: a first-party surface with a user-owned persistent layout,
  Standard and Wide tiles, a responsive phone and tablet grid, and tiles for Orbit destinations,
  Routines, safe device actions, apps and saved prompts. Chats stayed home, and Suggestions stayed
  local, deterministic and optional
- **Beta 2** added Documents — a native local PDF viewer with page navigation, a page scrubber,
  pinch, double-tap, pan and local text search — and refined Deck's reordering
- **Beta 3** finished four foundations from device testing: visible PDF search highlighting welded
  to the word through every transform, a structured Ask Orbit page context with a thumbnail card and
  exact-page reopening, one shared deterministic duration parser behind `SET_TIMER`, and proactive
  registration of Orbit's four notification channels
- **Beta 4** corrected the one defect Beta 3 left: dragging a full-span Deck tile produced valid
  drops but invalid provisional layouts. Insertion for a carried span is now decided by where the
  span actually fits rather than which neighbour the finger is over
- **Stable** carries Beta 4's behaviour unchanged. The Galaxy S25 Ultra pass confirmed standard and
  full-span Deck dragging, the PDF viewer, search highlighting, Ask Orbit page context, corrected
  timer durations and notification consistency, and found no new regression — so promotion changed
  version metadata and release documentation only, with no functional source change

### 0.7.8.3-beta.1 — Theme Studio
`0.7.8.3-beta.1` opens the Theme Studio line on a `0.7.8.2` Stable that held up on the device. It is
one feature, and the design decision that shaped it is not a visual one.

Orbit already had an appearance system: an accent key, an AMOLED flag and two bubble-colour keys,
each read directly out of `Prefs` by whatever needed them. The obvious way to build a theme editor
is to put a new theme model beside that and have both write colours; the result is two sources of
truth that agree until the day they do not. So Theme Studio does not introduce one. A theme *is*
those preferences, named as a single object, plus the two tokens this Beta adds. Migration is
therefore a schema stamp rather than a conversion — an upgrading install keeps the exact values it
had — and every one of the ~400 existing `UiKit.accent()` call sites keeps working untouched.

- **A dedicated Theme Studio**, reached from Settings → Look & Feel and from an optional Deck tile,
  with a live preview built from the same resolved tokens the real conversation is built from, so
  it cannot drift into showing colours the app would not actually draw
- **Six user-facing decisions**: accent, user bubble, assistant bubble, cards, background and
  AMOLED. Everything else Orbit draws — the card ramp, secondary text, inline code, links, system
  bar icons — is derived centrally from those rather than stored
- **A draft model.** Editing changes a draft and nothing else; Apply commits it; Back with unapplied
  edits reaches a discard confirmation through Orbit's existing guarded-editor contract
- **Seven Orbit presets** (Default, AMOLED, Nebula, Tide, Ember, Moss, Blurple), immutable, and a
  local library of the user's own with save, rename, duplicate and delete
- **An Orbit-native colour picker** — hue, saturation/value, hex entry, current-versus-new — built
  from `Canvas` rather than a new dependency, with alpha deliberately not offered
- **One owner for readability.** `OrbitContrast` holds every luminance and contrast threshold Orbit
  applies, including the two role cutoffs that already shipped, which are preserved rather than
  unified so no existing user's accent or bubble silently changes its foreground
- **Contrast warnings, not prohibitions.** Orbit names the pairing that would be hard to read and
  still lets the theme be applied
- **AMOLED stays AMOLED**: true black for the page, and the card ramp untouched, so cards remain
  visible rather than the whole app collapsing to one flat black
- **Responsive**: one column with the preview on top on a phone, preview beside the controls on a
  tablet, with the content width capped so a large tablet centres rather than stretches

### 0.7.8.3-beta.2 - Theme Studio Refinement
Beta 1 proved Theme Studio works on the device. Beta 2 is about making it feel like it has always
been part of Orbit, plus one unrelated bug the same device testing turned up.

The consolidation is the point of the release. Beta 1 left Look & Feel with two apparently
authoritative theming systems: Theme Studio at the top, and immediately underneath it the accent
menu, the AMOLED switch and the two bubble menus that predate it. They wrote the same preferences,
so nothing could go out of sync, and that was never the problem. The problem was that the screen
asked a question it could not answer, and on the Galaxy S25 Ultra the two cards sat close enough
together to read as one surface with two minds.

- **One color destination.** Look & Feel is now Theme Studio plus a Typography & Feedback card. The
  duplicate accent, AMOLED and bubble controls are gone rather than hidden, and the entry card
  carries a live summary strip resolved from the same tokens the app draws from
- **One palette.** `OrbitPalette` is the single definition of every named Orbit color. The four
  parallel arrays and the lookup chain that used to hold them are derived from it, and the presets
  that stored Violet, Blue and Mint as raw hex values now name them. A schema 2 migration writes the
  name over an identical stored hex value, so a Beta 1 install stops being told Orbit's own color
  is custom
- **Migration became stepwise.** Bumping the schema means migration re-runs on every Beta 1 install,
  and the step that names an appearance would have renamed a theme somebody created back to "Your
  theme". Each step now runs only for installs that have not had it
- **Nova AMOLED**, a built-in preset marked as the creator's favorite. Nova's exact hue on a true
  black page. It is not raw Nova, deliberately: at `#4C00FF` the color cannot reach 3 to 1 against
  any dark surface, so a preset using it verbatim would trip Orbit's own contrast warning
- **Links became a derived theme token.** They follow the accent, and where the accent cannot read
  on the surface behind it the correction now walks in HSV with the hue pinned instead of mixing
  toward near-white ink, which used to desaturate the color and pull its hue toward grey at once.
  The Theme Studio preview reads the same token the renderer does, so a draft's sample link finally
  moves when the accent moves
- **Copy and spacing.** American spelling, no em dashes, shorter explanations, one ratio format in
  the contrast warning, and a section heading between the Theme Studio card and the one below it so
  the separation comes from Orbit's own spacing rather than a one-off gap
- **Fractional timers.** `set a timer for 4 and 1/2 minutes` produced a five-minute timer on the
  device. The tokenizer stripped every character it did not recognise and the solidus was one of
  them, so `1/2` arrived as the separate tokens `1` and `2`, the `and 1` was read as an addend of a
  whole minute, and the stray `2` was discarded. Written and Unicode fractions are now first-class,
  with mixed-number semantics kept distinct from the spoken kind

### 0.7.8.3-beta.3 - Portable Themes
The storage format was written for this. `OrbitTheme` has carried a format identifier and a schema
since Beta 1, and Beta 3 is the release where something finally reads them.

- **`OrbitThemeFileCodec`**, one boundary between a theme file and Orbit. Import validation lives
  there rather than in the screen, because a document from Android's picker is untrusted input and
  the checks that make it safe are easier to trust when they can be read and tested as a unit
- **Import through the system picker**, with no storage permission. A document is refused unless it
  is JSON, declares the exact `orbit.theme` format, uses a schema this build understands, carries a
  whole appearance rather than a fragment, and is small enough to be a theme. A schema from a newer
  Orbit says so; everything else gets one short sentence, and no parser message reaches the user
- **An imported file cannot claim identity.** `builtIn` and `id` are read past, never trusted. Every
  import becomes a custom theme with a fresh local id, so no file can impersonate a shipped preset
  or overwrite a theme the user already saved. Duplicate display names stay legal
- **Import is previewed, not applied.** The theme is drawn in the same preview the editor uses, then
  added to the user's own presets and loaded into the draft. Apply is still the only thing that
  changes what Orbit looks like, and an edit in progress is never discarded without being asked
- **Export writes the canonical document**, not a second serialization, through Android's document
  creator to a sanitized filename such as `Nova-AMOLED.orbit-theme.json`. Built-in presets, saved
  themes and the current draft all export by the same path, and exporting never saves or applies
- **A theme file carries appearance and nothing else** - no intents, URLs, permissions, credentials,
  Routines, Memory or Deck state - and is separate from Backup & Restore rather than a replacement
  for it
- **Preview and layout polish.** The Deck sample draws Orbit's own Deck mark tinted by the draft
  accent instead of a plain dot, the tablet action bar no longer stretches across the full content
  width, and the import preview is bounded so it reads the same on a phone and a Tab S9 Plus

### 0.7.8.3-beta.4 - Preview Mark
One fix, released so it reaches a device: a Beta only gets to a phone through the normal update
path.

- **The Theme Studio preview header draws Orbit's own mark.** It was a plain accent-filled circle,
  the only element in the preview that was not a piece of Orbit, and it said nothing useful because
  how an accent looks as a flat disc is not how it looks on the actual icon. It is now the same
  `UiKit.orbitMark` the Chats header and the overlay draw, whose geometry is deliberately identical
  to `ic_orbit.xml`, so the miniature and the real mark cannot diverge
- **The mark wears the draft's accent, not the applied one.** `orbitMark` resolves the accent inside
  `onDraw` so a mark already on screen turns when Settings changes it, which is right for a header
  and wrong for a preview of a theme nobody has applied. It gains an overload that takes the accent
  as a parameter; existing callers keep the draw-time behavior they had

### 0.7.8.3-beta.5 - Popup Anchoring
Theme Studio's Color menus were positioned as free-floating popups rather than as menus belonging to
the row that opened them, which the S25 Ultra showed immediately.

- **One shared placement helper.** `anchoredOrbitPopupBounds` decides where an Orbit popup opens
  from the anchor's on-screen bounds and the usable frame, so Theme Studio does not grow a second
  positioning system beside `showOrbitMenu`
- **Below when it fits, above when it does not.** A long menu flips into the space above its row
  rather than being clipped or pushed off screen, with a fixed gap either way
- **The Revert and Apply bar is unavailable space.** The fixed action bar is subtracted from the
  usable frame, along with system bars and display cutouts, so a menu can never open underneath it

### 0.7.8.3-beta.6 - Centered Color Menus
Beta 5 anchored a menu's trailing edge to the trailing edge of the row that opened it. A colour row
spans the whole content card, so the much narrower menu was pushed hard against the right margin:
correct vertically, visibly off-centre horizontally.

- **Centred on the content frame, not the anchor's edge.** The same placement helper now takes an
  optional content frame and centres the popup within it. With no frame it keeps the previous
  anchor-centred policy, so `showOrbitMenu` and `showOrbitActionMenu` are unchanged
- **The clamp still wins.** The existing left and right clamp runs after centring, so a popup can
  never touch or cross a screen edge
- **Tablets centre within the controls pane.** Theme Studio passes its scrolling column, which is
  the full width on a phone and the right-hand pane on a tablet, so a menu stays beside the row that
  opened it instead of floating over the preview. No coordinate is hardcoded
- **Beta 5's vertical behaviour is untouched**, including the flip, the row gap, and the treatment
  of the action bar as unavailable space

### 0.7.8.3 - Theme Studio
Validated on a Galaxy S25 Ultra across six Betas (`beta.1` to `beta.6`) and released as `0.7.8.3`
Stable. Each Beta is recorded in full above; in short:

- **Beta 1** built Theme Studio on Orbit's existing appearance preferences rather than beside them,
  so a theme *is* those preferences named as one object: six user-facing decisions, a live preview
  built from the same resolved tokens the app draws from, a draft model with Apply and Revert, seven
  Orbit presets, a local library of the user's own, an Orbit-native colour picker, and contrast
  warnings rather than prohibitions
- **Beta 2** made Theme Studio the one place Orbit's colours are set, gave every named Orbit colour
  a single definition in `OrbitPalette`, made migration stepwise so no existing theme was renamed,
  added the Nova AMOLED preset, made links a derived token that stays readable, and fixed fractional
  timers such as `4 and 1/2 minutes`
- **Beta 3** made a theme portable: one validating codec between a theme file and Orbit, import and
  export through Android's own picker with no storage permission, external files forced to custom
  identity with fresh local ids, and preview before anything is added or applied
- **Betas 4, 5 and 6** were device corrections: the preview header drawing Orbit's real mark in the
  draft accent, then where a Color menu opens vertically, then how it sits horizontally
- **Stable** carries Beta 6's behaviour unchanged. The Galaxy S25 Ultra pass confirmed the editor,
  the live preview, presets, AMOLED, import and export, and the corrected Color menus, and found no
  new regression, so promotion changed version metadata and release documentation only, with no
  functional source change

# Next

## Orbit Vault / Quick Capture

The next major development priority, after Theme Studio. A searchable local-first collection for
quickly saving screenshots, selected text, clipboard content, photos, useful Orbit answers, and
later document snippets, with quick capture and share flows, a future **Ask Orbit about this** flow,
and an eventual Orbit Deck tile.

# Later

## Future direction

The in-app Roadmap in `RoadmapActivity` is future-only and is audited against this history whenever
it changes. Anything released belongs to the sections above and to What's New, never to the list
below.

### 0.7.7 development line — hybrid, provider-agnostic AI

v0.7.7.0 shipped this line's foundation: the provider layer, the AI Providers screen, and Orbit
Local's first release, all recorded in the completed sections above. What remains here is the
genuinely unfinished remainder of the line, in the order the next patches should take it.

The long-term goal is unchanged: Orbit becomes a **hybrid, provider-agnostic Android assistant
runtime** rather than an app tied to one model service.

#### Near-term order, after v0.7.8.0
v0.7.7.4 shipped the Beta channel, so this order is now also the order these are expected to be
*tested* in: a feature becomes a numbered Beta, is validated on a real device, and only then
becomes a Stable release.

v0.7.7.7 deliberately took its Beta slot for real Calendar control and request-duplication
reliability, ahead of the Orbit Local device-action model that previously held first place. A real
device showed Orbit confidently claiming to add a schedule while writing nothing, and the same
reports exposed one send producing two requests; both were worth more than another provider
feature. The Orbit Local device-action plan is unchanged and simply moves down one place, and it
now has something to reuse: the direct Calendar writer lives in Orbit's common device-action layer,
so a local action model reaches calendars through the same component rather than a second path.

v0.7.7.8 then took the following Beta slot for Thinking updates, a focused UX release rather than a
provider one. It is small, it is optional, and it is off by default, so it was worth taking ahead of
the larger runtime work rather than behind it. Orbit Local device actions remain unstarted and
unchanged at the head of this list, and the release below them is untouched.

`0.7.7.9` has now reached Stable: the full-app gesture system, shipped as `0.7.7.9-beta.1`,
corrected in `0.7.7.9-beta.2`, and extended app-wide in `0.7.7.9-beta.3`. It was a UX release like
`0.7.7.8` rather than a provider one, and it was taken then because the navigation model it
establishes is something later screens should be built on rather than retrofitted to. It took the
Beta slot in front of Orbit Local device actions exactly as `0.7.7.7` and `0.7.7.8` each did, and
never displaced them.

`0.7.8.0-beta.1` — **Orbit Local device-action foundation, plus everyday utilities** — opened the
line and has since been validated on the device and released as part of `0.7.8.0` Stable. It opens
item 1 of the numbered list below without finishing it. What actually shipped:

- **A second, much smaller model beside the chat model.** The component stopped holding one model
  and started holding two, keyed by slot: independent download, pause, resume, verification,
  storage accounting, and deletion, with the chat model keeping its original preference keys and
  file name so no existing install re-downloads anything. The IPC contract went to protocol 3
- **The action model itself**: Qwen 2.5 0.5B Instruct, Apache-2.0, the same publisher, export
  format and runtime as the chat model, ~521 MB, pinned by exact size and SHA-256, downloaded only
  when asked for and removable on its own
- **A semantic fallback, never a replacement.** `LocalCommandRouter` and every other deterministic
  router still run first and keep everything they can handle. The model is reached only when the
  message already reads as an instruction about something Orbit can control, and every failure
  falls through to the provider
- **One executor, as promised.** The model requests a normalized action; `LocalActionSchema`
  validates it against a small allowlist and rebuilds the parameters itself; `DeviceActionExecutor`
  runs it. Orbit Local gained a way to *ask* for tools and no Android-control code of its own
- **Initial allowlist**: flashlight, brightness, media volume, Do Not Disturb, ringer mode, media
  transport, timer, alarm, open app, open Settings. Deliberately small
- **New shared actions for every provider**: `MEDIA_CONTROL` and `SET_RINGER_MODE`, both reported
  from what Android actually confirmed rather than from a call that did not throw
- **Deterministic calculator and general unit conversion**, on the same exact rational the kitchen
  work introduced, and **device-status answers** for battery, brightness, media volume, ringer and
  Do Not Disturb
- **The Orbit Launch Sequence**, a hidden decorative scene behind a hold on the Chats mark

Still open on item 1: the allowlist beyond those ten actions, relative-follow-up handling through
the semantic path, and multi-action requests, which Beta 1 refuses outright.

`0.7.8.0-beta.2` is a corrective pass over Beta 1, not a second feature release. Beta 1 held up on
the Galaxy S25 Ultra; four things did not, and all four were found by using it rather than by
reading it:

- **A status question behaved differently depending on how it was phrased.** "What is my media
  volume right now" reached the provider while "what's my media volume right now" was answered
  locally, seconds apart. Two orderings compounded: the shared politeness rule removed the trailing
  "right now", taking the cue that marked the question as being about a current value, and the
  generic conceptual-question guard matched the expanded opener while missing the contraction. The
  cue is now read before politeness stripping, contractions are expanded before anything is
  classified, and the guard has been replaced by an explicit how-to rule. `LanguageNormalizer` is
  untouched, and the contracted and expanded forms are asserted as pairs
- **The status readings sounded like a debug dump.** They are now written in Orbit's own voice, and
  are still entirely deterministic — no reading is ever sent to a model to be reworded
- **"Use in chat" was SMS-specific and was being offered everywhere.** An email reply drafted in
  Gmail opened a text message to the sender, because the only "use this reply" path resolved a
  visible name to a phone number. The medium is now decided by `ReplySurface` from the foreground
  package, the helper is named `openSmsReplyComposer`, and a surface Orbit cannot insert into offers
  no insert control at all rather than a misleading one
- **A clarification could be mistaken for a sendable draft.** When Orbit asked which participant the
  user was, the overlay offered to send that question into the group, and the controls stayed on it
  after the real draft arrived. Reply-draft turns now carry an explicit `ReplyDraftOutcome`
  classification on a contract Orbit writes and strips, and the control row is bound to a turn
- **The Launch Sequence bodies did not follow the finger.** Move events were consumed and discarded
  and a nudge was applied at release. They are now driven from the touch on every move event, with
  bounded release momentum, and the orbiters are deliberately unnamed — Luna, Terra and Sol are Auto
  routing codenames and reading them in the easter egg suggested it selected a model

`0.7.8.0-beta.3` is a focused feature Beta built on a Beta 2 that held up on the device. It is not
corrective: nothing in Beta 1 or Beta 2 was found wrong. It does three things, one of which was
found by using the phone and is release-blocking:

- **Attachments became a set rather than a slot.** Orbit modelled a manually attached thing as one
  `ComposerAttachment` everywhere it mattered — the tray drew one, the request carried one image,
  and a stored turn recorded one path — so "four photos" could not be expressed at all. There is
  now one canonical ordered `ComposerAttachments` collection, and Gallery, Camera, File, Clipboard
  and Share all add to it; there is deliberately no second list for a multi mode. One central limit
  of ten per turn governs every route. Screen context stayed out of it: manual Use screen, Select
  area, automatic context and an app profile's Attach policy keep their own state and their own
  semantics, so the `0.7.7.8` manual-versus-policy rule is untouched
- **Multiple gallery selection, through the user's own Gallery.** The chosen picker is launched as
  itself with `EXTRA_ALLOW_MULTIPLE`, which a picker that supports multi-select honours and one that
  does not simply ignores — so Samsung Gallery is never quietly swapped for the system photo picker
  to make multi-select easier. Results are read from `getData`, `ClipData` and the platform's own
  result shapes at once, deduplicated on normalized URI identity rather than filename, in first-seen
  order. Decoding is sequential through the existing `AttachmentLoader`; there is no second image
  decoder and no unbounded executor, and one unreadable item costs that item rather than the batch
- **The request, the history and the providers all learned to count past one.** A turn sends one
  user message containing its text and every image, in order — never one turn per photo and never a
  stitched composite. `AssistantClient.History`, `PendingRequestStore` and `ConversationStore` carry
  an ordered path list beside the original single-path field, so a conversation written before this
  loads unchanged and no migration runs. Attachment continuity was generalised rather than widened:
  the retention budget is still three images, now spent newest-first across at most three turns, so
  "3 turns × 1 image" did not silently become "3 turns × unlimited". `AiCapabilities` gained
  `multipleImages`, which ChatGPT declares and the relay does not; the relay sends the first image
  and says so, to the model and in Diagnostics, rather than dropping the rest quietly. Orbit Local
  claims no vision at all, as before
- **Share to Orbit.** A dedicated exported `ShareToOrbitActivity` is the only surface that reads
  content from another app: it validates the action, MIME, extras and item count, collects and
  deduplicates the streams, stages them under a private one-shot token, opens a real conversation
  through `OrbitNavigation.stackFor`, and finishes. Nothing shared is ever executed or interpreted,
  no instruction is prepended, and nothing is sent — a share opens a composer holding the material
  and waits. URI grants are re-granted forward as `ClipData` so the composer can still read the
  photos after the doorway closes, and nothing larger than a URI crosses the Binder
- **A protected emergency and crisis dialing boundary.** Real-device testing produced the sharpest
  finding of the release: a model answering a safety question wrote sensible advice *and* returned a
  `DIAL` action for 911 with `requiresConfirmation` false, and Orbit opened the dialer on its own.
  Nothing had failed — every layer did exactly what it was designed to do — which is the point. The
  gate is now in the shared action layer: `DeviceActionExecutor` is the only place in Orbit that
  builds a dialer Intent, and it will not build one for a protected number without a grant that only
  a human tapping a confirmation can issue. So no provider, model, deterministic router, routine,
  widget or tile can bypass it, because none of them is asked. Protected numbers are 911 and 988,
  matched on the whole normalized number so `9-1-1` counts and `1911` does not, with the map as the
  extension point for more later; Orbit does not claim a worldwide database. The Intent stays
  `ACTION_DIAL` and has never been `ACTION_CALL`, so the call itself still belongs to the user. The
  assistant's ability to recommend emergency help is deliberately untouched — recommending and
  acting are different things, and separating them is the whole design

`0.7.8.0-beta.4` is the final device-polish pass for the `0.7.8.0` line. Beta 3 held up on the
Galaxy S25 Ultra, so this adds nothing and reopens nothing: it fixes the four things that only a
phone could show. Photos in the composer are captioned by position rather than by a forty-character
gallery filename, while documents keep the real names that are the only thing identifying them. The
strip stopped ending every redraw with a jump to the far right - a first batch now starts at the
beginning, an appended batch reveals what was just added, and a removal or a retheme leaves the
viewport alone. The protected-dial confirmation was correct and looked like a system warning, so it
became one compact shared card used by both full chat and the Side-button overlay rather than two
hand-built ones. And the wording was made to match the state: the reply path now distinguishes a
protected action that is awaiting confirmation from one that has executed, so Orbit can no longer
say it is opening the dialer before the user has agreed. The gate itself is untouched.

`0.7.8.0` has now reached Stable. Beta 4 passed device validation on the Galaxy S25 Ultra, so the
line was promoted unchanged: the Stable build is the Beta 4 build with its version metadata and
release guards flipped, and no behaviour was retuned during promotion.

**Development continues at item 1 below — Orbit Local device actions**, which this line opened and
deliberately did not finish. Its stated remainder is unchanged: growing the allowlist past the
initial ten actions, letting the semantic path resolve short follow-ups the way the deterministic
one already does, and multi-action requests, which are still rejected outright rather than partly
obeyed. Nothing below item 1 was started here, and no future item is complete.

### 0.7.8.1-beta.1 — Attachment Viewer + Ask Orbit
`0.7.8.1-beta.1` is a focused two-feature Beta on top of a `0.7.8.0` Stable that held up on the
device. It is deliberately not the next step of the numbered list below: Orbit Local device actions
remain unstarted and unchanged at item 1, and nothing in this Beta touches the action model, the
allowlist, the deterministic router, the everyday utilities, or the protected-dial boundary.

What it adds:

- **A full-screen viewer for image attachments.** `0.7.8.0` made a message able to carry ten photos
  and drew them back as a 40dp strip, which is enough to tell two photos apart and nowhere near
  enough to read one. Tapping an image now opens it full screen on black, with pinch and double-tap
  zoom, panning while zoomed, and a swipe between the images of one message. It applies to both an
  unsent composer and a turn already sent, and it is a viewer rather than an editor: no crop, no
  markup, no rotation, no export, because Screen Selection already owns that workflow. Documents are
  deliberately excluded — a PDF card carries a rendered first page, and offering page one full
  screen with no way to reach page two would be worse than the card it already had
- **Ask Orbit from Android selected text.** A second narrow exported doorway beside
  `ShareToOrbitActivity`, reached through `ACTION_PROCESS_TEXT`, reusing the same staging pipeline
  rather than a second one. Selecting text in another app and choosing Ask Orbit opens a new unsent
  conversation holding exactly that text — no invented prompt, nothing sent. Orbit reads a selection
  and never writes one back: it returns no result, so the text in the source app is untouched
  whether or not the selection claimed to be editable

Both features are gesture- and platform-shaped, which is exactly what a Beta exists to settle:
whether a pinch and a pan feel right on the panel, and whether Ask Orbit actually appears in
Samsung's own selection menu, are not questions a Robolectric run can answer.

### 0.7.8.1-beta.2 - Progressive Responses & Motion Polish
`0.7.8.1-beta.2` is a refinement release, not a new assistant capability. It adds nothing Orbit
could not already do and answers no question it could not already answer; what it changes is how
every answer looks while it is being produced, which is why it was worth a Beta slot ahead of more
feature work. Orbit Local device actions remain unstarted and unchanged at item 1 below, and
nothing here touches the action model, the allowlist, the deterministic router, the protected-dial
boundary, or the Beta 1 attachment viewer and Ask Orbit doorway.

The problem it fixes was visible on essentially every response. Orbit had two different ideas of
what a reply was: while one was arriving it was a single raw `TextView`, so the user watched
`## Heading`, `- item` and triple backticks scroll past as literal characters, and when it finished
that view was thrown away and a completely separate rich tree was built in its place. Every answer
therefore ended with a raw-to-rich jump, and the nicer of the two presentations was the one the
user spent the least time looking at.

What actually shipped:

- **One presentation instead of two.** `ResponseBlocks` names the step both paths were missing -
  deciding what the blocks of a response are - as pure text work with no Context, no View and no
  measurement. `OrbitRichResponseRenderer` exposes a single per-block builder, and the completed
  render and the streaming render now feed from the same parser and the same builder, so they
  cannot drift apart in what they think a response contains
- **A response that is conservative about what it commits to.** A construct is recognised only once
  the text proves it: an open fence is already a code block, but a line of pipes stays ordinary
  prose until the divider row arrives, a rule at the very end of a stream waits one fragment, and a
  half-written image URL is never fetched. A delimiter whose partner has not been generated is
  withheld while its words are kept, so "This is \*\*impor" reads as "This is impor" rather than
  showing the machinery
- **Rendering paced away from the provider.** `StreamRenderScheduler` accumulates deltas
  immediately and rate-limits the presentation to roughly twenty passes a second, expressed against
  an injected clock and poster so the coalescing rule is tested as a rule rather than with a
  stopwatch. A thousand fragments inside one window become two passes, and the newest text always
  wins
- **A diff that leaves settled content alone.** `ProgressiveResponseView` rebuilds only the blocks
  whose displayed text actually changed, which is what keeps a code block's Copy control, a link's
  clickability, a table's horizontal scroll offset and a screen reader's focus alive while the rest
  of the answer is still arriving. Block identity is keyed on what a block displays rather than on
  its raw state, so a paragraph is not rebuilt merely because a list started below it
- **Shared between both surfaces.** Full chat and the Side-button overlay use the same view, so the
  overlay stopped being a second streaming implementation. Two hand-written bubble animations in
  the overlay that ignored the system animation scale were replaced by Orbit's shared arrival, and
  list reordering, which had been written twice at two different durations with two raw
  interpolators and no reduced-motion check, was consolidated into one `UiKit` primitive

Motion is restrained on purpose: new structural blocks get a short fade and a 4dp settle, text
updating inside a block that is already on screen is never animated, and reduced motion removes the
decoration without removing any formatting.


Beta 1 shipped:

- **Back in a conversation became Android's own gesture.** The requirement was an interaction that
  moves under the finger rather than a swipe detector that plays an animation afterwards, and the
  correct implementation of that turned out to be a subtraction. Two things were quietly costing
  Orbit the platform's predictive transition: `ChatActivity` overrode `onBackPressed`, and every
  Orbit window declares `activityClose*` animations through the Page transitions preference, which
  tells Android how the window leaves and therefore stops it tracking one with the finger. Back is
  now `OrbitBackHandler`, which registers a platform callback only while the attachment chooser is
  open and otherwise leaves back entirely alone, and the conversation window uses a page-transition
  variant with no close half. Chats and the conversation opt into the back-callback API per
  activity in the manifest; every other screen keeps its own `onBackPressed` and is untouched.
  The intended result was the real system transition revealing the real Chats screen, with no
  screenshot of Home and no animation code of Orbit's own. On the device it produced no transition
  at all, which is what Beta 2 below corrects
- **Chat cards move with the finger.** `OrbitSwipeRow` holds one piece of state, written directly
  by touch, and everything drawn is a function of it. Arbitration is deliberate rather than a
  threshold on `dx`: vertical intent is decided once and is final, so a scroll that curves sideways
  can never become a delete, and horizontal intent has to beat vertical outright before the row
  takes the gesture. Release commits on distance or on velocity, one haptic marks the crossing into
  the committed region, and only one card in the list may ever be displaced
- **Delete is undoable because nothing is deleted.** The chat is held aside by id and stays exactly
  where it is in storage until the window closes, another delete arrives, or Chats leaves the
  foreground. Undo is Orbit forgetting it was asked rather than a restore, which is what makes it
  complete: no snapshot can omit the messages, attachment references, stopped-turn anchors, mode or
  pinned state, because none of them were ever removed
- **Pin/Unpin, and a Pinned section.** Persisted on the conversation and written only when true, so
  a chat stored before this release reads as unpinned and nothing needed migrating. Pinning does not
  touch `updatedAt`, so it is not mistaken for activity and unpinning returns a chat to exactly
  where it always belonged in Recent
- **Both actions exist without the gesture.** Pin and Delete are on the chat's own menu and exposed
  as accessibility actions, and the action surfaces are drawn rather than laid out, so a resting
  card hides nothing for a screen reader to find
- **Settings > Look & Feel > Gestures.** Two switches, both on. Neither disables Android's back
  gesture, which is not Orbit's to disable: the first chooses whether the conversation hands its
  transition to the platform or keeps Orbit's own

Beta 1 on a Galaxy S25 Ultra, recorded honestly because the third result is the reason Beta 2 exists:

- **Passed: swipe a chat left to delete.** Threshold, resistance, haptic and the Undo window all felt
  right on the device and are frozen
- **Passed: swipe a chat right to pin or unpin.** Same
- **Failed: the conversation's back gesture.** Diagnostics reported `Back path in a conversation:
  system predictive`, and the conversation did not move at all. The subtraction was configured
  correctly and the platform simply did not draw the transition it had been asked for. The deeper
  fault was the reporting: Orbit derived that line from the API level and the preference and
  presented an expectation as an observation, so nothing in the release could tell the difference
  between "asked for" and "happened"

Beta 2 is the corrective patch:

- **Orbit draws the gesture itself.** `OrbitPredictiveBack` registers an `OnBackAnimationCallback` on
  API 34+ and moves the conversation as a pure function of `BackEvent.progress`. Nothing waits for a
  release, so reversing the gesture reverses the picture and cancelling restores it exactly. The
  system path was preferred and was tried first; it was abandoned because Orbit cannot observe
  whether the platform rendered it, which is precisely how Beta 1 shipped nothing while passing
- **The destination is the real Chats screen.** `Activity.setTranslucent(true)` for the length of the
  gesture, so the activity underneath becomes visible and resumes. No screenshot, no second copy of
  the Chats UI. If the platform refuses the conversion the gesture still tracks the finger over
  Orbit's background, and Diagnostics records which of the two actually happened
- **There is now genuinely something underneath.** `parentActivityName` never did this: it is Up
  metadata, and the platform does not consult it for Back. A conversation opened from the overlay, a
  widget, or a notification was the root of its own task, so Back left Orbit. `ChatActivity.stackFor`
  builds Chats-then-conversation for every one of those surfaces, reusing an existing Chats screen
  rather than duplicating it
- **Diagnostics stops claiming what it cannot see.** Configuration, device capability and observation
  are three separate lines now, and the count of progress events Orbit actually drew is the proof
  the old wording pretended to be
- **The Undo bar floats.** It was an ordinary sibling under the weighted list, so showing it resized
  the list's viewport and sliced the straddling chat card flat — the black edge reported on the
  device. The page is hosted in a frame now, the list never changes size, and the one bottom inset
  Chats already applies is applied on the host and nowhere else
- **Each Diagnostics section copies on its own**, collapsed or not, from the same expression the
  screen displays. The raw planner block keeps its separate deliberate control and is excluded from
  every generic copy path

Beta 2 on the Galaxy S25 Ultra passed substantially better than Beta 1, and the Orbit-drawn back
interaction was approved as the navigation direction. Beta 3 makes it the app's navigation language
rather than one screen's feature:

- **One engine, not one per screen.** `OrbitPredictiveBack` was generalized rather than copied. It
  owns capability, callback, lifecycle, motion, translucency, restoration, reduced motion, the
  commit-without-a-second-animation rule and the counters; a screen supplies only a `Screen` policy —
  whether leaving is unconditional right now, what Back means here, and a category name. The Beta 2
  travel, scale, corner and commit timings are untouched, so Settings feels like leaving a chat
  because it is the same code
- **Every screen is classified, in data.** `OrbitNavigation` holds the audit: PREDICTIVE for the
  twenty ordinary pages, GUARDED for the six editors, LOCAL for the six screens where Back cancels or
  steps, ROOT for Chats. The test matrix asserts every one of them and fails when Orbit gains a
  screen nobody classified, so the next new page cannot land in the wrong class by accident
- **The reveal stays real everywhere.** A Settings section is a second `SettingsActivity` on top of
  the hub, so the hub genuinely is the page underneath it. Nothing is screenshotted and no parent is
  reconstructed
- **Editors are guarded rather than excluded.** The page moves only while there is nothing to lose;
  once there is, it does not move and Back reaches the screen's own save-or-discard contract by the
  route it always did. No confirmation was added and none was removed
- **The manifest opt-in was the trap.** `enableOnBackInvokedCallback` stops `onBackPressed` being
  called at all, so opting an editor in without migrating it would have deleted its discard
  confirmation outright. Every opted-in screen now registers something on API 33+ — the drawn gesture
  where the device and the preference allow it, and otherwise a plain callback performing the same
  Back — and the manifest and the classification table are asserted to be the same set
- **Entry points from outside build their stack.** Update and routine notifications, both Quick
  Settings tiles and the assistant-setup tile now open Chats-then-page (and Chats-hub-section for a
  Settings detail) instead of dropping a page in as the root of a fresh task
- **`Swipe back to Chats` became `Swipe to go back`**, on the same stored key, so an explicit opt-out
  survives the rename

Deliberately **not** in scope, then or now: per-message swipe actions, forward or history-style
navigation, and any horizontal gesture in the Side-button overlay. The overlay's vertical swipe
behaviour is settled and is not being reopened.

### 0.7.8.1-beta.3 - Markdown & Chat Presentation Polish
`0.7.8.1-beta.3` is real-device polish over Beta 2, not a second refinement pass and not new
capability. A Galaxy S25 Ultra confirmed the progressive architecture works: headings, lists, code,
tables, links and quotes all format while the response is arriving instead of waiting for it to
finish. The same test showed four presentation defects that only a screen could show, and this Beta
corrects exactly those four and leaves the Beta 2 architecture alone. Orbit Local device actions
remain unstarted and unchanged at item 1 below, and nothing here touches the action model, the
allowlist, the deterministic router, the protected-dial boundary, the request-integrity gates, or
the Beta 1 attachment viewer and Ask Orbit doorway.

What actually shipped:

- **Combined emphasis is one construct.** `***bold italic***` was coming out as a bold phrase with
  a literal asterisk still visible at each end, because the `**` matcher got first refusal and
  consumed the middle four of the six delimiters. Combined emphasis is now its own alternative
  above the runs it contains, and it applies two ordinary style spans over one range rather than a
  third kind of emphasis, so bold-italic composes with everything around it. The same pass put the
  full renderer on the word-boundary rules the Chats-list preview already used, so `2 * 4 = 8` and
  `some_variable_name` keep their characters
- **A table row is one row.** Cells were laid out at their own content height, so a row with
  unevenly wrapped columns broke into cards of four different heights with the assistant bubble
  showing through underneath the shorter ones. Cells now take their row's height, which is layout
  behaviour rather than measurement: a horizontal `LinearLayout` whose own height wraps already
  re-measures its match-parent children against the tallest of them. Rows keep independent heights,
  no height is hardcoded, and horizontal scrolling, cell width bounds and header styling are
  untouched
- **Task lists are shown, not operated.** `- [x] Done` reached the screen with its brackets intact.
  It is now a `TaskBoxSpan` drawn over a leading placeholder inside the item's own TextView, so the
  box and the words are one row that indents, wraps and scales together, and the text after the box
  is ordinary rich Markdown. There is deliberately no control to press — these boxes present what
  the assistant wrote, and tapping one must never edit a stored reply — and state is announced in
  words rather than as a toggle
- **Jump to latest stopped covering the answer.** Same control, same place, same appearance rules,
  same touch target; it simply rests slightly translucent, with the entrance animating straight to
  that value rather than to fully opaque and correcting itself afterwards

All three Markdown fixes apply to full chat and the Side-button overlay together, because both
surfaces share one parser and one block builder.

1. **Orbit Local device actions** — *foundation shipped in `0.7.8.0-beta.1`, not finished.* The
   pipeline is now real and is the one that was written down: user request → deterministic routers
   → lightweight local action model → normalized Orbit action → `LocalActionSchema` validation →
   the existing shared executor → observed result. `ComponentModelSpec` keys the two models' files
   and state independently, and the local model only ever *requests* existing Orbit tools; it has
   no device-control logic of its own and will not be given any.

   What remains: growing the allowlist past the initial ten actions, letting the semantic path
   resolve short follow-ups ("put it back") the way `RecentActionContext` already does for the
   deterministic one, and multi-action requests, which Beta 1 rejects outright rather than partly
   obeying. Orbit Local still declares `deviceActions(false)` for chat, because its chat model does
   not produce actions; the action model is a separate, optional install and says so on its own card
2. **Calendar maturation** — reading the calendar as context ("what does my Saturday look like"),
   editing and removing events Orbit itself created, and recurring events. Deliberately not in
   v0.7.7.7, which never modifies or deletes an existing user event
3. **Better Routine conditions and additional branch capability** — conditions beyond time and
   place, and more than one decision point in a single Routine
4. **Cook with Orbit** — the explicit temporary cooking session described below
5. **Kitchen hands-free** — the short spoken vocabulary inside an active cooking session
6. **Recipe intelligence** — extraction, whole-recipe scaling, substitutions, and sequencing

**OpenRouter is deferred.** Finishing OpenRouter chat requires a configured account to validate
properly on a real device, and there is not one available, so shipping it would mean releasing an
untested provider path. The secure setup groundwork from v0.7.7.0 — the configuration shell and
Android Keystore-only API key storage — stays intact and unchanged, and the work resumes when an
account exists to test it with.

Also still open, unscheduled: expanded local-model choices on the same `LocalModelStore` catalog
architecture, provider-aware AI strength maturation beyond today's show-or-hide behavior, and
automatic cloud/local routing groundwork inside `AiProviders`.

#### Long-term Hybrid Auto mode
Orbit chooses provider and model from capability, task, availability, and user preference:

- Simple device command → local, when local can do it well
- Lightweight conversational request → local, when local can do it well
- No network → local fallback
- Complex reasoning → cloud
- Current or web-dependent information → cloud

Routing stays explainable and never silently overrides an explicit provider choice; a future
setting has to opt into any automatic fallback.

### Cooking, and the hands-busy principle

**The principle.** Orbit should be exceptionally useful when the user's hands are busy. Cooking is
the first real case, but nothing built for it should be built *around food*: a session that holds
structured state, a step pointer, relevant timers, and a short hands-free vocabulary is the same
shape for home repair, cleaning, assembling furniture, or following a procedure at a computer.
Keep the session and voice infrastructure generic; keep only the recipe knowledge cooking-specific.

v0.7.7.3 shipped the deterministic foundation — conversions, fractions, scaling, better timer
labels — and is recorded above. What follows is future work.

#### Cook with Orbit
An explicit, temporary **cooking session**, not a permanent Kitchen tab and not a recipe app.
Started deliberately — "cook with me", "start cooking mode", or an offer Orbit makes when a recipe
is clearly present in the conversation or an attachment. Mentioning food never starts it.

A session holds structured state: recipe title, current step, total steps, ingredient quantities,
serving scale, the timers that belong to it, and any cooking notes. It lives inside the normal
conversation system — no separate chat database.

It should answer, without losing the recipe: current step, next, previous, repeat, "what am I doing
now", "what comes next", "how much of that", "what temperature", and ordinary questions in between.
Compact status in full chat and in the Side-button overlay, and a clean way to end the session.

#### Kitchen hands-free
Built on the existing Voice Beta architecture — the same recogniser, ownership, cancellation,
follow-up, IME, and overlay/full-chat rules. No second speech stack. Inside an active cooking
session only, a short command vocabulary for dirty hands: "next", "back", "repeat", "timer five
minutes", "how much", "what temperature", "stop listening".

Possible session-scoped option: keep the screen awake while actively cooking, restored automatically
when the session ends. Opt-in and session-scoped — never a permanent wakelock.

#### Recipe ingestion and intelligence
Start a session from conversation text, a pasted recipe, a screenshot, a photo, the gallery, a PDF,
the clipboard, the current screen, or a safely sourced page — all through the attachment and context
infrastructure that already exists.

Provider-backed, because these are semantic tasks the deterministic layer cannot do: extracting
ingredients and ordered steps, scaling a whole recipe, substitutions, sequencing, what can be
prepared while something else cooks, recovering from a mistake, adapting to a missing ingredient,
planning backwards from a serving time, and answering questions about the active recipe.

#### Food-safety assistance
Internal temperatures, storage, reheating, doneness, spoilage, cross-contamination. Designed
conservatively because it affects health: separate culinary preference from safety guidance, never
imply a photo can prove food is safe, use trustworthy and current information, and say plainly when
something is uncertain. Provider-backed unless a well-maintained authoritative local source exists.
Not alarmist UI.

#### Optional Orbit-managed timers
Android's Clock stays the default and stays available permanently. A later setting — **Use
Orbit-managed timers**, default off, explaining that Orbit otherwise starts timers in the system
Clock app — would opt into several named timers at once, remaining-time queries, listing, pause and
resume, adding and subtracting time, individual cancellation, compact timer cards in chat, overlay
timer status, and a persistent notification. Where the OS allows it, Android 16 promoted Live
Updates and Samsung Now Bar integration, with an ordinary notification as the fallback. OEM
promotion eligibility varies, so user-facing copy must never promise it.

Compile and target SDK move only when that work actually requires it, and only after checking the
current Android requirements. Existing users keep Clock behaviour through any upgrade; the option
is never the new default.

#### Later possibilities, not committed
Shopping lists from an active recipe, combining ingredients across recipes, saved recipes, pantry
awareness, "what can I make with what I have". Not built until real use shows they earn their
place. Orbit should first be excellent *during* cooking rather than become a meal planner.

### Next up
- Local device actions, beyond the first allowlist: more actions, short follow-ups through the
  semantic path, and requests that need more than one action. The foundation shipped in
  `0.7.8.0-beta.1`
- Conditions beyond time and location, and more than one branch point in a single routine
- Cook with Orbit: an explicit temporary cooking session with step state, quantities, and timers

### Planned
- Kitchen hands-free voice inside an active cooking session
- Recipe intelligence: extraction, whole-recipe scaling, substitutions, and sequencing
- More local models, sized to different phones and needs
- Edit & resend returns to the message menu once editing and resending is dependable
- Deeper Android actions
- Custom Commands that accept variation and detail beyond today's exact wording
- Optional Orbit-managed timers, off by default, with the Android/Samsung Clock app permanently
  kept as the default owner of timers
- Food-safety assistance, designed conservatively because it affects health

### Deferred
- OpenRouter chat, until a configured account is available to validate it on a real device. The
  secure setup groundwork and Keystore-only key storage remain intact

### Exploring
- Hybrid Auto: automatic local/cloud routing chosen from capability, task, and availability
- Proactive screen intelligence
- Image retrieval integrations with safe sourcing and attribution
- Hands-busy assistance beyond cooking: repairs, cleaning, assembly, and other guided tasks
- Grocery lists, saved recipes, and pantry awareness, only if real use shows they earn their place

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
