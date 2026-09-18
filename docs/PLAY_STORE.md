# Orbit Assistant on Google Play

How Orbit is built, signed, versioned, and distributed through Google Play alongside its existing GitHub releases. Nothing in this document has been done on Google Play yet: no app has been created in Play Console, no key has been enrolled, and no bundle has been uploaded.

Tasks only the project owner can do are marked **OWNER INPUT REQUIRED**. The step-by-step Console work is in [PLAY_CONSOLE_CHECKLIST.md](PLAY_CONSOLE_CHECKLIST.md). Draft store copy is in [PLAY_LISTING_DRAFT.md](PLAY_LISTING_DRAFT.md).

## 1. One app, two channels

Orbit is one Android application, `com.orbit.assistant`, distributed two ways as two separately signed editions (section 5).

| | GitHub edition | Google Play edition |
| --- | --- | --- |
| Artifact | Signed APK on GitHub Releases | Android App Bundle (AAB) uploaded to Play |
| Gradle build type | `debug`, `release` (unchanged) | `play` |
| Built by | `release.yml` / `candidate.yml` / `build-apk.yml`, `tools/build_orbit.ps1` | `gradlew bundlePlay`, locally for now |
| App signing key | Orbit's permanent GitHub release key | Google-generated Play app signing key, held by Google |
| Uploads signed with | Not applicable | A separate Play upload key (section 5.1) |
| Can update the other edition | No | No |
| App updates | Orbit's verified GitHub updater | Google Play only |
| `REQUEST_INSTALL_PACKAGES` | Requested | **Not present** in the merged manifest |
| Update channel (Stable / Beta) | In About & updates | Play testing tracks instead; no in-app channel |
| Orbit Local component | Installed from the matching GitHub Release | Not available; a component from a GitHub install cannot be used (section 6) |
| Location-triggered Routines (`ACCESS_BACKGROUND_LOCATION`) | Available | Not available; the permission is **not present** (section 7.1) |
| Orbit Pro Preview (developer override) | Debug and Beta builds only | Never, on any track |
| Diagnostics shows | `Distribution: GitHub` | `Distribution: Google Play` |

Both editions are built from the same code except for one class, a two-line manifest overlay, and one resource file (section 2). Apart from the rows above, features and the backup format are the same, so a person can move between editions with a backup, uninstall and reinstall (section 9.1). The editions cannot update each other.

## 2. The distribution boundary

The Play edition does not contain a switched-off updater. It contains no updater.

- `app/src/github/java/.../OrbitEdition.java` is compiled into `debug` and `release`. It is the only code that knows Orbit's GitHub release endpoints and the only code that builds the intent handing an APK to Android's package installer.
- `app/src/play/java/.../OrbitEdition.java` is compiled into `play` instead. Same shape, so the rest of Orbit compiles unchanged, but every method throws and it reports that installs are never possible.
- `app/src/play/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES`, so Android itself would refuse an install request from the Play edition. It also removes `ACCESS_BACKGROUND_LOCATION` (section 7.1).
- `app/src/play/res/xml/file_paths.xml` drops the two APK directories from FileProvider, so the Play edition cannot even share an APK file with the installer.
- `OrbitDistribution` answers "which channel is this?" from `OrbitEdition.ID`, never from a preference, a server, or the installer package name. An unknown value is treated as Play, the more restrictive channel.
- `OrbitUpdater`, `OrbitUpdateWorker`, and `OrbitLocalInstaller` also refuse at their own entry points, so a refusal is immediate and explained rather than a network error.

What the Play edition does instead:

- **About & updates** says Google Play delivers updates and offers one action, **Open in Google Play**, which opens Orbit's Play listing. The Stable/Beta channel picker and Orbit's own update notifications are not shown.
- **What's New** still shows release notes from the public GitHub repository, as text. It never shows the "Update available" prompt and hides the GitHub download footer about verifying the APK.
- The background update check is never scheduled, and one left behind by an earlier GitHub install is cancelled.

The GitHub edition's updater, update channels, verification, and Orbit Local installer are the same mechanism as before: its endpoints and installer hand-off moved into `src/github` unchanged. The tests in section 12 prove both halves.

## 3. Building the App Bundle

