# Privacy & trust notes

These notes describe the current behavior of Orbit Assistant based on the public source and release system. They are intended to make technical data boundaries understandable; they are not an attorney-reviewed privacy policy. The user-facing policy, which is also the Google Play privacy policy, is the [Orbit Assistant Privacy Policy](PRIVACY_POLICY.md).

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

Out of the box, attaching the current screen by default is off: the overlay offers the screen, and it is attached only when the user taps **Use screen** or **Select area**. The user can turn on attach-by-default globally or for specific apps.

Screen context is part of an active assistant or attachment workflow, not a general-purpose continuous screen recording feature. Review the context chip or attachment before sending whenever the screen contains private material.

## AI providers and network requests

The selected provider determines where a request is processed and which capabilities are available.

| Provider | Current boundary |
| --- | --- |
| **ChatGPT account mode** | Requests and selected context are sent through the connected ChatGPT account path. This is currently Orbit's fullest feature set. |
| **Anthropic Claude** | Requests and selected context are sent to Anthropic's official Messages API with the developer API key the user saved in Orbit. |
| **xAI / Grok** | Requests and selected context are sent to xAI's official inference API with the developer API key the user saved in Orbit. |
| **Orbit Local** (GitHub edition only) | Text generation runs through the separately installed Orbit Local component on the device. It requires no account and works offline after the model is installed. It never silently falls back to cloud processing. From v0.8.2.0 the context Orbit prepares for any request - Orbit Memory, recent conversation, allowed screen text, the text of attachments (including text Orbit recognised in pictures), Ask Vault passages, and Notification Intelligence history for notification questions - is given to the local model on the device, within a bounded budget and marked as untrusted data. None of it leaves the phone for a local request. Diagnostics records only which kinds of context were used and how much, never their content. The compact model has fewer capabilities than cloud mode and cannot see images. |
| **Private HTTPS relay** | Requests go to the HTTPS relay address configured by the user. The relay operator controls any onward provider processing and retention. Provider API credentials belong on that server, not in the Orbit APK. |
| **OpenRouter** | Setup groundwork is visible, but the provider is not selectable and OpenRouter chat is not currently available. |

Third-party provider terms and privacy practices apply to data sent to that provider. Orbit does not automatically switch a deliberately selected provider to another provider merely because a request fails.

**Auto (from v0.8.3.0-beta.5).** When a chat is set to Auto, Orbit chooses one provider and model for each request on the phone, from the request's size, attachments and the models' capabilities. The message is never sent to any AI to make that choice, and it goes only to the one provider Auto picks. Auto may use only providers switched on in *Settings > Intelligence > Auto can use*: ChatGPT and Orbit Local by default. Anthropic and xAI are off until the user turns them on, because they bill the user's own API key; a saved key does not turn them on, removing a key turns them off again, and their Auto opt-in is never restored from a backup. Response details show which model Auto chose and a short reason.

## Smart Vault

Smart Vault (Stable from v0.8.1.0, first in v0.8.1.0-beta.1) is off until the user turns it on, and each network-facing part is its own switch. Local indexing, meaning search and text recognition run on the device. The meaning model (Model2Vec `potion-base-8M`, MIT) is downloaded once from Hugging Face at a pinned revision and rejected unless its SHA-256 matches the value compiled into Orbit. Text recognition uses Google ML Kit through Google Play services. "Read saved links" reuses the Rich Answers page fetcher with its public-host checks, redirect revalidation, size ceiling and cookie-free requests. AI suggestions send one item at a time to the active provider through a request with no history, screen, memory or tools, and treat the item's content as untrusted data. Existing items are sent only after an explicit, counted confirmation. Ask Vault never sends anything by itself: it stages one attachment in the normal composer.

Smart Vault's index (recognised text, page text, meaning vectors and job state) is a separate database that can be deleted and rebuilt at any time and is not included in backups. Suggestions, topics and screen text captured with a full-screen Screen Selection save are part of the Vault item and travel with it.

## Credentials and secrets

- ChatGPT account tokens are encrypted with an Android Keystore-backed key.
- ChatGPT sign-in (from v0.8.3.0-beta.1) uses OAuth with PKCE in the system browser. For the length of one sign-in only, Orbit listens on `127.0.0.1` (port 1455, or 1457 if busy) for OpenAI's redirect, checks that it carries the exact random state Orbit created, exchanges the code, and closes the listener. Nothing off the phone can reach that address. The PKCE verifier is kept only in memory. The one-time-code sign-in remains available as a fallback, and Orbit never switches between the two on its own.
- The provider, model and strength a request is sent with are chosen by the user per chat. Orbit does not route a request to a different model, and when an account cannot use a model Orbit says so rather than answering with another one. The one exception is a chat the user sets to Auto, where Orbit chooses among the providers the user allowed, before sending, and records the choice.
- Anthropic and xAI developer API keys are encrypted with provider-specific Android Keystore keys, with no plaintext fallback. They are never redisplayed after saving, placed in model metadata, sent to another provider, logged, diagnosed or backed up.
- The setup-only OpenRouter key is Keystore-encrypted with no plaintext fallback.
- Extension `secret` and `secret_url` fields are encrypted with Android Keystore-backed AES/GCM and fail closed if secure storage is unavailable.
- The optional private-relay access token prefers Keystore-backed encryption. Current compatibility behavior can fall back to Orbit's app-private preferences if Keystore persistence fails. Do not reuse a high-value provider key as this relay token; keep the provider key on the relay server.
- Credentials and Extension secrets are intentionally excluded from Orbit Backup & Restore.

