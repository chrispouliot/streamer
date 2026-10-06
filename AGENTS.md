# Android app — agent guide

## Purpose and current state

This guide covers development of a new Kotlin Android app on an ARM64 Asus
Zenbook A14 running NixOS. The development flake also supports x86-64 Linux.

The app's name, application ID, module layout, screens, architecture, dependency
versions, and implementation status are not established by this guide. Inspect
the repository before making claims about what exists. Do not carry over
Bubbles-specific code, services, features, or validation results.

Read `flake.nix`, `nix/android.nix`, and the repository's README and Gradle files
when present. Keep this guide aligned with the actual project as it develops.
Distinguish planned work from implemented and verified behavior.

## Development environment

The revised flake imports `nix/android.nix` for the Android SDK and wrapped
build tools. Copy that file and all supporting files it references from the
existing working setup when creating the repository. The flake alone is not a
standalone SDK definition.

| Tool | Purpose |
|---|---|
| Native JDK 21 | Runs Gradle and JVM build tools. |
| Project Gradle wrapper | Pins the Gradle version; a standalone Gradle package is not supplied. |
| Android SDK and build tools | Supplied by `nix/android.nix`; inspect that file for exact versions. |
| Native ADB and fastboot | Device communication; ordinary app testing uses ADB. |
| FFmpeg/ffprobe and MediaInfo | Host-side audio inspection and test-fixture preparation. |
| SQLite CLI | Inspection of local database files. |
| Git/Git LFS, ripgrep, jq, curl, rsync, Python, zip/unzip | Repository and development utilities. |
| nixfmt and ShellCheck | Nix formatting and shell checks. |
| `android-doctor` | Launches Java, ADB, and the wrapped AAPT2 to check tool availability. |
| `android-gradle` | Runs the project's Gradle wrapper with the AAPT2 override. |
| `android-build` | Runs `assembleDebug`, passing additional arguments to Gradle. |
| `android-install` | Installs the debug APK and launches it; accepts `--no-launch`. |
| `android-run` | Builds, installs, and launches; accepts `--logcat`. |

Kotlin, Compose, and app libraries belong in Gradle. Host FFmpeg and SQLite do
not add codecs or database libraries to the APK. Select app dependencies when
implementing the corresponding feature; do not describe suggestions as already
installed or adopted.

Rust/Fenix, Cargo tooling, bindgen, and the Bubbles validation service are not
part of this development shell. The flake retains an `android-ndk` package
output for future native work, but does not configure an Android native build
or export NDK/Cargo compiler variables. Inspect the SDK definition before
assuming whether its SDK bundle also contains an NDK.

Android Studio, an Android emulator, scrcpy, Docker, and an agent CLI are not
supplied by this flake.

## ARM host and Android target

Keep these execution environments distinct:

| Role | Runtime |
|---|---|
| A14 development host | Native ARM64 Linux Java, Gradle, ADB, and helper tools. |
| Google's Linux native build tools | x86-64 executables, using the existing QEMU wrappers on the A14. |
| Android application | Runs on the Android device; any native libraries must match its Android ABI. |

Build-tool emulation does not run the Android app. Pure Kotlin/JVM code does
not require a Rust toolchain or an Android NDK build. If native code is added,
use an Android-targeting toolchain; host Linux libraries are not Android
libraries and must not be linked into the APK.

Preserve the existing host-tool wrapping implementation and its supporting
files. Keep host runtime patching separate from Android sysroots and target
libraries. Do not replace explicit QEMU wrappers with an assumption that a
global FEX or binfmt handler will be available, especially inside a sandbox.
On x86-64 Linux, the corresponding Google tools run natively.

Do not infer official ARM Linux SDK support or successful A14 execution from
flake evaluation alone.

## Initial project setup

- Inspect existing files before scaffolding or replacing anything.
- Choose the application ID, SDK levels, module structure, and plugin versions
  as part of project setup; this guide does not assign them.
- Ensure the Gradle wrapper, Android Gradle plugin, Kotlin plugins, JDK 21, and
  supplied Android SDK versions are compatible.
- Keep dependency and plugin versions in one consistent project convention.
- Commit the Gradle wrapper and `flake.lock`. Preserve the existing working
  nixpkgs pin initially unless an update is needed.