```powershell
.\gradlew.bat bundlePlay
```

Output: `app/build/outputs/bundle/play/app-play.aab`

The `play` build type is not debuggable, not minified (like `release`), targets API 36, and uses the same `versionCode` and `versionName` as the GitHub build of the same commit.

Signing:

- If all four `ORBIT_UPLOAD_*` values are set (section 5), the bundle is signed with the upload key.
- If not, the bundle is built **unsigned** and Gradle prints a warning. Play Console rejects unsigned bundles, so an unsigned bundle cannot reach Play by accident.
- The `play` build type never uses the `ORBIT_RELEASE_*` key.

To inspect what Play will install, `.\gradlew.bat :app:packagePlayUniversalApk` writes a universal APK derived from the bundle to `app/build/outputs/apk_from_bundle/play/`.

None of the GitHub tasks changed: `assembleDebug`, `assembleRelease`, `app-debug.apk`, `app-release.apk`, and `local-release.apk` are produced exactly as before.

## 4. Android 16 (API 36)

Orbit now compiles against and targets API 36 in both `:app` and `:local`. `minSdk` stays 29 (Android 10).

Build-tool changes, and only these:

- Android Gradle Plugin 8.9.2 to 8.10.1. AGP 8.9 supports at most API 35; 8.10 is the first line that supports API 36, with the same Gradle 8.11.1, JDK 17, and Build Tools 35.0.0 Orbit already uses.
- The `platforms;android-36` SDK package, installed by `tools/build_orbit.ps1` and all three workflows.

Android 16 behaviour changes for apps targeting API 36, and what they mean for Orbit:

| Change | Effect on Orbit | Action taken |
| --- | --- | --- |
| Predictive Back on by default; `onBackPressed` stops being called | Would silently break Back on the Side-button overlay, the onboarding, the viewers, screen selection, the permission bridges, and the launch sequence, which all rely on `onBackPressed` | `android:enableOnBackInvokedCallback="false"` on `<application>` keeps the old default. The 32 screens already migrated keep their own `true`. **Needs phone testing.** |
| Edge-to-edge opt-out removed | None. Orbit already runs edge-to-edge under API 35 and never used the opt-out | None |
| Orientation and resizability ignored on screens 600dp and wider | Phones are unaffected. On tablets and unfolded foldables, Screen Selection's orientation lock is ignored, so the selection could rotate mid-crop | None yet; a large-screen check before Play production is recommended |
| `elegantTextHeight` ignored | Orbit does not set it | None |
| `scheduleAtFixedRate` catch-up change | Orbit does not use it | None |
| Health permission split | Orbit does not use body sensors | None |
| Stricter intent matching | Opt-in only at API 36 | None |
| Local network permission | Opt-in testing only. Orbit already blocks local and private network hosts for Extensions and Rich Answers | None |

Areas checked and found unaffected by the target change itself: background execution (WorkManager, no foreground service types), exact alarms (still `SCHEDULE_EXACT_ALARM` with a `canScheduleExactAlarms()` check), notifications, the `VoiceInteractionService`, widgets, services, FileProvider sharing, package visibility, settings access, and the notification listener.

A successful build does not prove Android 16 behaviour. The unit suite simulates Android 15 (API 35) because Robolectric needs a Java 21 test runtime to simulate API 36 and Orbit's toolchain is JDK 17; `app/src/test/resources/robolectric.properties` records that. **The API 36 build must be checked on the phone before any release** (section 13).

## 5. Signing model

**Decided: the two editions are signed with different keys, on purpose.**

| | GitHub edition | Google Play edition |
| --- | --- | --- |
| App signing key | Orbit's existing permanent GitHub release key | A Google-generated app signing key, created, held and protected by Google Play (Play App Signing) |
| Certificate | `7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3` | Whatever Play Console shows under **App integrity > App signing** once the app exists. Not known yet, and deliberately different |
| Who signs the delivered APK | `release.yml` in CI, from the `ORBIT_RELEASE_KEYSTORE_B64` secret | Google Play |
| What Orbit's maintainer signs with | The release key (CI) | A separate **upload key**, only to prove an upload came from Orbit. Google checks it and re-signs with the Play key |
| Updates | Orbit's GitHub updater, which pins the certificate above | Google Play only |
| Orbit Local | Supported; it requires the certificate above | Not available (section 6) |

