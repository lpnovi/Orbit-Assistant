# Building Orbit Assistant from source

These instructions are for inspecting and producing a local **debug** build. Official release signing material is intentionally not included in the repository.

## Requirements

- Git
- JDK 17
- Android SDK with API 35 installed
- A 64-bit operating system supported by the Android build tools

Orbit's Gradle wrapper downloads the pinned Gradle distribution on first use. Dependency downloads require an internet connection.

## Clone and build the main app

```bash
git clone https://github.com/lpnovi/Orbit-Assistant.git
cd Orbit-Assistant
```

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your Android SDK, then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

The APK is written below `app/build/outputs/apk/debug/`.

Run the unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

On Windows, `BUILD_ORBIT.cmd` (a wrapper around `tools/build_orbit.ps1`) can also produce a debug APK. It locates or downloads JDK 17, the Android SDK packages, and Gradle for you, and writes `Orbit-Assistant-v<version>-debug.apk` to the repository root.

## Building with GitHub Actions (forks)

If you fork Orbit, the **Build Orbit APK** workflow (`.github/workflows/build-apk.yml`) builds a debug APK on every push to `main` and can also be started manually from the **Actions** tab. It installs JDK 17 and the required Android SDK packages itself and needs no secrets. The APK is attached to the workflow run as an artifact.

GitHub disables Actions on new forks until you enable them in your fork's **Actions** tab.

The **Release Orbit APK** workflow runs only for `v*` tags and requires the project's private signing secrets, so it will fail in a fork. That is expected; use the debug workflow instead.

## Optional Orbit Local component

Orbit Local is a separate APK because its inference runtime and downloaded models are optional. Its native runtime currently targets 64-bit ARM Android devices (`arm64-v8a`). Build its debug APK with:

```powershell
.\gradlew.bat :local:assembleDebug
```

The component APK is written below `local/build/outputs/apk/debug/`. A compatible device must still pass Orbit's runtime capability checks, and models are downloaded separately from inside Orbit.

## Signing and update behavior

Debug APKs use a developer debug key. They do not share Orbit's official permanent release identity and normally cannot update an official installation in place. Keep source-built debug installs separate from important production data.

Release builds deliberately fail unless all required signing values are provided. Do not request, copy, or commit the project's private keystore or signing credentials.

## Project layout

- `app/`: the main Orbit application
- `local/`: the optional Orbit Local component
- `ipc/`: the shared AIDL contract between the two APKs
- `docs/`: public technical documentation
- `examples/`: example declarative Orbit Extensions

Orbit Assistant is open source under the [Mozilla Public License 2.0](../LICENSE).