- The supplied install/run helpers assume an application module named `app`,
  an unflavored `debug` variant, and one APK. If the project differs, adapt the
  helpers before using them.

## Implementation principles

- Keep UI rendering separate from network, persistence, and other application
  operations. Choose concrete boundaries to fit the implemented features.
- Keep blocking work off the Android main thread. Handle cancellation, errors,
  lifecycle changes, and resource ownership explicitly.
- Avoid starting duplicate long-lived work through recomposition or rotation.
- Keep composables testable by passing state and callbacks where useful.
- Give persisted data a clear owner. Avoid competing stores for the same state.
- Test meaningful behavior at the appropriate layer. Use device tests for
  Android-specific behavior that host tests cannot establish.
- Keep credentials, private server details, tokens, signing keys, and personal
  data out of source control, APK assets, Nix expressions, and diagnostic logs.
- Keep generated build output out of source control.

## Development and testing

Enter the shell from the project root:

```bash
nix develop
android-doctor
```

Once an Android project and its Gradle wrapper exist:

```bash
android-build                         # Assemble the debug APK
android-gradle testDebugUnitTest       # Run JVM unit tests when configured
android-gradle lintDebug               # Run Android lint
android-run                           # Build, install, and launch
android-run --logcat                   # Also follow the running app's log
android-install --no-launch            # Install the existing APK only
```

`android-gradle` requires `./gradlew`. It exports the SDK and JDK paths and
passes the wrapped AAPT2 path explicitly. Plain `./gradlew` inherits the same
AAPT2 override through `GRADLE_OPTS` inside the shell. Preserve this override;
do not let Gradle silently substitute an unwrapped x86-64 AAPT2 on the A14.

The shell exports `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and
`ANDROID_AAPT2`. Configure SDK versions in Nix instead of running `sdkmanager`
against the read-only Nix store. Configure app SDK levels in Gradle; the shell
does not choose `minSdk`, `targetSdk`, or the app's supported ABIs.

Start Gradle inside the development shell. Stop existing daemons after a
relevant toolchain/environment change if they retain stale settings:

```bash
android-gradle --stop
```

The install helper reads the application ID and APK filename from
`app/build/outputs/apk/debug/output-metadata.json`; it does not hard-code the
app's identity. It rejects multiple APK entries. For a split-APK build, use the
appropriate Gradle installation task instead.

For physical-device testing, enable debugging and authorize the host first:

```bash
adb devices
android-install
# When instrumentation tests exist and the device is authorized:
android-gradle connectedDebugAndroidTest
```

The helpers can use USB or an already-connected wireless ADB device. If more
than one device is connected, set `ANDROID_SERIAL` for the helpers or use
`adb -s SERIAL` for individual ADB commands. Host USB permissions and wireless
pairing/connectivity are configured separately from the flake.

## Reproducibility and sandbox integration

- A dev shell may download dependencies. It is not a hermetic APK derivation,
  and the flake does not provide a default APK package.
- Preserve the project-local `.gradle-user-home/` and `.android-user-home/`
  across development sessions, including the debug signing identity. Keep
  these directories, build output, and machine-specific `local.properties`
  out of Git. Commit dependency locks when the project uses them.
- For Nix changes, run `nix fmt` and
  `nix flake check --no-build --all-systems`, then the relevant tool or build
  check. Evaluation alone does not establish executable compatibility.
- Use a separate Gradle home for sandbox builds to avoid sharing cache locks
  and daemon state across host/sandbox boundaries:

  ```bash
  GRADLE_USER_HOME="$PWD/.gradle-user-home-agent" android-gradle --no-daemon assembleDebug
  ```

- Keep that agent cache out of Git too. The sandbox must expose the project,
  required Nix store paths, environment, writable caches, and network access
  needed for dependencies. Inspect the actual launcher configuration before
  changing integration; no launcher is supplied by this flake.
- Use host ADB for device installation unless device access has explicitly
  been configured in the sandbox.
- Keep machine-level NixOS changes separate from project configuration.

## Verification reporting

No app build, device installation, or feature validation is established by
this guide. Record the commands actually run, the host architecture, their
results, and any remaining limitations. Distinguish shell syntax checks,
Nix evaluation, tool launches, APK assembly, unit tests, and device tests.
Do not carry forward verification claims from another app or machine.
