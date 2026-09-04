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

The source currently has no repository-level software license. Being able to inspect or build the code does not by itself grant permission to redistribute it or create derivative works.
