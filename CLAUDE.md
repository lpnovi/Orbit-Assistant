# CLAUDE.md — Orbit Assistant

Durable working notes for Claude Code sessions on this repository. The repository is always the
source of truth; if this file disagrees with the code, trust the code and fix this file.

## What Orbit is

Orbit Assistant is an Android AI assistant built as a highly customizable alternative to Gemini on
Samsung/Android devices. It has two primary surfaces:

- **Side-button assistant overlay** — a `VoiceInteractionSession` shown over other apps, with screen
  context, voice, attachments, and device actions.
- **Full companion app** — chat list, full-screen conversations, Settings, and all management screens.

Both surfaces share conversation history, appearance, preferences, and the action pipeline.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/orbit/assistant/` | All application Java, in one flat package. No Kotlin, no Compose. |
| `app/src/main/res/` | Layouts, drawables, values, `xml/` (widget info, voice interaction service, file paths) |
| `app/src/main/assets/orbit-extensions/` | Bundled first-party `.orbitext` manifests |
| `app/src/test/`, `app/src/androidTest/` | Robolectric unit tests and instrumented tests |
| `tools/build_orbit.ps1` | The real local build entry point (bootstraps JDK 17, Android SDK, Gradle) |
| `BUILD_ORBIT.cmd` | Double-clickable wrapper around the above |
| `.github/workflows/build-apk.yml` | Debug APK CI on push to `main` |
| `.github/workflows/release.yml` | Tag-triggered signed release + verification + GitHub Release |
| `CHANGELOG.md` | Canonical version-by-version history (also the source of release notes) |
| `ROADMAP.md` | Completed and future direction, surfaced in-app via `RoadmapActivity` |
| `docs/EXTENSIONS.md` | Public Extensions v1/v2 schema and security model |
| `docs/MODEL_LIBRARY.md`, `docs/SMART_ROUTING.md` | Provider/model catalog rules, and Auto's routing invariants |
| `docs/PLAY_STORE.md` | Google Play edition: build, signing, versioning, policy (plus `PLAY_CONSOLE_CHECKLIST.md`, `PLAY_LISTING_DRAFT.md`) |
| `app/src/github/`, `app/src/play/`, `app/src/testPlay/` | Per-channel `OrbitEdition`, Play manifest/resource overlays, Play-variant tests |
| `server/` | Optional private OpenAI API relay (Flask + Dockerfile), not part of the APK |

## Build

Local build (produces `Orbit-Assistant-v<version>-debug.apk` in the repo root plus a `.sha256.txt`):

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File "tools/build_orbit.ps1"
```

The script reads `versionName` from `app/build.gradle`, deletes stale root APKs, writes
`orbit-build.log`, and only reports success from Gradle's exit code.

Direct Gradle (for tests or targeted tasks) needs the environment set manually — there is no
`local.properties` and `JAVA_HOME` is normally unset:

- `JAVA_HOME` — a JDK 17; the build script's own lookup order in `tools/build_orbit.ps1` is the
  authoritative list of where to find one on this machine
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` — `%LOCALAPPDATA%\Android\Sdk`
- Gradle — `%LOCALAPPDATA%\OrbitAssistant\BuildTools\gradle-<version>\bin\gradle.bat`, at the version
  pinned in `tools/build_orbit.ps1`

Useful tasks: `assembleDebug`, `testDebugUnitTest`. `assembleRelease` deliberately fails unless all
four `ORBIT_RELEASE_*` values are present. `bundlePlay` builds the Google Play App Bundle
(`app/build/outputs/bundle/play/app-play.aab`), signed with the separate `ORBIT_UPLOAD_*` key when
configured and unsigned otherwise. `testPlayUnitTest` runs only the `Play*Test` classes in
`app/src/testPlay` against the Play variant. Unit tests simulate API 35 by default
(`app/src/test/resources/robolectric.properties`): Robolectric needs Java 21 to simulate API 36.

## Windows / Gradle process cleanup

Orbit development runs on Windows and Gradle uses OpenJDK processes. To reduce stale Java/Gradle
processes interfering with Claude Desktop or Windows app-package updates:

- Reuse Gradle normally while actively implementing and testing. Do not disable the Gradle daemon
  globally.
- Do not run unnecessary concurrent Gradle builds.
- After a task is completely finished — including the final full test suite, APK builds, release
  verification, commit/tag/push, and any other Gradle-dependent work — run `gradlew --stop`.
- Run that only after no further Gradle work is required for the task.
- Do not kill arbitrary `java.exe`, `javaw.exe`, Android Studio, or Windows/system processes as
  routine cleanup.
- If Claude or the computer crashes during a Gradle build, a stale OpenJDK/Gradle process may remain.
  On the next session, diagnose that process before building again.
- A Windows filesystem/app-package lock is an environment problem, not a reason to reset Git, delete
  source/build files, or modify Orbit code.

## Versioning

- `versionName` is a four-part human version (`0.MAJOR.MINOR.PATCH`). Read the current one from
  `app/build.gradle` — never assume it.
- `versionCode` is a **simple monotonically increasing integer**, +1 per release, unrelated to the
  version name. Never reuse or decrease it — Android rejects the install as a downgrade.
- Every release adds one `CHANGELOG.md` line in the exact form `- **v<versionName>**: …` under the
  matching `## 0.x series` heading. `release.yml` parses this line to generate GitHub release notes
  and **fails the release if the entry is missing**.
- `ROADMAP.md` and `README.md` are updated when a release changes product direction or features.
- The git tag is `v<versionName>`; `release.yml` verifies the tag matches the tagged source version.

## Release notes — keep them short

The GitHub Release body is built by `release.yml` **from the `CHANGELOG.md` entry**, never from the
commit message. That entry is user-facing, so write it that way:

- One short summary sentence on the `- **v<versionName>**:` line.
- Then 3–6 bullets indented two spaces (`  - …`), one sentence each, roughly 8–25 words.
- Target 60–150 words total; `release.yml` fails the release above 200 words.
- Say what the user gets. Keep class names, method names, root-cause history, previous failed
  attempts, internal flags, test names, and architecture out of it.

That detail belongs in the commit message, code comments, tests, and the report back to the user —
all of which may stay as technical as they need to be. Commit message length never affects the
release body.

## Signing and update compatibility — do not break

- Application ID is `com.orbit.assistant`. Never change it.
- The release keystore lives **outside the repository**, in a separate local signing location
  referenced by the git-ignored `orbit-signing.properties`. Never regenerate, replace, move, or
  print signing material, paths, or passwords.
- CI signs from the `ORBIT_RELEASE_KEYSTORE_B64` secret and verifies the produced APK's package,
  versionName, versionCode, signer count, and certificate SHA-256 against a pin in `release.yml`.
- The same certificate pin is compiled into `OrbitUpdater.java`. Both must stay in sync with the real
  keystore or in-app updates stop working.
- Users install updates over existing installs. Preserving package identity, signing identity, and
  monotonic version codes is the highest-priority constraint in this project. That applies within
  each edition: the GitHub edition is signed with the release key above, the Play edition with a
  Google-generated Play key. The two are intentionally different and never cross-update. Never
  export or upload the GitHub release key to Google Play.

## Update system

`OrbitUpdater` reads the latest GitHub Release for `lpnovi/Orbit-Assistant`, requires an
`orbit-update.json` manifest asset (generated by `release.yml`), and verifies package name,
versionCode, APK SHA-256, and certificate SHA-256 before handing the file to Android's installer.
Orbit never downloads or installs silently. `OrbitUpdateWorker` / `OrbitUpdateNotifier` handle the
optional background check, gated by the `update_notifications` preference.

## Two distribution channels

