# Orbit Assistant

Orbit is an Android AI assistant designed to live closer to the operating system. It combines Side-button access, screen context, voice, local device actions, Routines, and customizable automation with a full companion chat experience.

Orbit is built for Android 10 and newer. Development is active, and releases are distributed as permanently signed APKs through this repository.

## Highlights

- **System-level access:** Use Orbit as Android's default assistant from the Side button or assistant gesture, or open the full companion chat.
- **Flexible AI access:** Connect a ChatGPT account through the Codex device-code flow, or use the optional private OpenAI API relay fallback. Choose Auto, Fast, Balanced, or Deep intelligence modes.
- **Screen understanding:** Attach configurable screen text and screenshots, use one-tap full-screen context, or precisely Crop and Mark up a Screen Selection.
- **Voice Beta:** Speak requests and optionally hear responses while retaining the normal text workflow.
- **Local Android actions:** Control supported device settings and actions through Orbit's existing confirmation and capability checks.
- **Routines and automation:** Build saved action chains with IF conditions, time and location triggers, Routine Templates, Custom Commands, and configurable Quick Settings tiles.
- **Personal context:** Use Orbit Memory, local reminders, Saved Places, per-app behavior, and Notification Intelligence.
- **Personalization:** Choose live Accent colors, conversation colors, app fonts, AMOLED surfaces, haptics, and other assistant preferences.
- **Local portability:** Export and restore versioned Orbit backups through Android's system file picker.
- **Verified updates:** Check public stable releases in-app; Orbit validates the manifest, APK checksum, package, version, and permanent signing certificate before opening Android's installer.

## Installation

1. Download the latest `Orbit-Assistant-v*.apk` from [GitHub Releases](https://github.com/lpnovi/Orbit-Assistant/releases/latest).
2. Open the APK. Android may ask you to allow installs from the browser or file manager you used.
3. Review Android's installer confirmation and install Orbit.

After installation, Orbit's built-in updater can discover later stable releases. It never silently installs or automatically downloads an APK; verification completes before Android's normal installer opens.

## Getting started

1. Install Orbit and complete the first-run onboarding.
2. Connect a ChatGPT account if you want account-backed AI access, or configure your own private relay.
3. Set Orbit as Android's digital assistant to use the Side button or assistant gesture.
4. Grant only the Android capabilities you want Orbit to use.
5. Invoke Orbit over another app to use screen context, Screen Selection, voice, or local actions.

## Privacy

- Screen text and screenshot context are configurable, and screen use can be blocked per app.
- Selected-region attachments always require an explicit selection. Full-screen context follows the user's one-tap choice or existing default-attachment preference.
- API fallback credentials are not embedded in the APK. The relay API key remains server-side in the user's private relay deployment.
- ChatGPT account credentials are stored through Orbit's secure local credential store and are intentionally excluded from backups.
- Orbit backup files stay in the location selected through Android's file picker. They are not encrypted and should be stored somewhere private.
- Automatic update checks read public GitHub Release metadata. Orbit never silently downloads or installs an update.

See the in-app settings for the complete capability, history, notification, screen-context, and backup controls.

## Screenshots

Curated public screenshots will be added here later. No private device or user screenshots are included in the repository.

## Development status

Orbit is actively developed. Future areas under consideration include richer chat rendering, inline reply images, home-screen widgets, plugins and extensibility, additional AI providers, deeper Android automation, advanced Voice work, and more local intelligence. These are directions rather than fixed promises or dates.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the complete version-by-version history.

## Releases

Official stable builds and their SHA-256 checksum files are available from [GitHub Releases](https://github.com/lpnovi/Orbit-Assistant/releases). Release APKs use Orbit's permanent signing identity so Android can verify updates belong to the same application.
