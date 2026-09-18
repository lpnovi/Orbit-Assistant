# Orbit Assistant on Google Play

How Orbit is built, signed, versioned, and distributed through Google Play alongside its existing GitHub releases. Nothing in this document has been done on Google Play yet: no app has been created in Play Console, no key has been enrolled, and no bundle has been uploaded.

Tasks only the project owner can do are marked **OWNER INPUT REQUIRED**. The step-by-step Console work is in [PLAY_CONSOLE_CHECKLIST.md](PLAY_CONSOLE_CHECKLIST.md). Draft store copy is in [PLAY_LISTING_DRAFT.md](PLAY_LISTING_DRAFT.md).

## 1. One app, two channels

Orbit is one Android application, `com.orbit.assistant`, signed with one permanent identity. It is distributed two ways.

| | GitHub edition | Google Play edition |
| --- | --- | --- |
| Artifact | Signed APK on GitHub Releases | Android App Bundle (AAB) uploaded to Play |
| Gradle build type | `debug`, `release` (unchanged) | `play` |
| Built by | `release.yml` / `candidate.yml` / `build-apk.yml`, `tools/build_orbit.ps1` | `gradlew bundlePlay`, locally for now |
| Signed by | Orbit's permanent release key | Upload key; Play re-signs with Orbit's permanent key |
| App updates | Orbit's verified GitHub updater | Google Play only |
| `REQUEST_INSTALL_PACKAGES` | Requested | **Not present** in the merged manifest |
| Update channel (Stable / Beta) | In About & updates | Play testing tracks instead; no in-app channel |
| Orbit Local component | Installed from the matching GitHub Release | Not installable from the Play edition (see section 6) |
| Orbit Pro Preview (developer override) | Debug and Beta builds only | Never, on any track |
| Diagnostics shows | `Distribution: GitHub` | `Distribution: Google Play` |

Both editions are built from the same code except for one class, one manifest line, and one resource file (section 2). Apart from the rows above, features, conversations, settings, and backups are identical, and so is the package name, so a person can move between editions (section 9).

## 2. The distribution boundary

The Play edition does not contain a switched-off updater. It contains no updater.

- `app/src/github/java/.../OrbitEdition.java` is compiled into `debug` and `release`. It is the only code that knows Orbit's GitHub release endpoints and the only code that builds the intent handing an APK to Android's package installer.
- `app/src/play/java/.../OrbitEdition.java` is compiled into `play` instead. Same shape, so the rest of Orbit compiles unchanged, but every method throws and it reports that installs are never possible.
- `app/src/play/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES`, so Android itself would refuse an install request from the Play edition.
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

**OWNER INPUT REQUIRED for every step in this section.** Nothing here has been done.

Orbit's permanent release certificate:

```
7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3
```

The goal is that every Orbit APK on every phone, whether it came from GitHub or from Google Play, is signed by this one certificate. That is what lets updates cross channels, lets Orbit Local keep trusting Orbit, and keeps the certificate pin in `OrbitUpdater` and `release.yml` true.

The supported Google model for an app that already has its own key:

1. **App signing key** = Orbit's existing release key. You give Google an encrypted copy through Play App Signing's "use your existing key" enrollment (the PEPK tool). Google then signs every APK it delivers with it.
2. **Upload key** = a new, separate key you create. You sign bundles with it before uploading. Google checks the upload signature, discards it, and re-signs with the app signing key. If the upload key is ever lost or leaked, Google can reset it; the app signing key is not affected.
3. **GitHub releases** keep being signed by the same app signing key through `release.yml`, exactly as today.

### 5.1 Create the upload key

Run on your own PC, and store the keystore **outside the repository**, next to the release keystore:

```powershell
keytool -genkeypair -v -keystore <path outside the repo>\orbit-upload.jks -alias orbit-upload -keyalg RSA -keysize 4096 -validity 10000
```

Then add four lines to the existing, git-ignored `orbit-signing.properties`:

```properties
ORBIT_UPLOAD_STORE_FILE=<path outside the repo>\orbit-upload.jks
ORBIT_UPLOAD_KEY_ALIAS=orbit-upload
ORBIT_UPLOAD_STORE_PASSWORD=<your upload keystore password>
ORBIT_UPLOAD_KEY_PASSWORD=<your upload key password>
```

Export the upload **certificate** (public, safe to upload to Play Console):

```powershell
keytool -export -rfc -keystore <path outside the repo>\orbit-upload.jks -alias orbit-upload -file orbit-upload-certificate.pem
```

### 5.2 Enroll the existing key in Play App Signing

In Play Console, when the app's first release asks how it should be signed, choose to **use your own existing app signing key**, not the Google-generated key, and follow the PEPK instructions Play Console shows. Play Console supplies the `pepk.jar` download and an encryption public key for your account; the command has this shape:

```powershell
java -jar pepk.jar --keystore=<path to the Orbit release keystore> --alias=<release key alias> --output=orbit-app-signing-key.zip --include-cert --rsa-aes-encryption --encryption-key-path=<encryption key file from Play Console>
```

