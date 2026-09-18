# Security policy

Orbit Assistant is an independent open-source project. Security reports are welcome and are handled on a best-effort basis. There is no bug bounty.

## Supported versions

Security fixes target the latest Stable release and, when relevant, the current Beta. Older versions are not patched separately; update through Orbit's in-app updater or the [Releases page](https://github.com/lpnovi/Orbit-Assistant/releases/latest).

## Reporting a vulnerability

Please do not post exploit details, tokens, private screen content, or backup files in a public Issue.

1. If the repository's **Security** tab shows a **Report a vulnerability** button, use it. That creates a private report visible only to the maintainer.
2. If that option is not available, open a short public Issue titled "Security contact request" that says only which area is affected (for example, updates, Extensions, or credentials), with no technical details. The maintainer will arrange a private way to share the rest.

Helpful details for the private report:

- Orbit version and update channel (Stable, Beta, or source build)
- Device model and Android version
- What an attacker could do, and the steps to reproduce it
- Whether the issue needs another app, a malicious Extension, network access, or physical access to the phone

## Scope

In scope: the Orbit Assistant and Orbit Local apps, the in-app updater and its verification, credential and Extension secret storage, Extension validation, backup and restore, and the release workflows in this repository.

Out of scope: vulnerabilities in third-party AI providers, Android itself, or device firmware; a relay server you operate yourself; and reports that require an already rooted or compromised phone.

## What Orbit already does

These are descriptions of current behavior, not certifications. See [Privacy & trust notes](docs/PRIVACY.md) for detail.

- Official APKs are signed with one permanent release key that is not stored in this repository.
- The updater checks the release manifest, package name, version code, APK SHA-256, and signing certificate before handing a download to Android's installer, and never installs silently.
- ChatGPT credentials and Extension secrets use Android Keystore-backed encryption and are excluded from backups.
- Extensions are declarative data with HTTPS-only, validated public endpoints and no executable code.
