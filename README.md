<div align="center">

# Orbit Assistant

**Your Android assistant, one gesture away, aware of the screen you choose, and ready to act.**

Orbit brings system assistant access, screen context, safe device actions, automation, and a full conversation app into one deeply customizable Android experience.

[![Download Stable](https://img.shields.io/badge/Download-Stable-7457E8?style=for-the-badge&logo=android&logoColor=white)](https://github.com/lpnovi/Orbit-Assistant/releases/latest)
[![Try Beta](https://img.shields.io/badge/Try-Beta-29233B?style=for-the-badge&logo=github&logoColor=white)](https://github.com/lpnovi/Orbit-Assistant/releases?q=prerelease%3Atrue)

**Android 10+** · Free and open source (MPL-2.0) · Install from GitHub · Independent project

Stable is recommended for most people. Beta offers newer features with a greater chance of bugs.

</div>

## See Orbit in action

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/assets/screenshots/overlay.jpg" width="185" alt="Orbit Assistant Side-button overlay over a neutral sunset background"><br><sub><strong>Orbit over any app</strong><br>Side-button overlay for quick help without leaving what you're doing.</sub></td>
    <td align="center" width="33%"><img src="docs/assets/screenshots/chat.jpg" width="185" alt="Orbit Assistant full chat showing a response about phone actions"><br><sub><strong>Full conversations</strong><br>Ask questions, work with content, and use Android actions in a persistent chat.</sub></td>
    <td align="center" width="33%"><img src="docs/assets/screenshots/deck.jpg" width="185" alt="Orbit Deck with shortcut tiles for a new chat, Routines, Reminders, Memories, Flashlight, media Play/Pause, and Capabilities"><br><sub><strong>Your command center</strong><br>Shortcuts, Routines, reminders, apps, prompts, media controls, and safe actions.</sub></td>
  </tr>
</table>

<table>
  <tr>
    <td align="center" width="50%"><img src="docs/assets/screenshots/documents.png" width="185" alt="Orbit Documents viewing a fictional test PDF with Ask Orbit"><br><sub><strong>Work with documents</strong><br>Read PDFs natively and ask Orbit about the current page.</sub></td>
    <td align="center" width="50%"><img src="docs/assets/screenshots/theme-studio.jpg" width="185" alt="Orbit Theme Studio showing a customized live preview and color controls"><br><sub><strong>Make Orbit yours</strong><br>Customize Orbit's colors and presentation with a live preview.</sub></td>
  </tr>
</table>

## Why Orbit?

Opening a chatbot is useful. Orbit is designed for the moments when leaving what you are doing breaks the flow.

Set Orbit as Android's digital assistant and call it from the system assistant gesture or a mapped Side button. It can appear over the current app, work with screen context you allow, and hand safe actions to Android. When you want more room, the same assistant continues in a full companion chat with history, attachments, settings, and automation.

Orbit is not tied to one interaction style: type, speak, attach content, build reusable Routines, or keep selected tools one tap away in Orbit Deck.

In short:

- **It lives where you already are.** Call it over any app instead of switching to a chatbot and pasting things in.
- **You decide what it sees.** The current screen is offered, not sent. You choose when to attach it, and apps marked sensitive never attach it automatically.
- **It can do things, not just answer.** Timers, alarms, reminders, calendar events, media, flashlight, and more run through Android with confirmation where it matters.
- **It is yours to shape.** Themes, Deck shortcuts, Routines, Custom Commands, and Extensions let you build the assistant you want.
- **It is free and open source.** No account with Orbit, no ads, and the full source is here under MPL-2.0.

## What Orbit can do

| | |
| --- | --- |
| **Assistant everywhere** | Invoke an overlay through Android's digital-assistant flow, continue in full chat, type or use Voice Beta, and optionally attach screen text or a screenshot. Side-button behavior depends on the phone maker and system configuration. |
| **Understand and work with content** | Attach images, files, clipboard text, and PDFs; share supported content to Orbit from other apps; read PDFs in Documents; ask about a selected page; and view rich Markdown responses. Image understanding depends on the active provider. |
| **Act on Android** | Use supported actions for timers, alarms, media, brightness, flashlight, Do Not Disturb, navigation, sharing, and more. Calendar writing uses Android's Calendar Provider and asks for confirmation before changes. Capabilities remain subject to Android permissions and device support. |
| **Make Orbit yours** | Arrange shortcuts, Routines, apps, prompts, and safe actions in Orbit Deck. Customize accents, conversation colors, fonts, AMOLED surfaces, haptics, app profiles, Memory, and other preferences. Design the whole look in Theme Studio, with a live preview, saved presets, and portable Orbit theme files. |
| **Automate repeatable work** | Build Routines with reusable steps, conditions and branches; add time or location triggers; start from templates; use Custom Commands; and launch compatible actions from Quick Settings or home-screen widgets. Declarative Extensions can add reviewed Routine actions without loading executable plugin code. |
| **Choose how Orbit thinks** | Use ChatGPT account mode for Orbit's fullest cloud feature set, install the optional Orbit Local component for private offline chat on supported hardware, or connect an advanced private HTTPS relay. OpenRouter configuration is a preview only. OpenRouter chat is not currently available. |

Advanced details are available in the [Extensions guide](docs/EXTENSIONS.md), [Orbit Local model guide](docs/LOCAL_MODELS.md), [changelog](CHANGELOG.md), and [roadmap](ROADMAP.md).

## Stable or Beta?

| Channel | Best for | What to expect |
| --- | --- | --- |
| **Stable** | Most users | Device-tested public releases and the recommended starting point. |
| **Beta** | Early adopters and testers | New features sooner, with possible rough edges or regressions. |

- **[Download Stable](https://github.com/lpnovi/Orbit-Assistant/releases/latest)** always opens GitHub's latest non-prerelease.
- **[Try the Beta](https://github.com/lpnovi/Orbit-Assistant/releases?q=prerelease%3Atrue)** searches the repository's published prereleases, so the link remains useful as new Betas replace old ones.

Orbit's in-app updater follows the channel you choose. Stable checks only normal releases. Beta considers official prereleases and newer Stable builds, then offers the eligible build with the highest Android version code. Changing channels does not weaken package, checksum, or signing verification.

## Install Orbit

Orbit is currently distributed as an APK from this GitHub repository. This is normal Android sideloading; there is no need to disable device security broadly.

1. Open **[Download Stable](https://github.com/lpnovi/Orbit-Assistant/releases/latest)** on your phone, or **[Try the Beta](https://github.com/lpnovi/Orbit-Assistant/releases?q=prerelease%3Atrue)** if you intentionally want prerelease software.
2. Scroll to **Assets** (tap it to expand if needed) and download `Orbit-Assistant-v<version>.apk` (for example, `Orbit-Assistant-v0.8.0.0.apk`).
   - You do not need the `.sha256` files unless you want to verify the download.
   - You do not need `Orbit-Local-v*.apk` unless you want optional offline chat. It is a separate component, and cloud-powered Orbit works without it.
3. Open the APK from your browser's downloads or your file manager.
4. If Android asks, allow that specific browser or file manager to install unknown apps, then return to the installer. You can turn that permission off again afterward.
5. Review Android's installation screen, install Orbit, and complete onboarding.

Because Orbit is not distributed through Google Play, Android or Google Play Protect may warn that the app is unfamiliar. Only continue if the file came from this repository's Releases page.

Official release assets come from `lpnovi/Orbit-Assistant`, use Orbit's permanent Android signing identity, and include SHA-256 checksum files. You can compare a downloaded APK with its adjacent `.sha256` asset before installing:

```powershell
Get-FileHash -Algorithm SHA256 .\Orbit-Assistant-vX.Y.Z.apk
```

The value should match the release's `Orbit-Assistant-vX.Y.Z.apk.sha256` file exactly. Orbit's updater separately verifies the official manifest, package name, version code, APK checksum, and signing certificate before handing an approved download to Android's normal installer. It does not silently install updates.

## Your first five minutes

1. **Open Orbit and finish onboarding.** You can revisit setup later, and you can restore an Orbit backup there if you have one.
2. **Connect an AI provider.** **Sign in with ChatGPT** is the recommended full-featured path. Your browser handles the sign-in, and Orbit never asks for your ChatGPT password. Under **More provider options**, Orbit Local and a private relay are optional alternatives.
3. **Choose capabilities deliberately.** Grant only the Android permissions and special access you want Orbit to use. Everything can be changed later in Settings.
4. **Optional: make Orbit your digital assistant.** Onboarding opens Android's assistant settings, where you select Orbit as the digital assistant app. This enables the system assistant gesture.
5. **Optional: map the Side button.** On many Samsung Galaxy phones, go to **Settings > Advanced features > Side button** and set press and hold to **Digital assistant**. Menu names and availability vary by model and One UI version, and other manufacturers may not offer this at all.
6. **Try a real task.** Open an article, call Orbit, tap **Use screen**, and ask for a summary. Or attach a PDF and ask about a page, or create a simple reminder.

No assistant button on your phone? Orbit still works from its app icon, the **Ask Orbit** Quick Settings tile, and home-screen widgets.

## Compatibility

| Area | Current support |
| --- | --- |
| **Android version** | Android 10 (API 29) or newer. |
| **Main Orbit app** | The APK contains no native ABI-specific libraries and is designed for Android phones and responsive tablet layouts. |
| **Orbit Local** | The optional companion APK currently packages `arm64-v8a` native libraries and performs additional on-device capability checks before offering model use. |
| **Assistant invocation** | Requires Orbit to be selected as Android's digital assistant. Gesture and button mapping vary by Android version, manufacturer, launcher, and device policy. Samsung Side-button support is an important tested path, not a promise that every phone exposes the same setting. |
| **Device testing** | Stable releases are primarily validated on a Galaxy S25 Ultra. Responsive phone/tablet behavior is implemented and tested in code, but OEM-specific behavior can differ and should be reported. |
| **Root** | Not required. Orbit uses standard Android permissions and settings. |

## Privacy and trust

**[Orbit Assistant Privacy Policy](docs/PRIVACY_POLICY.md)**: what Orbit stores, what it sends and to whom, and how to delete it. It covers both the Google Play and GitHub editions.

Orbit exposes powerful context and device capabilities, so control is part of the product, not an afterthought.

- By default, Orbit offers the current screen but attaches it only when you tap **Use screen** or **Select area**. Screen text, screenshots, and automatic attachment behavior are configurable globally and per app. Sensitive app profiles disable automatic context, and an app can be set to never provide screen access.
- Android permissions and special access are requested for specific capabilities. You can leave capabilities disabled and manage access through Orbit or Android Settings.
- Cloud requests go to the provider you explicitly choose. Orbit Local generation stays on the device and does not silently fall back to a cloud provider.
- ChatGPT credentials, OpenRouter's setup-only key, and Extension secrets use Android Keystore-backed encryption. Credentials and Extension secrets are excluded from Orbit backups. The optional private-relay access token has a documented compatibility caveat in the detailed privacy notes.
- Orbit backups are created only through Android's file picker. They can contain personal data and are **not encrypted**, so keep them in a private location.
- The GitHub edition's updater reads public GitHub release data and never downloads without approval or installs without Android's installer confirmation. The Google Play edition is updated by Google Play only.
- Orbit Extensions are declarative data. They cannot load executable code, APKs, scripts, arbitrary intents, local files, or Orbit's personal/context data.

For more technical detail, the [Privacy & trust notes](docs/PRIVACY.md) describe the data paths, provider boundaries, backup behavior, and current limitations. The [Privacy Policy](docs/PRIVACY_POLICY.md) is the user-facing policy. To report a security problem, see the [Security policy](SECURITY.md).

## Official downloads and release integrity

Treat only releases published at **[github.com/lpnovi/Orbit-Assistant](https://github.com/lpnovi/Orbit-Assistant/releases)** as official.

Every official release workflow verifies the tagged source version, both Orbit APK identities, the shared signing certificate, release manifest, and checksums before publication. The updater accepts only correctly labeled Stable/Beta releases with matching official assets and then lets Android perform the final installation.

Never post signing keys, account tokens, diagnostic data containing private content, or backup files in a public Issue.

## Development

| Status | Examples |
| --- | --- |
| **Shipping in Stable** | Side-button/default-assistant access, full chat, attachments, screen context, Voice Beta, Android actions, Calendar writing, Routines, Extensions, widgets, Orbit Local, Orbit Deck with large tiles, sections and folders, Documents, Theme Studio with Solid, Frosted and Liquid surface materials, Orbit Vault / Quick Capture, Rich Answers with sourced web images inside answers, Settings search, and optional GPT-6 Astra for eligible ChatGPT accounts. |
| **In Beta** | **Smart Vault**, free and optional, on the journey to Orbit 0.9: ranked Vault search, search by meaning with an on-device model, text read from saved pictures, suggested titles, summaries and topics, topic filters, related items, and Ask Vault. Every part is off until you turn it on, and nothing leaves your phone unless you choose it. |
| **In development** | Orbit Pro, an optional paid layer of advanced customization and power-user extras built on top of free Orbit. Its first features, advanced Theme Studio styling and saved Deck layouts, are tested in Beta builds only; Stable builds never unlock them. Nothing that is free today moves behind it, and there is no checkout, no billing, and nothing to buy yet. |
| **Planned or under consideration** | Vault Pro organization on top of the free Vault, Hybrid Auto routing, deeper local capabilities, and other work listed in the roadmap. OpenRouter chat remains deferred until it can be properly validated. |

See [ROADMAP.md](ROADMAP.md) for the detailed development record and future direction, or [CHANGELOG.md](CHANGELOG.md) for shipped release history. Roadmap items are direction, not promised dates.

### How Orbit is built

Orbit is an independent, open-source project developed with substantial help from AI coding tools. Changes are reviewed in the repository, exercised through the project's automated tests, and validated on a real phone before Stable promotion. No AI provider, model company, or device manufacturer owns, sponsors, endorses, or officially supports Orbit.

Want to build it yourself? [Building from source](docs/BUILDING.md) produces a debug APK without any private signing material. Official signing material is intentionally not part of the repository.

## Support and feedback

- **Found a bug or unexpected behavior?** [Open a bug report](https://github.com/lpnovi/Orbit-Assistant/issues/new?template=bug_report.yml).
- **Have an idea?** [Open a feature request](https://github.com/lpnovi/Orbit-Assistant/issues/new?template=feature_request.yml).
- **Found a security problem?** Follow the [Security policy](SECURITY.md) instead of posting details publicly.
- **Need to compare releases?** Read the [changelog](CHANGELOG.md) and [GitHub Releases](https://github.com/lpnovi/Orbit-Assistant/releases).

GitHub Issues are public. Remove or crop personal data before posting: account details, tokens, notifications, private screen content, and anything personal in screenshots. Never attach Orbit backup files or unredacted Diagnostics output.

## Contributing

Ideas, bug reports, and documentation fixes are welcome. For code or larger documentation changes, please open an Issue first so scope can be discussed before a pull request. See [CONTRIBUTING.md](CONTRIBUTING.md) for the short version.

## License

Orbit Assistant is licensed under the [Mozilla Public License 2.0](LICENSE).

Product and service names belong to their respective owners. Orbit Assistant is not an official ChatGPT, OpenAI, Samsung, Google, or Android application.
