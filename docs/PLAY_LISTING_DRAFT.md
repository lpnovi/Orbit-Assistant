# Google Play listing draft

Draft copy for Orbit Assistant's Google Play listing. **Not published.** Re-check every statement against the build you actually upload, and against Play's current metadata policy, before pasting it into Play Console.

Ground rules, same as [LAUNCH_MATERIALS.md](LAUNCH_MATERIALS.md): no user counts, downloads, ratings, endorsements, press, certifications, or partnerships. No personal names. Do not promise Side-button support on every phone. Third-party names (ChatGPT, Samsung, Android) appear only to state compatibility, never in the app name, icon, or short description.

Differences from the GitHub copy that matter here: the Play edition is updated by Google Play, Orbit Local (offline AI) is not available in the Play edition yet, and the initial Play edition has no location-triggered (arrive/leave) Routines. The Play listing must not offer either.

## App name (30 characters max)

```
Orbit Assistant
```

## Short description (80 characters max)

```
An AI assistant that opens over any app and uses your screen only when you ask.
```

(79 characters.) Alternative:

```
Open-source AI assistant: screen context you control, real actions, your style.
```

## Full description (4,000 characters max)

```
Orbit Assistant is a free, open-source AI assistant for Android, built for the moments when switching to a chatbot app breaks your flow.

OPENS OVER ANY APP
Set Orbit as your phone's digital assistant and call it with the assistant gesture or, on many Samsung phones, the Side button. It appears over the app you are using, and the same conversation can continue in Orbit's full chat app when you need more room.

YOUR SCREEN, ON YOUR TERMS
By default, Orbit offers the current screen instead of sending it. Tap Use screen to attach it, or Select area to share just part of it. You can change this for all apps or for individual apps, and apps you mark as sensitive never attach screen content automatically.

IT CAN ACTUALLY DO THINGS
Ask Orbit to set a timer or alarm, create a reminder, add a calendar event (with a confirmation that names the calendar), control media, turn on the flashlight or Do Not Disturb, start navigation, or share text. Android's own screens and confirmations stay in charge of anything sensitive.

A REAL CHAT APP TOO
Conversation history, image and file attachments, and a built-in PDF viewer you can ask questions about.

ROUTINES AND SHORTCUTS
Build multi-step Routines with conditions, and run them on a schedule, from a Quick Settings tile, or from a home-screen widget. Custom Commands turn your own phrases into actions.

ORBIT DECK AND THEME STUDIO
Orbit Deck is your own board of apps, prompts, and controls, with sections and folders. Theme Studio restyles the whole app: accent colours, AMOLED black, and Solid, Frosted, or Liquid surfaces, with a live preview and shareable theme files.

AI PROVIDER
Orbit connects to your own ChatGPT account through a browser sign-in; Orbit never sees your password. Your account's plan and limits apply. Advanced users can point Orbit at their own private HTTPS relay instead.

PRIVACY
There is no Orbit account and no Orbit server. Your chats, Memory, Routines, and settings are stored on your phone. A request, and anything you choose to attach to it, is sent only to the AI provider you selected. Your ChatGPT sign-in is stored encrypted with Android Keystore.

OPEN SOURCE
Orbit Assistant is open source under the Mozilla Public License 2.0. The full source is on GitHub.

GOOD TO KNOW
• Requires Android 10 or newer.
• Primarily tested on a Galaxy S25 Ultra. Side-button mapping is a Samsung feature and is not available on every phone; the assistant gesture, app, tile, and widgets work elsewhere.
• Location, contacts, calendar, notifications, microphone, and camera access are all optional and asked for only when you use the feature that needs them.
• Orbit Assistant is an independent project. It is not affiliated with or endorsed by OpenAI, Samsung, or Google.
```

Check before use: length (Play Console shows the count), and that every feature named is in the uploaded build.

## First Play release notes (500 characters max)

```
Orbit Assistant is now on Google Play.

• Opens over any app as your digital assistant
• Shares your screen only when you ask
• Timers, reminders, calendar events, media, and more
• Routines, Orbit Deck, and Theme Studio
• Built for Android 16

Updates for this version arrive through Google Play.
```

## Reviewer explanation (App access and notes)

Short form for the Play Console "notes for reviewers" field:

```
Orbit Assistant is a general-purpose AI assistant that the user can set as Android's default digital assistant (VoiceInteractionService), so it can open over other apps from the assistant gesture or, on Samsung phones, the Side button.

Every sensitive capability is optional and requested only when the user turns on the feature that needs it:
- Location (while Orbit is open): weather, saved places, and location conditions inside a Routine. This edition does not request background location.
- Contacts: resolving a spoken name ("call Alex") to a phone number, and finding the recipient of an on-screen SMS to prepare a reply.
- Calendar: adding an event after the user confirms it, then reading it back to confirm it was added.
- Notification listener: optional Notification Intelligence, enabled by the user in Android Settings.
- Exact alarms: time-triggered Routines and reminders, granted by the user in Android Settings.
- Write settings and Do Not Disturb access: brightness and Do Not Disturb commands, granted by the user in Android Settings.
- Delete packages: removing Orbit's optional offline-AI component if it is installed. Android asks the user to confirm.

This edition never downloads or installs APKs and does not request REQUEST_INSTALL_PACKAGES; all app updates come from Google Play. Orbit Extensions are declarative JSON files describing HTTPS requests to public endpoints; they contain no executable code.
```

## Reviewer setup and test instructions

```
1. Install and open Orbit Assistant. Onboarding explains each optional permission; all can be skipped.
2. To use the assistant overlay: Settings > Apps > Default apps > Digital assistant app > Orbit Assistant (onboarding links here). Then long-press the Side button on a Samsung phone, or use the system assistant gesture.
3. AI chat: Settings > Models & access > sign in with ChatGPT. [OWNER INPUT REQUIRED: test account details, or a note that the reviewer's own account is needed.]
4. Without signing in you can test: Routines (Settings > Routines), Orbit Deck, Theme Studio, reminders, widgets, Quick Settings tiles, Orbit Vault, and the PDF viewer.
5. Routines > a routine > Automatic triggers offers time triggers. This edition does not offer location-triggered Routines and says so on that screen.
6. About & updates shows that updates are delivered by Google Play.
```

Check the menu paths in steps 3 to 6 against the uploaded build before submitting; Settings sections move between releases.
