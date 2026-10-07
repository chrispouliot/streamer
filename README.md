# Streamer

Native Kotlin/Jetpack Compose Android player for a Navidrome server
(Subsonic/OpenSubsonic API). See `PLAN.md` for scope and phase status and
`AGENTS.md` for the development environment.

Status: Phase 1 (adaptive UI shell) implemented. Debug builds show a fictional demo
library and a silent simulated player; release builds show empty states. There is
no Navidrome connection, real audio playback or downloading yet.

## Project choices

| Setting | Value | Notes |
|---|---|---|
| App name | Streamer | Provisional; not approved branding. |
| Application ID / namespace | `dev.streamer.app` | Provisional; changing it after release breaks upgrades. |
| `minSdk` | 36 (Android 16) | |
| `compileSdk` / `targetSdk` | 37 (Android 17, platform `37.0`) | Current AndroidX releases require compileSdk ≥ 37. |
| Build tools | 37.0.0 | Pinned in `app/build.gradle.kts`; must match `nix/android.nix`. |
| Modules | single `:app` | Organized by package; split only when justified. |

## Toolchain versions

All library/plugin versions live in `gradle/libs.versions.toml`.

| Tool | Version |
|---|---|
| Gradle (wrapper, checksum-verified) | 9.8.0 |
| Android Gradle Plugin | 9.4.1 (built-in Kotlin support) |
| Kotlin / Compose compiler plugin | 2.4.20 |
| Compose BOM | 2026.09.00 |
| JDK (from Nix shell) | 21; bytecode target 17 |

Gradle is not installed by Nix: `./gradlew` downloads the pinned distribution
into `$GRADLE_USER_HOME`. The Android SDK is read-only in the Nix store, so
SDK components must be added in `nix/android.nix`, never via `sdkmanager`.

## Build

```bash
nix develop        # or direnv
android-doctor
android-build                              # debug APK
android-gradle testDebugUnitTest lintDebug
android-run --logcat                       # install + launch on a device
```