Upload the resulting encrypted file where Play Console asks, and register the upload certificate from 5.1.

Then confirm in Play Console under **App integrity > App signing** that the **app signing key certificate SHA-256** is exactly the value at the top of this section. If it is anything else, stop: do not release, because every existing Orbit user would be unable to move to or from the Play edition.

### 5.3 Rules that must never be broken

- Never choose the Google-generated app signing key for Orbit.
- Never accept a Play App Signing **key upgrade** or rotation for Orbit. It would give Play installs a different certificate from GitHub installs, and they would stop being able to update each other.
- Never commit, print, email, or paste the release keystore, the upload keystore, their passwords, `orbit-signing.properties`, the PEPK output file, or the encryption key file. `.gitignore` already covers `*.jks`, `*.keystore`, and `orbit-signing.properties`; keep all of these outside the repository anyway.
- Delete the PEPK output file once Play Console confirms the enrollment.
- The release key's CI copy stays in the `ORBIT_RELEASE_KEYSTORE_B64` secret. The upload key does not need to be in CI until Play uploads are automated, which is a separate decision.

## 6. Orbit Local

Orbit Local is a separate APK (`com.orbit.assistant.local`) that the GitHub edition downloads from the matching GitHub Release and hands to Android's installer. It carries native inference libraries. Google Play policy does not allow a Play-distributed app to install executable code from outside Play, so the Play edition cannot do that.

What is implemented now:

- The Play edition never downloads the component and never hands it to Android. Both are refused in code and impossible at the platform level (no install permission, no APK FileProvider directory).
- The Orbit Local screen and onboarding in the Play edition say **"Orbit Local isn't available in the Google Play edition of Orbit yet."** and show no setup button.
- A genuine component that is **already installed** (for example, by someone who moved from the GitHub edition) keeps working, because it is signed with the same certificate. If it needs updating, the Play edition says it cannot update it and offers only **Uninstall component**.
- The GitHub edition is unchanged.

**OWNER INPUT REQUIRED: choose the long-term Play option.**

| Option | What it means | Trade-offs |
| --- | --- | --- |
| A. Leave Orbit Local GitHub-only (current state) | Play users get cloud AI only | Simplest, zero policy risk. Play users who want offline AI must use the GitHub edition |
| B. Publish Orbit Local as its own Play app | A second listing for `com.orbit.assistant.local`, enrolled with the same app signing key, installed from its Play page | Keeps today's architecture and signature trust. A second listing to maintain, a component with no launcher icon to explain, its own Play review and Data safety form, and its native libraries must meet Play's 16 KB page-size requirement |
| C. Convert Orbit Local into a Play Feature Delivery module | The inference code becomes an on-demand module inside Orbit's own Play bundle | One listing and the smoothest install for Play users. A large rework of the component boundary and IPC, and the GitHub edition would need its own path for the same code |

Recommendation: **A now, B later if Play users ask for offline AI.** B reuses everything that already works; C is the best long-term user experience but is a project in its own right.

## 7. Permissions and Play policy