No signing key or release credential is stored in the repository or included in public APK source assets.

## Local data

Depending on enabled features, Orbit's app-private storage can contain conversation history, attachments retained with history, Memory entries, reminders, saved places, Routines, Custom Commands, app profiles, notification configuration/history, Orbit Vault items and the pictures saved with them, Extensions, and preferences.

Controls for history, Memory, notification access and retention, app profiles, attachments, and other capabilities are available in Orbit and Android Settings. Removing Android permission prevents future access through that permission; it does not necessarily erase data already saved by a feature, so use the matching Orbit management screen when you also want stored data removed.

## Notifications, calendar, and location

- Notification Intelligence requires Android notification-listener access. Its retained history and per-app exclusions are managed locally. Relevant notification context may be included in a provider request when that feature is used.
- Calendar writing requires Android Calendar permission and a confirmation that names the destination calendar. Orbit checks the result rather than treating an intent launch as proof that an event was added.
- Weather, Saved Places, and location-triggered Routines use Android location access only when their related options are configured. Background location is required for location triggers that must work while Orbit is closed.
- The Google Play edition does not request background location. Location-triggered Routines are not available there; weather, Saved Places, and Routine location conditions use location only while Orbit is in use.

## Attachments and documents

Files, images, selected text, shared content, and PDF pages enter Orbit through explicit pickers, shares, selections, or attachment controls. A cloud provider receives the attachment data needed for a request only when the request is sent and only if that provider supports the content. Orbit Local does not provide cloud image processing.

Document viewing and PDF text search run locally. **Ask Orbit about this page** attaches page context to the conversation; the chosen provider boundary then applies.

**Keep in this chat.** An attached document, text file, clipboard text or Vault item can be kept for a whole chat. Orbit stores one bounded copy of its text with that chat and sends it with every later request in the chat until the user removes it from the chat's kept list; it is not repeated under later messages. The live screen, screen selections, photos, notifications and device state are never kept. **Continue in new chat** sends the visible conversation text (not Orbit's instructions, memories or hidden branches) to GPT-5.6 Luna at Low strength through the user's ChatGPT sign-in to write a short summary, which becomes the new chat's kept context.

**Branches and answer versions.** Editing an earlier message and retrying an answer keep the earlier versions in that chat. Only the visible branch is ever sent with a request. Deleting a chat deletes all of its branches and the files they reference.

## Backups

Orbit Backup & Restore uses Android's system file picker. Orbit writes the backup to the location the user selects and does not upload it to an Orbit-operated backup service.

Backups can include chats, including every branch and answer version and any context kept in a chat, retained conversation images, Memory, Orbit Vault items and their saved pictures, Routines and triggers, safe Extension manifests and non-secret setup, Custom Commands, reminders, saved places, app profiles, notification configuration, personalization, Model Library Favorites, Recents and provider defaults. Dynamic provider catalog caches are rebuilt and are not backed up. Credentials, Extension secrets, Android permission grants, default-assistant status, and Beta-channel enrollment are excluded. The user's Deck layout is not currently part of the portable backup.

**Backup files are not encrypted.** Anyone who can read the file may be able to read personal content inside it. Store it privately and inspect it before sharing.

## Extensions

Orbit Extensions are declarative JSON data, not executable plugins. They cannot load Java, Kotlin, JavaScript, shell commands, APKs, reflection targets, arbitrary Android intents, local files, or an API for Orbit conversations, Memory, notifications, location, attachments, or account data.

HTTPS Extension actions are bounded and restricted to validated public endpoints. Redirects and local/private-network destinations are blocked. Review every Extension's requested endpoint, setup fields, and action before installing it. See [EXTENSIONS.md](EXTENSIONS.md) for the complete format and security limits.

## Updates and official builds

The Google Play edition is updated by Google Play and never downloads or installs app updates itself. It still reads public release notes from the repository for **What's New**. The rest of this section describes the GitHub edition.

The GitHub edition checks public release data from `lpnovi/Orbit-Assistant`. Stable follows normal GitHub releases. Beta can also consider official prereleases. Orbit validates release labeling, the update manifest, asset location, package name, Android version code, APK SHA-256, and the permanent signing certificate.

An update is not downloaded without user approval, and Orbit does not silently install it. After verification, Android's normal package installer owns the final confirmation.

Official release pages also include standalone SHA-256 files so a downloaded APK can be checked independently.

## Reporting a privacy or security concern

GitHub Issues are public. Do not include tokens, account identifiers, private screen content, notification text, backup files, or unredacted diagnostics in an Issue.

For a suspected security vulnerability, follow the [Security policy](../SECURITY.md), which describes how to reach the maintainer without posting details publicly. Use a public Issue only for a sanitized product-level report that contains no sensitive details.
