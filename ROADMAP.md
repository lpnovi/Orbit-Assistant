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
- **Provider-aware onboarding.** The Connect Orbit step now presents ChatGPT as recommended, Orbit
  Local as optional with its real component/model state, and collapses OpenRouter and the renamed
  Private API relay behind More provider options. Availability and status come from `AiProviders`
  rather than a second set of hardcoded strings, and setup stays seven steps

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

#### Near-term order, after v0.7.7.7
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

1. **Orbit Local device actions** — a lightweight local intent/function model installed beside the
   chat model (the `ModelSpec` architecture already keeps their files and state independent). The
   intended pipeline is fixed: user request → lightweight local intent/function model → normalized
   Orbit tool request → existing Orbit tool execution layer → result. The local model only ever
   *requests* existing Orbit tools — starting with simple reversible actions such as flashlight,
   brightness, and volume — and never grows its own device-control logic; cloud and local providers
   ultimately share the same tool execution layer. Until it exists, Orbit Local declares
   `deviceActions(false)` and says so plainly rather than pretending otherwise
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
- Local device actions: Orbit Local asking Orbit's existing tools to run simple reversible
  controls
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