Orbit ships as the GitHub edition (build types `debug`, `release`) and the Google Play edition
(build type `play`). Same `com.orbit.assistant` and version sequence, but deliberately different
signing: the GitHub release key for GitHub, a Google-generated Play key for Play (uploads use a
separate Play upload key). The editions cannot update each other; users move with a backup,
uninstall, and reinstall. Orbit Local only serves the GitHub-signed edition.
`app/src/github/.../OrbitEdition.java` is the only code with GitHub release endpoints and the APK
installer hand-off; `app/src/play/.../OrbitEdition.java` has the same shape and refuses everything,
and the Play manifest overlay removes `REQUEST_INSTALL_PACKAGES` and `ACCESS_BACKGROUND_LOCATION`
(so location-triggered Routines are unavailable on Play; see `OrbitDistribution.supportsLocationTriggers`). Ask `OrbitDistribution`, never
re-derive the channel. Never add a GitHub download, installer intent, or self-update path to shared
code, and never let the Play edition install Orbit Local. See `docs/PLAY_STORE.md`.

## Major components

- **Overlay / assistant integration** — `OrbitVoiceInteractionService`, `OrbitSessionService`,
  `OrbitSession` (largest file; overlay UI, context bar, attachments, voice, streaming).
  `OrbitSetupHelper` handles the "Make Orbit default assistant" flow via
  `Settings.ACTION_VOICE_INPUT_SETTINGS` with fallbacks.
- **Full app** — `MainActivity` (chat list/search/tools), `ChatActivity` (full conversation),
  `SettingsActivity` (sectioned: Models & access, Voice/context/permissions, Personalization & data,
  Look & Feel, About & updates), `OnboardingActivity`, `CapabilitiesActivity`, `DiagnosticsActivity`.
- **AI pipeline** — `AssistantClient`, `ChatGptClient`, `ChatGptBrowserAuth` (primary browser
  OAuth + PKCE sign-in, loopback `127.0.0.1:1455`) and `ChatGptAuth` (device-code fallback, shared
  token storage/refresh), `OrbitRequestManager` + `OrbitRequestWorker` (durable WorkManager
  background completion), `ConversationStore`, `PendingRequestStore`.
- **AI selection (0.8.3.0+)** — provider → model → strength. `OrbitModelCatalog` (`AiModelSpec`,
  `AiStrength`) holds model facts; `AiSelections` is the one validation/resolution/migration
  layer; `AiSelection` is what chats, pending requests and `AiRequest` carry. An explicit
  selection is never routed: never add per-request model choice outside `AiSelections` and
  `SmartRouter`. Intelligence modes and `AutoRouter` were removed and must not return;
  legacy `model`/`reasoning`/`intelligence_mode` keys are read once by migration.
  ChatGPT exposes GPT-6 Luna, GPT-6.1 Sol, GPT-6 Astra and GPT-5.6 Luna/Terra/Sol through this one
  catalog; do not reproduce their ids, strengths or context metadata in a surface.
- **Model Library (0.8.3.0-beta.4+)**: `AiModelSpec` is also the authority for context size,
  vision, native-file versus extracted-document handling, tools, web/search, streaming,
  availability and metadata source. `ProviderCatalogRepository` owns bounded last-known-good
  Anthropic/xAI/OpenRouter discovery; never fetch catalogs in an Activity or erase a cache on refresh failure.
  `ModelLibraryStore` owns Favorites, bounded user-turn Recents and per-provider defaults. Internal
  completion/title/summary jobs never call `recordRecent`. Anthropic and xAI credentials stay in
  `SecureStore`, are never backed up, logged, diagnosed, shown again or passed across providers.
  Requests go through `ProviderRequestMapper` and `ApiKeyProviderClient`; do not add their shapes to
  `ChatGptClient`. See `docs/MODEL_LIBRARY.md`.