Source: the merged manifest of the built Play bundle. See [PLAY_CONSOLE_CHECKLIST.md](PLAY_CONSOLE_CHECKLIST.md#4-app-content-declarations) for the declaration text.

| Permission or access | Play edition | Why Orbit needs it | Play Console |
| --- | --- | --- | --- |
| `REQUEST_INSTALL_PACKAGES` | **Removed** | GitHub self-update and Orbit Local install only | Not applicable |
| `ACCESS_BACKGROUND_LOCATION` | Kept | Location-triggered Routines (arrive/leave) that run while Orbit is closed | **Declaration + video required.** Prominent disclosure wording needs updating first (below) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Kept | Weather, Saved Places, location triggers | Data safety: location |
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

**Background location prominent disclosure.** Play requires an in-app disclosure shown before the permission request that says what is collected and that it is used while the app is closed. Orbit explains the requirement but not in Play's required form, and on Android 10 it requests background location without a dialog first. Before submitting the background location declaration, Orbit's three location-setup screens should show wording like:

> Orbit collects location data to run your arrive and leave Routines, even when Orbit is closed or not in use. Location is checked on this phone against the places you set and is not sent anywhere for this feature.

**OWNER INPUT REQUIRED:** approve that wording (or edit it) so it can be implemented and verified in a Beta before the declaration is filed. Confirm the last sentence against the code at that time.

**AI-generated content policy.** Play requires apps that generate content with AI to let users report or flag offensive AI output without leaving the app. Orbit has no such control today. **OWNER INPUT REQUIRED:** decide the design (for example, a Report action on each assistant reply). This must exist before production and is likely checked at review.

## 8. Testing on Play

Order of work, all **OWNER INPUT REQUIRED**:

1. **Internal testing** (up to 100 testers, available within minutes, no full review). Use it to prove that a Play-installed Orbit installs, signs in, uses the Side button, and updates from Play.
2. **Closed testing.** If the developer account is a personal account created after 13 November 2023, Google requires a closed test with at least 12 opted-in testers for 14 continuous days before production access can be requested. Orbit Betas can go here.
3. **Open testing** (optional) for a public Beta on Play.
4. **Production** with a staged rollout (for example 10%, then 50%, then 100%), halting the rollout if the crash rate rises.

Play's testing tracks replace the in-app Beta channel for Play users. The same Beta build (same `versionCode`) can go to a Play testing track and to a GitHub prerelease.

## 9. Versioning across GitHub and Play

The published `v0.8.0.0` (versionCode 796) is not changed.

Rules:

1. **One version sequence for both channels.** Every release, Beta or Stable, is built once per channel from one tagged commit, and both artifacts carry identical `versionName` and `versionCode`. The `play` build type cannot override either; a test enforces it.
2. **Every build that leaves the machine gets a new `versionCode`**, +1, as today. Play remembers every code ever uploaded to any track and refuses a repeat, so a code used for a Play upload is spent even if that release is abandoned.
3. **Betas rank below the Stable that follows them**, as today: `0.8.1.0-beta.1` = 797, `-beta.2` = 798, `0.8.1.0` = 799. Betas go to GitHub prereleases and, optionally, a Play testing track. Play production receives Stable only.
4. **Never upload the current tree as 796.** The API 36 build differs from the published 796. The first Play upload must use the next version, and the GitHub release of that version should come from the same commit.
5. **Keep Play production and GitHub Stable on the same version.** If Play lags behind GitHub, a GitHub user cannot switch to the older Play build, because Android refuses a lower `versionCode`.

What moving between channels looks like when both carry the same signing certificate:

- **GitHub to Play:** installing Orbit's Play listing over a GitHub install is an ordinary update when the Play version is equal or higher. Data is kept, because it is the same app.
- **Play to GitHub:** installing a GitHub APK of an equal or higher version is an ordinary update; the in-app GitHub updater then takes over.
- **Lower version either way:** Android refuses it as a downgrade. The fix is to wait for the next release, never to reuse a number.
- After a switch, which store Android credits with future updates can vary by Android version. That is expected and harmless while both carry the same certificate.

Recommended next version, **OWNER INPUT REQUIRED:** `0.8.0.1` (797) if the next release is only this API 36 and Play groundwork plus the Stable Pro-message fix, or `0.8.1.0-beta.1` (797) if it starts a new Beta line.

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
- PEPK output files and the Play encryption public key file
- Play Console service-account JSON keys, if uploads are ever automated
- Play Billing license keys or server credentials, when billing arrives
- Any tester, reviewer, or ChatGPT account credentials

## 12. Tests

- `DistributionBoundaryTest` (runs in `testDebugUnitTest`, the GitHub edition): the GitHub edition keeps its endpoints, installer hand-off, and install permission; the package is `com.orbit.assistant` with no suffix or flavors; the `play` build type shares the version, is not debuggable, and never uses the release key; both modules target API 36; the application keeps legacy Back; unknown editions fail closed; the Play `OrbitEdition` source contains no GitHub endpoint or installer code; shared code reaches assets and the installer only through `OrbitEdition`; and every Pro Preview instruction goes through `lockedGuidance`.
- `PlayEditionTest`, `PlayManifestTest`, `PlayFileProviderPathsTest` (in `app/src/testPlay`, run by `testPlayUnitTest` against the real Play variant): the Play build identifies as Play and is not debuggable; every GitHub operation is refused before any network or installer; no update check is scheduled; Orbit Local cannot be downloaded or installed; About & updates shows only the Play message and the Play listing action; Pro Preview is never available; Deck's locked Pro sheet says Pro cannot be bought yet and never mentions Pro Preview; the merged manifest lacks only `REQUEST_INSTALL_PACKAGES`; the merged FileProvider paths lack only the APK directories.

`testPlayUnitTest` runs only the `Play*Test` classes; the shared suite describes the GitHub edition.

## 13. Before the first internal-test upload

- [ ] A version bump, section 9 (**OWNER INPUT REQUIRED**)
- [ ] Phone test of the API 36 GitHub debug build: Back on every screen type, Side-button overlay Back, onboarding, Screen Selection, attachment and PDF viewers, time and location Routines, reminders, notifications, widgets, Quick Settings tiles, Orbit Local, and an in-app update check
- [ ] Upload key created and configured, section 5.1 (**OWNER INPUT REQUIRED**)
- [ ] Play Console app created, App Signing enrolled with the existing key, certificate confirmed, section 5.2 (**OWNER INPUT REQUIRED**)
- [ ] `bundlePlay` rebuilt signed with the upload key
- [ ] Privacy policy URL, Data safety, and permission declarations entered, [checklist](PLAY_CONSOLE_CHECKLIST.md) (**OWNER INPUT REQUIRED**)