What this means:

- The GitHub release key is **never** exported to Google, uploaded to Play Console, or used for Play. Nothing about the GitHub signing process changes: `release.yml`, `candidate.yml`, the certificate pin in `release.yml` and `OrbitUpdater`, and the Orbit Local trust checks all stay as they are.
- The Play edition's certificate is Google's. Nothing in Orbit pins it, and nothing needs to: Google Play verifies its own updates.
- Because the certificates differ, Android treats the two editions as incompatible installs of the same package name. **Neither edition can update or replace the other**, and Orbit makes no attempt to make them. Section 9 explains how a person moves between them.
- Keeping the Play key with Google means Google can also upgrade it later if Play ever requires that; it has no effect on GitHub builds.

### 5.1 The upload key

**Created on 2026-09-18.** A new RSA 4096 key, unrelated to the GitHub release key, valid until 2054:

| | |
| --- | --- |
| Alias | `orbit-play-upload` |
| Keystore | PKCS12, stored on the maintainer's PC **outside the repository**, never committed |
| Public certificate | `orbit-play-upload-certificate.pem`, beside the keystore; this is the file to register in Play Console |
| Upload certificate SHA-256 | `B0:0A:C8:FE:16:CF:30:2C:6F:F8:75:9A:A6:7E:90:21:10:79:F3:88:CE:91:41:43:D4:C0:92:6B:68:D2:FC:66` |

The four `ORBIT_UPLOAD_*` values live only in the git-ignored `orbit-signing.properties`, which `app/build.gradle` reads for the `play` build type only. Store file paths there use forward slashes, because a backslash is an escape character in a `.properties` file.

**OWNER INPUT REQUIRED:** back up the upload keystore and `orbit-signing.properties` together, somewhere private and outside the repository. The password exists nowhere else. If the upload key is ever lost or leaked, Play Console can reset it after you register a new upload certificate. The Play app signing key is unaffected, because Google holds it.

To re-create the public certificate from the keystore later:

```powershell
keytool -exportcert -rfc -storetype PKCS12 -keystore <upload keystore> -alias orbit-play-upload -file orbit-play-upload-certificate.pem
```

### 5.2 Play App Signing with a Google-generated key

**OWNER INPUT REQUIRED.** When Play Console asks how the new app should be signed, choose the **Google-generated app signing key** (the default for new apps). Do not choose any option that uploads or exports an existing key. Register the upload certificate from 5.1, or let the first signed upload register it, as Play Console offers.

Afterwards, record the Play app signing certificate SHA-256 from **App integrity > App signing** in your own notes. It is public and useful for support, but Orbit's code does not need it.

### 5.3 Rules that must never be broken

- Never export, upload, or enroll the GitHub release key with Google Play, in any form. Never run PEPK or any other key-export tool on it.
- Never sign a Play bundle with the GitHub release key. The `play` build type cannot use `ORBIT_RELEASE_*`, and a test enforces that.
- Never commit, print, email, or paste the release keystore, the upload keystore, their passwords, or `orbit-signing.properties`. `.gitignore` covers `*.jks`, `*.keystore`, `*.pem`, and `orbit-signing.properties`; keep all of these outside the repository anyway.
- The release key's CI copy stays in the `ORBIT_RELEASE_KEYSTORE_B64` secret. The upload key does not need to be in CI until Play uploads are automated, which is a separate decision.

## 6. Orbit Local

Orbit Local is a separate APK (`com.orbit.assistant.local`) that the GitHub edition downloads from the matching GitHub Release and hands to Android's installer. It carries native inference libraries. Google Play policy does not allow a Play-distributed app to install executable code from outside Play, so the Play edition cannot do that.

What is implemented now:

- The Play edition never downloads the component and never hands it to Android. Both are refused in code and impossible at the platform level (no install permission, no APK FileProvider directory).
- The Orbit Local screen and onboarding in the Play edition say **"Orbit Local isn't available in the Google Play edition of Orbit yet."** and show no setup button.
- A component left behind by a GitHub install is **never used** by the Play edition. It is signed with the GitHub release key, only serves an Orbit carrying that same certificate (its bind permission is signature-level), and the Play edition carries Google Play's signature. The Play edition therefore treats every installed component as unusable, says so, and offers only to uninstall it, through Android's own confirmation.
- The GitHub edition is unchanged.