- **Smart Routing / Auto (0.8.3.0-beta.5+)** — `AiSelection.AUTO` (`1|auto|auto|`) is a per-chat
  selection, never dispatchable. `SmartRouter` is the only router: local, deterministic, a curated
  candidate set (policy 2 since beta.6: 14 routes incl. GPT-5.6 and four exact OpenRouter slugs,
  never `openrouter/auto`), eligibility then selection, `POLICY_VERSION`; bump it whenever routing
  meaning changes and never reinterpret stored policy numbers. It is called only from
  `OrbitRequestManager.enqueueFrozen`; the exact result is frozen on `PendingRequestStore.Item`
  (`selection` + `route`), the worker passes `AssistantClient.Routing.of(item)` and never routes,
  and `sendToProvider` refuses an unrouted Auto. Routed turns never `recordRecent`, and Auto never
  becomes a provider default. `AutoPermissions`: ChatGPT/Local on, Anthropic/xAI/OpenRouter off by
  default; only the user's switch writes them, `SecureStore.clear*Key` revokes, and the metered
  opt-ins are not in backup. `globalDefault` stays explicit; `newChatSelection` adds the optional Auto default.
  Titles, summaries and Smart Vault keep their fixed selections. See `docs/SMART_ROUTING.md`.
- **OpenRouter (0.8.3.0-beta.6+)** — a real provider (`OpenRouterProvider`) on the same
  `ApiKeyProviderClient`/`ProviderRequestMapper` path. `OpenRouterAuth` is the browser OAuth PKCE
  sign-in (S256, `http://localhost:<ephemeral>/orbit/openrouter/<state>` loopback callback, state in
  the path because OpenRouter documents no `state` param, verifier in memory only, code claimed
  once); its key and a typed key share one `SecureStore` slot, with a non-secret source
  (`oauth`/`manual`; none recorded = a pre-beta.6 manual key). The catalog (`/models/user`, else
  `/models`) is filtered to text-in/text-out chat models, excludes `openrouter/*` except
  `openrouter/auto` (never Fusion) and `:batch`, caps at 800, and is cached in the no-backup dir;
  strengths come only from `reasoning.supported_efforts`. Direct and OpenRouter routes are distinct
  (provider + slug everywhere). OpenRouter Auto is an explicit model; `ResponseDetails.servedBy`
  records its reported downstream model. `OrbitModelCatalog.isDynamicProvider` replaces
  hard-coded Anthropic/xAI checks.
- **Conversation titles (0.8.3.0-beta.2+)** — `ConversationStore` owns durable default/automatic/
  manual/legacy title state and the compare-and-set job token. `ConversationTitleManager` schedules
  one invisible WorkManager job after the first persisted successful exchange;
  `ConversationTitlePolicy` fixes ChatGPT metadata work to GPT-5.6 Luna Low and supplies the local
  fallback. Never put title prompts/results in chat history or let an async result beat Rename.
- **Rendered-result follow-ups (0.8.3.0-beta.2+)** — `RenderedResultContext` exposes only the exact
  count of persisted rich-image cards from the immediately preceding assistant turn. It is bounded
  untrusted data, not UI markup or an instruction; keep it out of older turns and visible history.
- **Conversation Control (0.8.3.0-beta.3+)** — `ConversationStore.messages` is always the *active
  path*; every request, renderer and search reads only it, so hidden branches can never leak into a
  request. Alternatives live in `forks` (`ConversationBranches`): a fork at position N holds whole
  alternative *tails* (messages from N on, plus forks inside them), the visible one is a `null`
  slot, and each fork carries its parent message's fingerprint so a stale fork is dropped, never
  grafted. Change the path only through `branchFromUserMessage` (Edit), `commitAnswerVariant`
  (Retry, committed only on success or a stopped partial) and `selectVariant`; `save()` refuses a
  divergent copy of a branched chat. Every `new Conversation(...)` must carry `forks` and `kept`.
  Retry requests carry `PendingRequestStore.Item.variantTarget` and are built from the path before
  the retried answer. `ActionResultStore` cards bind to their answer's fingerprint before a path
  first changes. Kept context (`KeptContext`) is chat-level, stored once, injected by the request
  builder, never drawn under later messages; screen, photos and notifications are never keepable.
  The context meter (`ContextEstimate`/`ContextLedger`) runs the real `ChatGptClient.requestBody`
  in measuring mode; never add a parallel model of what a request contains. Continue in new chat
  (`ContinueChat`) uses the fixed GPT-5.6 Luna Low summary policy and never touches the original.
  Backups walk branch messages too (`OrbitBackupManager.allMessages`).
