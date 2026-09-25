# Orbit Assistant Privacy Policy

**Last updated: September 24, 2026**

This policy explains what Orbit Assistant ("Orbit") does with your information. It applies to both ways Orbit is distributed: the **Google Play edition** and the **GitHub edition**. Where they differ, this policy says so.

Orbit is an independent, open-source Android app published by **lpnovi**. The full source code is public at <https://github.com/lpnovi/Orbit-Assistant>, so everything described here can be checked. More technical detail is in [Privacy & trust notes](PRIVACY.md).

## The short version

- **There is no Orbit account and no Orbit server.** The developer does not operate any service that receives your conversations, screen content, files, location, or other Orbit data, and does not sell data.
- **Orbit keeps its data on your phone**, in Orbit's private app storage.
- **When you ask Orbit's AI something, that request goes to the AI provider you chose**, together with anything you chose to include, such as screen content, an attachment, or a relevant Memory. That provider's own terms and privacy policy apply.
- **Android permissions are optional.** Orbit asks for one only when you use or set up the feature that needs it, and you can decline.
- **Orbit contains no advertising and no analytics or crash-reporting services.**

## Information stored on your phone

Depending on the features you use, Orbit may store the following in its private app storage on your device:

- Conversations, and attachments kept with your conversation history. You can turn history off in Settings.
- Memory entries: things you asked Orbit to remember, or suggestions you accepted.
- Orbit Vault items, and pictures you saved to the Vault.
- Reminders, Routines and their triggers, and Custom Commands.
- Saved places you created.
- App profiles: your per-app privacy and screen settings.
- Notification Intelligence data, if you enable it: which apps are included or excluded, and a local history of recent notifications, kept for 7 days by default (adjustable from 1 to 30 days).
- Orbit Deck layout, themes, appearance, and other settings.
- Extensions you installed, and their non-secret settings.
- Document viewer state for PDFs you opened in Orbit.
- Local diagnostics, shown only in Orbit's Diagnostics screen.

Orbit does not upload this stored data anywhere by itself. Parts of it can leave your device only in the situations described below, such as an AI request you send or a backup file you export.

## Screen context

When Orbit is your digital assistant, Android can give it the text and a screenshot of the screen you are looking at when you open Orbit. Orbit uses these only as part of that assistant request. It is **not** continuous screen recording.

- **By default, the screen is offered, not sent.** It is attached to a request only when you tap **Use screen** or **Select area**. Select area attaches only the part of the screen you choose.
- You can change this in Settings: turn screen text or screenshots off entirely, or turn on attaching the screen by default, for all apps or for specific apps.
- Apps that Orbit recognizes as sensitive, or that you mark as sensitive, never attach screen content automatically and do not provide screenshots.
- A screen thumbnail is saved with your conversation history only if you turn that option on. It is off by default.
- Screen content you attach is sent to your selected AI provider as part of that request.

## AI requests and providers

Orbit's AI features send your request to the provider you selected:

| Provider | Where your request goes |
| --- | --- |
| **ChatGPT account** | To OpenAI through your own ChatGPT account. OpenAI's terms and privacy policy apply. |
| **Private relay** (advanced) | To the HTTPS server address you entered. Whoever runs that server controls what happens next. |
| **Orbit Local** (GitHub edition only) | Nowhere. Answers are generated on your phone (see below). |

A request includes your message and anything you chose to include with it: attached screen content, files, images or clipboard text, relevant conversation history, and relevant Memory entries. If you ask about your notifications with Notification Intelligence enabled, the relevant notification details are included too.