**Decided for the initial Play release: option A.** Orbit Local is available through the GitHub edition only. The Play edition does not download, install, or update it, and there is no separate Play listing for it. Options B and C stay on record for later:

| Option | What it means | Trade-offs |
| --- | --- | --- |
| A. Leave Orbit Local GitHub-only (current state) | Play users get cloud AI only | Simplest, zero policy risk. Play users who want offline AI must use the GitHub edition |
| B. Publish Orbit Local as its own Play app | A second listing for `com.orbit.assistant.local`, installed from its Play page | Keeps today's architecture, but not its trust model: two Play apps each get their own Google-generated key, so the signature-level link would need replacing (for example with Android's known-signer permissions pinned to the Play certificates). A second listing to maintain, a component with no launcher icon to explain, its own Play review and Data safety form, and its native libraries must meet Play's 16 KB page-size requirement |
| C. Convert Orbit Local into a Play Feature Delivery module | The inference code becomes an on-demand module inside Orbit's own Play bundle | One listing and the smoothest install for Play users. A large rework of the component boundary and IPC, and the GitHub edition would need its own path for the same code |

If Play users ask for offline AI later, C avoids the cross-app signing problem entirely and is the best long-term user experience, but it is a project in its own right. B needs its trust model redesigned for Play signing first.

## 7. Permissions and Play policy

