# Privacy & trust notes

These notes describe the current behavior of Orbit Assistant based on the public source and release system. They are intended to make technical data boundaries understandable; they are not an attorney-reviewed privacy policy.

## The short version

Orbit stores its working data locally, asks the user to enable optional Android capabilities, and sends an AI request only to the provider the user selected. Context attached to a cloud request can leave the device. Orbit Local generation stays on the device. Backups can contain personal data and are not encrypted.

## Screen context

Orbit can receive screen text and screenshots through its Android assistant session and explicit attachment flows. These controls are separate:

- global screen-text and screenshot settings;
- whether the current screen is attached by default;
- per-app privacy level;
- per-app screen behavior: use the global setting, attach by default, or never use the screen;
- per-app screenshot behavior: use the global setting, allow, or block.

Apps recognized or marked as sensitive do not attach screen context automatically and do not provide screenshots. A profile can disable screen access entirely. Screen Selection sends only the region the user chooses as the visual attachment, although any separately enabled screen-text context follows its own setting.

Screen context is part of an active assistant or attachment workflow, not a general-purpose continuous screen recording feature. Review the context chip or attachment before sending whenever the screen contains private material.

## AI providers and network requests

The selected provider determines where a request is processed and which capabilities are available.

| Provider | Current boundary |
| --- | --- |
| **ChatGPT account mode** | Requests and selected context are sent through the connected ChatGPT account path. This is currently Orbit's fullest feature set. |
| **Orbit Local** | Text generation runs through the separately installed Orbit Local component on the device. It requires no account and works offline after the model is installed. It never silently falls back to cloud processing. The compact model has fewer capabilities than cloud mode. |
| **Private HTTPS relay** | Requests go to the HTTPS relay address configured by the user. The relay operator controls any onward provider processing and retention. Provider API credentials belong on that server, not in the Orbit APK. |
| **OpenRouter** | Setup groundwork is visible, but the provider is not selectable and OpenRouter chat is not currently available. |

Third-party provider terms and privacy practices apply to data sent to that provider. Orbit does not automatically switch a deliberately selected provider to another provider merely because a request fails.

## Credentials and secrets

- ChatGPT account tokens are encrypted with an Android Keystore-backed key.
- The setup-only OpenRouter key is Keystore-encrypted with no plaintext fallback.
- Extension `secret` and `secret_url` fields are encrypted with Android Keystore-backed AES/GCM and fail closed if secure storage is unavailable.
- The optional private-relay access token prefers Keystore-backed encryption. Current compatibility behavior can fall back to Orbit's app-private preferences if Keystore persistence fails. Do not reuse a high-value provider key as this relay token; keep the provider key on the relay server.
- Credentials and Extension secrets are intentionally excluded from Orbit Backup & Restore.

No signing key or release credential is stored in the repository or included in public APK source assets.

## Local data

Depending on enabled features, Orbit's app-private storage can contain conversation history, attachments retained with history, Memory entries, reminders, saved places, Routines, Custom Commands, app profiles, notification configuration/history, Extensions, and preferences.

Controls for history, Memory, notification access and retention, app profiles, attachments, and other capabilities are available in Orbit and Android Settings. Removing Android permission prevents future access through that permission; it does not necessarily erase data already saved by a feature, so use the matching Orbit management screen when you also want stored data removed.

## Notifications, calendar, and location

- Notification Intelligence requires Android notification-listener access. Its retained history and per-app exclusions are managed locally. Relevant notification context may be included in a provider request when that feature is used.
- Calendar writing requires Android Calendar permission and a confirmation that names the destination calendar. Orbit checks the result rather than treating an intent launch as proof that an event was added.
- Weather, Saved Places, and location-triggered Routines use Android location access only when their related options are configured. Background location is required for location triggers that must work while Orbit is closed.

## Attachments and documents

Files, images, selected text, shared content, and PDF pages enter Orbit through explicit pickers, shares, selections, or attachment controls. A cloud provider receives the attachment data needed for a request only when the request is sent and only if that provider supports the content. Orbit Local does not provide cloud image processing.

Document viewing and PDF text search run locally. **Ask Orbit about this page** attaches page context to the conversation; the chosen provider boundary then applies.

## Backups

Orbit Backup & Restore uses Android's system file picker. Orbit writes the backup to the location the user selects and does not upload it to an Orbit-operated backup service.

Backups can include chats, retained conversation images, Memory, Routines and triggers, safe Extension manifests and non-secret setup, Custom Commands, reminders, saved places, app profiles, notification configuration, and personalization. Credentials, Extension secrets, Android permission grants, default-assistant status, and Beta-channel enrollment are excluded. The user's Deck layout is not currently part of the portable backup.

**Backup files are not encrypted.** Anyone who can read the file may be able to read personal content inside it. Store it privately and inspect it before sharing.

## Extensions

Orbit Extensions are declarative JSON data, not executable plugins. They cannot load Java, Kotlin, JavaScript, shell commands, APKs, reflection targets, arbitrary Android intents, local files, or an API for Orbit conversations, Memory, notifications, location, attachments, or account data.

HTTPS Extension actions are bounded and restricted to validated public endpoints. Redirects and local/private-network destinations are blocked. Review every Extension's requested endpoint, setup fields, and action before installing it. See [EXTENSIONS.md](EXTENSIONS.md) for the complete format and security limits.

## Updates and official builds

Orbit checks public release data from `lpnovi/Orbit-Assistant`. Stable follows normal GitHub releases. Beta can also consider official prereleases. Orbit validates release labeling, the update manifest, asset location, package name, Android version code, APK SHA-256, and the permanent signing certificate.

An update is not downloaded without user approval, and Orbit does not silently install it. After verification, Android's normal package installer owns the final confirmation.

Official release pages also include standalone SHA-256 files so a downloaded APK can be checked independently.

## Reporting a privacy or security concern

GitHub Issues are public. Do not include tokens, account identifiers, private screen content, notification text, backup files, or unredacted diagnostics in an Issue.

The repository owner still needs to choose and publish a private security-reporting route before a broad public launch. Until that policy exists, use a public Issue only for a sanitized product-level report that contains no sensitive details.
