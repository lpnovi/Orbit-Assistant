$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BuildRoot = Join-Path $env:LOCALAPPDATA "OrbitAssistant\BuildTools"
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$GradleVersion = "8.11.1"
$GradleHome = Join-Path $BuildRoot "gradle-$GradleVersion"

# Read Orbit's actual Android version from app/build.gradle so the builder
# never gets stuck using an old hard-coded APK filename again.
$AppGradle = Join-Path $ProjectRoot "app\build.gradle"
if (-not (Test-Path $AppGradle)) { throw "app\build.gradle was not found." }
$AppGradleText = Get-Content $AppGradle -Raw
$VersionMatch = [regex]::Match($AppGradleText, 'versionName\s+["'']([^"'']+)["'']')
if (-not $VersionMatch.Success) { throw "Could not read versionName from app\build.gradle." }
$OrbitVersion = $VersionMatch.Groups[1].Value
$OutputApk = Join-Path $ProjectRoot "Orbit-Assistant-v$OrbitVersion-debug.apk"
$BuildLog = Join-Path $ProjectRoot "orbit-build.log"
$JdkUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
$CmdToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
$GradleUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Download-File([string]$Url, [string]$Destination) {
    Write-Host "    Downloading: $Url" -ForegroundColor DarkGray
    $parent = Split-Path $Destination -Parent
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Remove-Item $Destination -Force -ErrorAction SilentlyContinue

    # WebClient is deliberately used instead of winget/PowerShell package-provider
    # plumbing because some Windows configurations mis-handle native progress output.
    try {
        $client = New-Object System.Net.WebClient
        $client.Headers.Add("User-Agent", "OrbitAssistantBuilder/$OrbitVersion")
        $client.DownloadFile($Url, $Destination)
        $client.Dispose()
    }
    catch {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if (-not $curl) { throw }
        Write-Host "    WebClient failed; retrying with Windows curl..." -ForegroundColor Yellow
        & curl.exe -L --fail --retry 3 --retry-delay 2 -o $Destination $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed for $Url (curl exit code $LASTEXITCODE)." }
    }

    if (-not (Test-Path $Destination)) { throw "Download did not create $Destination." }
    if ((Get-Item $Destination).Length -lt 1024) { throw "Downloaded file from $Url is unexpectedly small." }
}

function Find-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )

    $adoptiumRoot = Join-Path $env:ProgramFiles "Eclipse Adoptium"
    if (Test-Path $adoptiumRoot) {
        $adoptium = Get-ChildItem $adoptiumRoot -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "jdk-17*" } | Sort-Object Name -Descending | Select-Object -First 1
        if ($adoptium) { $candidates += $adoptium.FullName }
    }

    $portableRoot = Join-Path $BuildRoot "jdk17-extract"
    if (Test-Path $portableRoot) {
        $portableJava = Get-ChildItem $portableRoot -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
        if ($portableJava) {
            $candidates += (Split-Path (Split-Path $portableJava.FullName -Parent) -Parent)
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) { return $candidate }
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path $javaCommand.Source -Parent
        return (Split-Path $javaBin -Parent)
    }
    return $null
}

function Install-JavaIfNeeded {
    $javaHome = Find-JavaHome
    if ($javaHome) { return $javaHome }

    Write-Step "Java 17 was not found. Downloading a private portable Temurin JDK 17"
    New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
    $zip = Join-Path $BuildRoot "temurin-jdk17.zip"
    $extract = Join-Path $BuildRoot "jdk17-extract"
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $extract | Out-Null

    Download-File $JdkUrl $zip
    Write-Host "    Extracting Java..." -ForegroundColor DarkGray
    Expand-Archive -Path $zip -DestinationPath $extract -Force

    $javaExe = Get-ChildItem $extract -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
    if (-not $javaExe) { throw "Temurin JDK downloaded, but java.exe was not found after extraction." }

    $javaHome = Split-Path (Split-Path $javaExe.FullName -Parent) -Parent
    if (-not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) { throw "The downloaded Java package does not contain javac.exe." }
    return $javaHome
}

function Ensure-AndroidSdk {
    $sdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $sdkManager)) {
        Write-Step "Downloading official Android command-line tools"
        New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot "cmdline-tools") | Out-Null
        $zip = Join-Path $BuildRoot "commandlinetools.zip"
        $extract = Join-Path $BuildRoot "cmdtools-extract"
        Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $extract | Out-Null
        Download-File $CmdToolsUrl $zip
        Write-Host "    Extracting Android command-line tools..." -ForegroundColor DarkGray
        Expand-Archive -Path $zip -DestinationPath $extract -Force
        $latest = Join-Path $SdkRoot "cmdline-tools\latest"
        Remove-Item $latest -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $latest | Out-Null
        Copy-Item (Join-Path $extract "cmdline-tools\*") $latest -Recurse -Force
    }

    if (-not (Test-Path $sdkManager)) { throw "Android sdkmanager could not be installed." }

    Write-Step "Accepting Android SDK licenses"
    $answers = (1..200 | ForEach-Object { "y" })
    $answers | & $sdkManager "--sdk_root=$SdkRoot" --licenses | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Android SDK license step failed (exit code $LASTEXITCODE)." }

    Write-Step "Installing Android API 35 and Build Tools 35.0.0"
    & $sdkManager "--sdk_root=$SdkRoot" "platform-tools" "platforms;android-35" "build-tools;35.0.0" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Android SDK package installation failed (exit code $LASTEXITCODE)." }

    return $sdkManager
}