Source: the merged manifest of the built Play bundle. See [PLAY_CONSOLE_CHECKLIST.md](PLAY_CONSOLE_CHECKLIST.md#4-app-content-declarations) for the declaration text.

| Permission or access | Play edition | Why Orbit needs it | Play Console |
| --- | --- | --- | --- |
| `REQUEST_INSTALL_PACKAGES` | **Removed** | GitHub self-update and Orbit Local install only | Not applicable |
| `ACCESS_BACKGROUND_LOCATION` | **Removed** for the initial Play release | Only location-triggered Routines use it, and the Play edition does not offer them (below) | No background location declaration or video |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Kept | Weather, Saved Places, and Routine IF conditions, all while Orbit is open | Data safety: location |
| `READ_CONTACTS` | Kept | Voice "call/text <name>" resolves a spoken name to a number; SMS reply finds the on-screen recipient. The Android Contact Picker cannot resolve a spoken name | Play now prompts READ_CONTACTS apps for a declaration; required for apps targeting API 37+ from January 2027 |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Kept | Adding a confirmed event, choosing a writable calendar, and reading it back to prove it was added | Data safety: calendar |
| `SCHEDULE_EXACT_ALARM` | Kept | Time-triggered Routines and reminders at the minute the user chose; the user grants it in Android Settings | No declaration (that applies to `USE_EXACT_ALARM`, which Orbit does not use) |
| Notification listener | Kept | Notification Intelligence, enabled by the user in Android Settings | Data safety: in-app messages/notifications; mention in reviewer notes |
| `RECORD_AUDIO` | Kept | Voice input | Data safety: audio |
| `CAMERA` | Kept | Taking a photo as an attachment | Data safety: photos |
| `WRITE_SETTINGS` | Kept | Brightness control, granted in Android Settings | No declaration |
| `ACCESS_NOTIFICATION_POLICY` | Kept | Do Not Disturb control, granted in Android Settings | No declaration |
| `REQUEST_DELETE_PACKAGES` | Kept | Removing an installed Orbit Local component; Android still asks the user | No declaration |
| Default assistant (`VoiceInteractionService`) | Kept | The Side-button overlay | Explain in reviewer notes |
| `FOREGROUND_SERVICE` | Added by WorkManager | No foreground service types are declared or used | Answer "no" if Console asks about foreground service types |

### 7.1 Location-triggered Routines in the Play edition

The initial Play edition does not request background location, so arrive/leave Routine triggers are not available there. This is a distribution boundary like Orbit Local and self-updates, not a removed feature: the GitHub edition keeps location triggers exactly as they were.

How the boundary is built:

- `app/src/play/AndroidManifest.xml` removes `ACCESS_BACKGROUND_LOCATION`, so Android cannot grant it, whatever is asked.
- `OrbitDistribution.supportsLocationTriggers()` is false in the Play edition. `RoutineLocationTriggerScheduler` then reports no background access, is never "ready", and never arms a proximity monitor. Scheduling still cancels first, so a monitor armed by an earlier GitHub install is removed on the first reschedule (at start-up, boot, or app update).
- The proximity receiver disarms and ignores any stale event. It runs nothing and changes nothing.
- The shared setup helper never requests background location or opens Android's permission page in the Play edition.

What a Play user sees:

- **Automatic triggers:** time triggers as usual. In place of the location setup card: *"Location-triggered Routines aren't currently available in the Google Play edition of Orbit. Time triggers work as usual."* There is no "New location trigger" button, and no empty location section.
- **Saved location triggers** (for example, after moving from the GitHub edition) are still listed as *"Not available in the Google Play edition"*, with no on/off switch and only **Delete** in their menu.
- **Capabilities** shows Location triggers as "Not available", with no setup row. **Onboarding** does not offer location automation.
- **Routines list:** a location trigger is not counted as "on".
- **AI Routine Builder:** a drafted location trigger is shown as not available, and the editor does not offer to set it up. The routine itself is saved and runs manually or from a time trigger.
- The location triggers editor cannot be opened.
- Nothing points the user to the GitHub edition or to sideloading.

What stays: precise and approximate location for Saved Places ("Use my current location"), weather, and Routine IF location conditions evaluated while Orbit is open. A Routine with a location IF condition that fires from a time trigger in the background is handed off rather than evaluated, exactly as on a GitHub install that has not granted background location.

Saved configuration is safe across editions. The Play edition never deletes, disables, or rewrites a saved location trigger, including ones restored from a GitHub backup (section 9.1). Restore the same backup into a GitHub install and those triggers arm again once background location is granted.

Re-enabling location triggers on Play later would need: the permission restored in the Play build, an in-app prominent disclosure in Play's required form, shown before every background request including on Android 10, and the Play Console background location declaration with a video. Draft disclosure wording:

> Orbit collects location data to run your arrive and leave Routines, even when Orbit is closed or not in use. Location is checked on this phone against the places you set and is not sent anywhere for this feature.

### 7.2 AI-generated content reporting: production blocker

Play's AI-Generated Content policy requires apps that produce generative AI output to let users report or flag offensive AI output without leaving the app. Orbit has no such control, and **none has been added**: a control that looked like reporting but delivered nothing would mislead users and reviewers. **This blocks production release.** Internal testing is not blocked. **OWNER INPUT REQUIRED:** choose the report-delivery backend (where a report goes and what it contains).

The cleanest integration points already exist:

- **`MessageActions`** builds the long-press menu for every assistant reply on both surfaces: the full chat (`ChatActivity`) and the Side-button overlay (`OrbitSession`). A **Report** entry belongs beside Copy, Save to Vault, and Regenerate: add a `REPORT_MENU_LABEL` and icon in `assistantLabels`/`assistantIcons`, and handle it in `showAssistantMenu`. One change covers both surfaces, and it is only offered on assistant replies.
- **What travels:** `MessageActions.assistantCopyText(rawText)` is already the precise "visible words of this reply" boundary Copy and Save to Vault use: no hidden prompt, screen context, reasoning, or conversation. A report should start from the same text, plus a reason the user picks, and only with explicit confirmation of what is sent.
- **Delivery:** a small `AiContentReportSender` interface with the backend behind it, following the provider pattern `OrbitProEntitlementProvider` uses, so the menu does not know where reports go. Whether it applies to both editions or only to Play is part of the backend decision; Play needs it, and nothing stops the GitHub edition offering it too.
- **Tests:** the existing `MessageActions` menu tests pin the label arrays, so they document the change when it is made.

### 7.3 Other policy notes

None of the remaining permissions need a declaration beyond Data safety, except that Play Console now prompts apps holding `READ_CONTACTS` for a declaration (text in the checklist).

## 8. Testing on Play

Order of work, all **OWNER INPUT REQUIRED**:

1. **Internal testing** (up to 100 testers, available within minutes, no full review). Use it to prove that a Play-installed Orbit installs, signs in, uses the Side button, and updates from Play.
2. **Closed testing.** If the developer account is a personal account created after 13 November 2023, Google requires a closed test with at least 12 opted-in testers for 14 continuous days before production access can be requested. Orbit Betas can go here.
3. **Open testing** (optional) for a public Beta on Play.
4. **Production** with a staged rollout (for example 10%, then 50%, then 100%), halting the rollout if the crash rate rises.

Play's testing tracks replace the in-app Beta channel for Play users. The same Beta build (same `versionCode`) can go to a Play testing track and to a GitHub prerelease.

## 9. Versioning across GitHub and Play

The published `v0.8.0.0` (versionCode 796) is not changed.

Orbit keeps one version sequence so that "Orbit 0.8.0.1" means the same features whichever store it came from. Matching numbers do **not** make the two editions interchangeable: they are signed differently (section 5), so Android never lets one update or replace the other.

Rules:

1. **One version sequence for both editions.** A release is built for each edition from one tagged commit, and both artifacts carry the same `versionName` and `versionCode`. The `play` build type cannot override either; a test enforces it.
2. **Every build that leaves the machine gets a new `versionCode`**, +1, as today. Play remembers every code ever uploaded to any track and refuses a repeat, so a code used for a Play upload is spent even if that release is abandoned. GitHub never reuses one either.
3. **Betas rank below the Stable that follows them**, as today: `0.8.1.0-beta.1` = 797, `-beta.2` = 798, `0.8.1.0` = 799. Betas go to GitHub prereleases and, optionally, a Play testing track. Play production receives Stable only.
4. **Never upload the current tree as 796.** The API 36 build differs from the published 796, and the first Play candidate is 0.8.0.1 / 797.
5. Play may lag behind GitHub, or skip a GitHub-only release, without harming anyone: each edition's users only ever receive updates from their own store.

**Decided:** the next distributable candidate is `versionName 0.8.0.1`, `versionCode 797`, for both editions. The tree is now at 0.8.0.1 / 797. Nothing is tagged or published.

### 9.1 Moving between the GitHub and Play editions

Because the two editions are signed with different keys, Android refuses to install one over the other, whatever the version numbers. Moving from one to the other is a reinstall:

1. **Export an Orbit backup** in the edition you are leaving (Settings > Personalization & data > **Export Orbit backup**), and keep the file somewhere private. Backups are not encrypted.
2. **Uninstall** that edition. This removes its app data from the phone.
3. **Install** the other edition, from GitHub Releases or from Google Play.
4. **Import the backup** in the new edition (Settings > Personalization & data > **Restore Orbit backup**).
5. **Re-grant what Android keeps per install:** runtime permissions, notification access, Do Not Disturb and settings access, exact alarms, and Orbit as the default digital assistant. Sign in to ChatGPT again: credentials and Extension secrets are deliberately never in backups.

Things that do not carry over: the GitHub edition's update channel, Orbit Local, and the Orbit Deck layout (not part of backups yet). Location-triggered Routines restored into the Play edition are kept but shown as not available (section 7.1), and work again if the backup is later restored into a GitHub install.

## 10. Orbit Pro on Play

Not implemented. There is no billing library, no product, and no purchase button.

What already exists and does not need to change:

- Every Pro feature asks only `OrbitProEntitlement.hasPro(context)`. None knows why it is unlocked.
- `OrbitProEntitlementProvider` is the plug-in point: a name for Diagnostics and a fast, non-blocking `hasPro`.
- Stable resolves Pro only through providers, and there are none. The Play edition additionally never offers the developer Pro Preview, whatever its version name, so a Play testing track cannot unlock Pro for free.
- Every locked Pro surface gets its sentence from `OrbitProEntitlement.lockedGuidance(...)`. Today that says "Orbit Pro isn't available for purchase yet." on Stable and Play. When billing exists, that one method becomes the route to the purchase page.

Recommended shape when billing is built:

- A `PlayBillingEntitlementProvider` in `app/src/play/java`, with the Play Billing Library added as a `playImplementation` dependency, so the GitHub edition never carries it. `OrbitEdition` supplies the provider list (the Play copy returns the Billing provider, the GitHub copy returns none), and `OrbitProEntitlement` reads it in place of today's empty list.
- The provider keeps the last verified, acknowledged purchase state locally so `hasPro` answers instantly, and refreshes it with `queryPurchasesAsync` in the background at start-up and on resume. Purchases must be acknowledged within three days or Google refunds them.
- **Product:** one-time, non-consumable in-app product. Not a subscription.
- **Product ID:** recommended `orbit_pro`. **OWNER INPUT REQUIRED:** a product ID is permanent. It can never be renamed or reused, even after deletion, and the code depends on it forever, so choosing it is a product decision.
- **Price:** set only in Play Console, never in code. **OWNER INPUT REQUIRED.**
- **Purchase and restore UI:** a new **Orbit Pro** page opened from **Settings > About & updates** and from every locked Pro sheet (through `lockedGuidance`'s successor). It shows what Pro adds, the price Play reports, **Buy Orbit Pro**, and **Restore purchase**. Nothing is shown until billing actually works.
- **OWNER INPUT REQUIRED:** whether the GitHub edition will ever sell Pro, and how. Play policy requires Play Billing for digital goods only inside the Play edition.

## 11. Secrets that must never be committed

- The release keystore and its passwords (`ORBIT_RELEASE_*`)
- The upload keystore and its passwords (`ORBIT_UPLOAD_*`)
- `orbit-signing.properties`
- Play Console service-account JSON keys, if uploads are ever automated
- Play Billing license keys or server credentials, when billing arrives
- Any tester, reviewer, or ChatGPT account credentials

## 12. Tests

- `DistributionBoundaryTest` (runs in `testDebugUnitTest`, the GitHub edition): the GitHub edition keeps its endpoints, installer hand-off, and install permission; the package is `com.orbit.assistant` with no suffix or flavors; the `play` build type shares the version, is not debuggable, and never uses the release key; both modules target API 36; the application keeps legacy Back; unknown editions fail closed; the Play `OrbitEdition` source contains no GitHub endpoint or installer code; shared code reaches assets and the installer only through `OrbitEdition`; and every Pro Preview instruction goes through `lockedGuidance`.
- `PlayLocationTriggersTest` and `PlayOrbitLocalSigningTest` (in `app/src/testPlay`, run by `testPlayUnitTest`): location triggers are never offered, armed, or requested on Play while saved ones are kept and foreground location works; and no Orbit Local component is ever presented as usable, only as removable.
- `PlayEditionTest`, `PlayManifestTest`, `PlayFileProviderPathsTest` (in `app/src/testPlay`, run by `testPlayUnitTest` against the real Play variant): the Play build identifies as Play and is not debuggable; every GitHub operation is refused before any network or installer; no update check is scheduled; Orbit Local cannot be downloaded or installed; About & updates shows only the Play message and the Play listing action; Pro Preview is never available; Deck's locked Pro sheet says Pro cannot be bought yet and never mentions Pro Preview; the merged manifest lacks only `REQUEST_INSTALL_PACKAGES`; the merged FileProvider paths lack only the APK directories.

`testPlayUnitTest` runs only the `Play*Test` classes; the shared suite describes the GitHub edition.

## 13. Before the first internal-test upload

- [x] Bump to `0.8.0.1` / 797, section 9
- [ ] Phone test of the API 36 GitHub debug build: Back on every screen type, Side-button overlay Back, onboarding, Screen Selection, attachment and PDF viewers, time and location Routines, reminders, notifications, widgets, Quick Settings tiles, Orbit Local, and an in-app update check
- [ ] Phone test of the Play build (a universal APK from the bundle, or the internal-test install): no location trigger offered, time triggers and Saved Places "Use my current location" working, About & updates showing Google Play
- [x] Upload key created and configured, section 5.1 (back it up: **OWNER INPUT REQUIRED**)
- [ ] Play Console app created with a **Google-generated** app signing key, upload certificate registered, section 5.2 (**OWNER INPUT REQUIRED**)
- [x] `bundlePlay` rebuilt signed with the upload key
- [ ] Privacy policy URL, Data safety, and permission declarations entered, [checklist](PLAY_CONSOLE_CHECKLIST.md) (**OWNER INPUT REQUIRED**)
