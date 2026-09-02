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
four `ORBIT_RELEASE_*` values are present.

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
  monotonic version codes is the highest-priority constraint in this project.

## Update system

`OrbitUpdater` reads the latest GitHub Release for `lpnovi/Orbit-Assistant`, requires an
`orbit-update.json` manifest asset (generated by `release.yml`), and verifies package name,
versionCode, APK SHA-256, and certificate SHA-256 before handing the file to Android's installer.
Orbit never downloads or installs silently. `OrbitUpdateWorker` / `OrbitUpdateNotifier` handle the
optional background check, gated by the `update_notifications` preference.

## Major components

- **Overlay / assistant integration** — `OrbitVoiceInteractionService`, `OrbitSessionService`,
  `OrbitSession` (largest file; overlay UI, context bar, attachments, voice, streaming).
  `OrbitSetupHelper` handles the "Make Orbit default assistant" flow via
  `Settings.ACTION_VOICE_INPUT_SETTINGS` with fallbacks.
- **Full app** — `MainActivity` (chat list/search/tools), `ChatActivity` (full conversation),
  `SettingsActivity` (sectioned: Models & access, Voice/context/permissions, Personalization & data,
  Look & Feel, About & updates), `OnboardingActivity`, `CapabilitiesActivity`, `DiagnosticsActivity`.
- **AI pipeline** — `AssistantClient`, `ChatGptClient`/`ChatGptAuth` (device-code flow),
  `AutoRouter` (Auto/Fast/Balanced/Deep/Custom), `OrbitRequestManager` + `OrbitRequestWorker`
  (durable WorkManager background completion), `ConversationStore`, `PendingRequestStore`.
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