Orbit does not control how long an AI provider keeps the data it receives. Please read the provider's privacy policy, for example [OpenAI's Privacy Policy](https://openai.com/policies/privacy-policy/).

Orbit does not switch you to a different provider on its own if a request fails.

## Signing in to ChatGPT

You sign in to ChatGPT in your browser, on OpenAI's own sign-in page. **Orbit never sees your password.** After you sign in, Orbit stores the resulting access tokens on your phone, encrypted with a key held in Android Keystore, so it can make requests on your behalf. Signing out in Orbit removes them.

Secret values you enter for Extensions are also encrypted with Android Keystore, as is an OpenRouter key if you enter one on its setup screen. OpenRouter chat is not currently available, so nothing is sent to OpenRouter. A private-relay access token is encrypted the same way where possible; if Android Keystore storage fails on a device, it falls back to Orbit's private app storage.

Credentials are never included in Orbit backups. That covers your ChatGPT sign-in, the private-relay access token, an OpenRouter key, and any secret values you entered for Extensions.

## Files, images, PDFs, clipboard, and shared content

Orbit reads a file, photo, PDF, or clipboard text only when you explicitly give it to Orbit: through an attachment picker, the camera, sharing to Orbit from another app, selecting text and choosing **Ask Orbit**, or the **Clipboard** attachment option. Orbit does not read your clipboard in the background.

Viewing and searching a PDF happen on your phone. An attachment, or a PDF page you ask about, is sent to your AI provider only when you send that request.

## Web images in answers

When an answer cites web pages, Orbit may load pictures from those pages to show alongside the answer. Orbit fetches the page and image directly from the website, sending no cookies or account information, but the website can see that a request came from your device, including your IP address. You can turn this off in Settings.

## Smart Vault

Smart Vault is an optional part of Orbit Vault, off until you turn it on in **Settings > Orbit Vault > Smart Vault**. Each part below is its own switch:

- **Local indexing** keeps a search index of your Vault in Orbit's private storage. Nothing leaves your phone.
- **Read text in pictures** recognises text in screenshots and photos you saved, on your phone, using Google ML Kit through Google Play services. Your pictures are not sent anywhere. Google Play services may download the recognition model and may collect anonymous usage statistics about the ML Kit feature.
- **Search by meaning** downloads a search model once (about 30 MB) from Hugging Face. After that, meaning search runs entirely on your phone. Hugging Face can see the download request, including your IP address.
- **Read saved links** opens the web page behind a link you saved, once, to read its title and text. That website can see the request, including your IP address. No cookies or account information are sent.
- **Suggest details** sends a saved item's text (and text Orbit read from it) to your chosen AI provider to get a suggested title, summary and topics. This happens for new items only if you turned it on, and for items you saved earlier only if you ask, one item or one confirmed batch at a time.
- **Ask Vault** attaches a few saved items that match your question to a normal chat. You see which items are attached, and nothing is sent until you press Send.

When your Vault is full, Orbit pauses new saves instead of deleting anything. **Delete Smart Vault data** removes the index and every suggestion without touching your saved items. The index is not included in backups; suggestions and topics are, because they are part of your Vault items.

## Voice

Voice input uses the speech recognition service installed on your phone, provided by Android or your phone's manufacturer, and spoken replies use your phone's text-to-speech engine. Those services are not operated by Orbit, and their own privacy practices apply. Orbit uses the microphone only while you are using voice input.

## Contacts, calendar, notifications, and device actions

These permissions are optional. Orbit asks for each one only when you use a feature that needs it:

- **Contacts:** used on your phone to find the number for a contact you name in a request such as "call" or "text", and to identify the recipient of an on-screen text conversation when you ask Orbit to prepare a reply. Orbit does not upload your contact list.
- **Calendar:** used to add an event you confirmed, to find a calendar that can hold it, and to check that the event was actually added and is not a duplicate.
- **Notification access:** used only if you enable Notification Intelligence in Android Settings. Orbit keeps a limited local history (see above), which you can clear at any time.
- **Camera:** used only when you take a photo to attach.
- **Microphone:** used only for voice input.
- **Alarms and reminders, Do Not Disturb, and modify system settings:** used to run timers, reminders, and Routines on time, and the Do Not Disturb and brightness controls you ask for.

Device actions such as timers, calls, messages, navigation, and sharing are handed to Android or to the app that performs them. For example, a message opens as a draft in your messaging app for you to send, and a calendar event is added only after you confirm it.

## Location

Location permission is optional.

**Google Play edition:**

- Orbit uses location only while you are using it: for weather at your current location (if you turn that option on), adding a Saved Place at your current position, and location conditions inside a Routine.
- The Google Play edition **does not request background location** and does not track your location in the background. Location-triggered Routines (arrive/leave) are not available in this edition.

**GitHub edition:**

- The same foreground uses as above.
- If you set up a location-triggered Routine and grant background location, Android checks on your phone whether you arrived at or left the places you chose, even while Orbit is closed. This check does not send your location anywhere.

**Weather:** Orbit gets weather from Open-Meteo, a third-party weather service. It sends either the place name you typed or, if you enabled weather for your current location, your current coordinates. No account or identifier is sent. See [Open-Meteo's terms](https://open-meteo.com/en/terms).

## Orbit Local (GitHub edition only)

Orbit Local is an optional on-device AI component available only with the GitHub edition. When you use it, answers are generated on your phone and your requests are not sent to a cloud AI provider.

The Google Play edition does not offer Orbit Local, cannot install it, and cannot use a copy installed by the GitHub edition. The two editions are signed differently, and Orbit Local works only with the GitHub edition.

## Extensions

Extensions are optional add-ons you install yourself. They are data files describing a web request, not programs. When you run an Extension, Orbit sends the information that Extension declares to the web address it names. Review an Extension before installing it; its operator's privacy practices apply to what it receives. Extension secrets are stored encrypted on your phone and are never included in backups.

## Backup and restore

Orbit can export your data to a backup file and restore it later:

- You choose where the file is saved, using Android's system file picker. Orbit does not operate any cloud backup service and does not upload the file anywhere.
- **Backup files are not encrypted.** Anyone who can open the file may be able to read what is inside. Store it privately, and check it before sharing it.
- **Included:** conversations and retained attachments, Memory, Orbit Vault items and pictures, Routines and triggers, Custom Commands, reminders, saved places, app profiles, notification settings, Extensions without their secrets, and personalization settings.
- **Not included:** credentials (your ChatGPT sign-in, the relay token, an OpenRouter key, Extension secrets), Android permissions and special access, your default-assistant setting, Beta-channel enrollment, and your Orbit Deck layout.

### Moving between the Google Play and GitHub editions

The two editions are signed separately, so Android does not let one update or replace the other. To move, export a backup, uninstall the edition you have, install the other one, and restore the backup. Afterwards, grant Android permissions again, set Orbit as your default assistant again if you want, and sign in to ChatGPT again.

## Deleting your data

- **Inside Orbit:** you can clear conversation history, clear all Memory or delete single entries, delete Vault items or all Vault data, clear the local notification history, delete Routines, reminders, saved places, and Extensions, and sign out of ChatGPT.
- **Uninstalling Orbit** removes the data in Orbit's private app storage, as Android does for any app.
- **Files you exported yourself**, such as backup files, stay wherever you saved them until you delete them.
- **Removing a permission** in Android Settings stops Orbit from using it from then on. It does not by itself delete data a feature already saved; use the matching screen in Orbit for that.
- **Data sent to an AI provider** is held under that provider's policies, not Orbit's. Use the provider's own tools to manage it.

Because Orbit has no server or account, there is no Orbit-side copy of your data to delete.

## Updates

- The **Google Play edition** is updated by Google Play. It does not download or install app updates itself.
- The **GitHub edition** can check the public Orbit GitHub releases for updates. It never downloads an update without your approval, verifies the file's checksum and signing certificate, and hands it to Android's installer for your confirmation.

Both editions may load release notes from the public Orbit GitHub repository to show in **What's New**. Those requests go to GitHub and include no personal information.

## Third-party services

Orbit works with these third parties only when you use the related feature. Orbit does not control them, and their own policies apply:

| Service | Used for |
| --- | --- |
| OpenAI (ChatGPT) | AI requests and sign-in, if you choose ChatGPT account mode. [Privacy Policy](https://openai.com/policies/privacy-policy/) |
| Open-Meteo | Weather answers. [Terms](https://open-meteo.com/en/terms) |
| GitHub | Release notes in What's New; update checks and Orbit Local downloads in the GitHub edition. [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement) |
| Google Play | Installing and updating the Google Play edition. [Google Privacy Policy](https://policies.google.com/privacy) |
| Websites cited in answers | Pictures shown with answers, if enabled |
| Google Play services (ML Kit) | On-device text recognition for Smart Vault, if you turn it on. [Google Privacy Policy](https://policies.google.com/privacy) |
| Hugging Face | The one-time Smart Vault search model download, if you turn on search by meaning. [Privacy Policy](https://huggingface.co/privacy) |
| Websites you saved as links | Reading a saved page's text, if you turn on Read saved links |
| Your phone's speech and text-to-speech services | Voice input and spoken replies |
| Your private relay or installed Extensions | Only if you configure them |

Orbit Pro is not available for purchase. If paid Orbit features are offered in a future release of the Google Play edition, purchases would be handled by Google Play Billing, and Orbit would not receive your payment card details. This policy will be updated before that happens.

## Security

Orbit sends network requests over HTTPS only, and stores credentials encrypted with Android Keystore (with the one relay-token exception described above). No method of storage or transmission is completely secure, so Orbit cannot guarantee absolute security. Keep your phone and any exported backup files protected.

## Children

Orbit is a general-purpose assistant and is not directed to children.

## Changes to this policy

When Orbit's handling of information changes, this policy will be updated and the "Last updated" date above will change. Every version of this policy is kept in the public repository's history.

## Contact

Questions about this policy or Orbit's privacy practices: **lpnovi.orbit@gmail.com**

Please don't include passwords, tokens, or private screen content in email or in public GitHub issues. To report a security vulnerability, follow the [Security policy](../SECURITY.md).