function Ensure-Gradle {
    $gradleBat = Join-Path $GradleHome "bin\gradle.bat"
    if (Test-Path $gradleBat) { return $gradleBat }

    Write-Step "Downloading Gradle $GradleVersion"
    New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
    $zip = Join-Path $BuildRoot "gradle-$GradleVersion-bin.zip"
    Download-File $GradleUrl $zip
    Write-Host "    Extracting Gradle..." -ForegroundColor DarkGray
    Expand-Archive -Path $zip -DestinationPath $BuildRoot -Force

    if (-not (Test-Path $gradleBat)) { throw "Gradle $GradleVersion could not be installed." }
    return $gradleBat
}

try {
    Write-Host "Orbit Assistant automated Android build v$OrbitVersion" -ForegroundColor Magenta
    Write-Host "Project: $ProjectRoot"

    # Clear old root-level Orbit APK copies to avoid version-name confusion.
    Get-ChildItem $ProjectRoot -Filter "Orbit-Assistant-v*-debug.apk" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    Get-ChildItem $ProjectRoot -Filter "Orbit-Assistant-v*-debug.apk.sha256.txt" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue

    Write-Step "Finding a compatible Java runtime"
    $javaHome = Install-JavaIfNeeded
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    Write-Host "    Java home: $javaHome" -ForegroundColor DarkGray
    & (Join-Path $javaHome "bin\java.exe") -version
    if ($LASTEXITCODE -ne 0) { throw "Java exists but could not start (exit code $LASTEXITCODE)." }

    Write-Step "Preparing Android SDK"
    Ensure-AndroidSdk | Out-Null
    $env:ANDROID_HOME = $SdkRoot
    $env:ANDROID_SDK_ROOT = $SdkRoot

    Write-Step "Preparing Gradle"
    $gradle = Ensure-Gradle

    Write-Step "Compiling Orbit Assistant"
    Push-Location $ProjectRoot
    try {
        Remove-Item $BuildLog -Force -ErrorAction SilentlyContinue

        # Native tools such as Gradle and the Android SDK legitimately write warnings
        # to stderr. Windows PowerShell converts redirected native stderr into
        # ErrorRecord objects; with the script-wide ErrorActionPreference=Stop, a
        # harmless warning can otherwise abort the wrapper before Gradle finishes.
        # Temporarily allow native stderr through and decide success strictly from
        # Gradle's process exit code.
        $previousErrorActionPreference = $ErrorActionPreference
        $nativePrefExists = $null -ne (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue)
        if ($nativePrefExists) { $previousNativePref = $PSNativeCommandUseErrorActionPreference }
        try {
            $ErrorActionPreference = "Continue"
            if ($nativePrefExists) { $PSNativeCommandUseErrorActionPreference = $false }
            & $gradle --no-daemon --stacktrace clean assembleDebug 2>&1 | Tee-Object -FilePath $BuildLog
            $gradleExit = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
            if ($nativePrefExists) { $PSNativeCommandUseErrorActionPreference = $previousNativePref }
        }

        if ($gradleExit -ne 0) { throw "Gradle build failed (exit code $gradleExit). Full log: $BuildLog" }
    } finally {
        Pop-Location
    }

    $builtApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $builtApk)) { throw "Gradle finished but app-debug.apk was not found." }
    Copy-Item $builtApk $OutputApk -Force

    $hash = (Get-FileHash $OutputApk -Algorithm SHA256).Hash
    Set-Content -Path "$OutputApk.sha256.txt" -Value "$hash  $(Split-Path $OutputApk -Leaf)" -Encoding ASCII

    Write-Step "BUILD SUCCESSFUL"
    Write-Host "APK: $OutputApk" -ForegroundColor Green
    Write-Host "SHA-256: $hash"
    Write-Host ""
    Write-Host "Copy the APK to your Galaxy S25 Ultra and install it."
    exit 0
}
catch {
    Write-Host ""
    Write-Host "BUILD FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($_.ScriptStackTrace) {
        Write-Host ""
        Write-Host "Builder location: $($_.ScriptStackTrace)" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "Send ChatGPT a screenshot of this window. If orbit-build.log exists, you can upload that too."
    exit 1
}