- **Context** — `ScreenContextExtractor`, `ScreenContextClassifier`, `ScreenActionSuggester`,
  `ScreenSelection*` (crop/markup editor), `AttachmentStore`/`AttachmentLoader`/`AttachmentBridge`.
- **Actions & automation** — `OrbitActionEngine`, `DeviceActionExecutor` (timers, alarms, brightness,
  media volume, DND, flashlight, dial/SMS, navigate, share, copy, URLs), `ReversibleActionHelper`,
  `RoutineStore`/`RoutineEditorActivity`/`RoutineActionCatalog`/`RoutineConditionEvaluator`,
  routine triggers (time + location), `CustomCommand*`, `QuickSettingsTiles` + tile services,
  `OrbitWidgets` + three widget providers with a headless `OrbitWidgetActionReceiver` path.
- **Extensions** — `OrbitExtension` (v1), `OrbitExtensionV2` (schema v2: setup fields, headers,
  placeholders), `OrbitExtensionStore`, `OrbitExtensionSecretStore` (Android Keystore),
  `OrbitExtensionActionExecutor`, `ExtensionsActivity`. Declarative only: no executable code, HTTPS
  public endpoints only, DNS-validated, no redirects, bounded sizes. Secrets never enter backups.
- **Personal context** — `MemoryStore`, `AppProfileStore`, `NotificationStore` +
  `OrbitNotificationListenerService`, `ReminderStore`/`ReminderScheduler`, `SavedPlaceStore`,
  `WeatherService` (Open-Meteo, native in-chat weather with unit preference).
- **Voice** — `VoiceInputController` (pause-friendly Voice Beta, partial transcripts, TTS replies)
  used by the Activity composers; the overlay has its own equivalent path in `OrbitSession`.
- **UI system** — `UiKit` is the shared design system: accent resolution (`accent()`,
  `accentForName()`, `onAccent()`), AMOLED handling, fonts, chat text size, cards, buttons, Orbit
  menus/dialogs. `OrbitMarkdown` + `OrbitRichResponseRenderer` render replies in both surfaces.
  `Prefs` holds every preference key; `SecureStore` holds credentials.

## UI and product conventions

- Preserve Orbit's existing visual identity. No redesigns unless explicitly requested.
- All new UI goes through `UiKit` — never stock Android dialogs, menus, or spinners.
- Accent, AMOLED, font, and chat-text-size changes must propagate live to visible UI. `SettingsActivity`
  does this with an appearance signature + in-place rebuild (`refreshAppearanceIfNeeded`) that
  preserves scroll position; follow that pattern rather than calling `recreate()`.
- Never ship a control that appears functional but does nothing.
- Never remove working functionality because a replacement is easier.
- Destructive actions get restrained destructive styling and explicit confirmation.

## Git workflow

- Single branch `main`, tracking `origin/main` at `https://github.com/lpnovi/Orbit-Assistant.git`.
- Every release commit is tagged `v<versionName>`; pushing the tag triggers the signed release build.
- Do not push, merge, force-push, rewrite history, or delete branches unless explicitly told to.
- Commit a build as a checkpoint when the user confirms it is good on their physical device.

## Testing and reporting

There is no emulator in this environment. Verification is: build succeeds, unit tests pass, then the
user installs the debug APK on a physical Galaxy S25 Ultra.

After each piece of work, report in this order:

1. What was wrong
2. What changed (product level, not code level)
3. Whether the build succeeded
4. Resulting Orbit version
5. Where the APK is
6. Exactly what to test on the phone

## Communication style

The user is not a programmer and directs Orbit at a product level. Explain in practical product
terms; keep code-level detail out unless asked. Screenshots, videos, and behavior descriptions from
the physical phone are testing evidence — treat them as authoritative about real device behavior.
When a bug is reported, trace and fix the root cause rather than masking the symptom, then check
related code for regressions. Avoid broad refactors and duplicate systems; extend what exists.
